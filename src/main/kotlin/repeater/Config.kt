package repeater

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

data class PodcastConfig(
    val id: String,
    val url: String,
    val title: String? = null,
    // Overrides the global transcode section for this podcast when set.
    val transcode: TranscodeConfig? = null,
)

data class UserConfig(
    val name: String,
)

data class TranscodeConfig(
    // When true, downloaded episodes are re-encoded to save disk space
    // (typically 50-75% smaller). Requires ffmpeg on the PATH.
    // Episodes already in the cache are not touched.
    val enabled: Boolean = false,
    // "opus": best compression, plays in most podcatchers (gPodder,
    //         AntennaPod...) but NOT in iOS/Apple Podcasts.
    // "aac":  AAC in an m4a container, plays everywhere including iOS.
    val codec: String = "opus",
    // For opus, 32 is comfortable for speech; for aac use 64 or higher.
    val bitrateKbps: Int = 32,
    // When true, a transcoded podcast looks for another configured podcast
    // with the same url and no transcoding, and sources episodes from that
    // podcast's cache (downloading into it if needed) instead of fetching
    // its own copy - one origin download serves both feeds. When false (the
    // default), it downloads independently and keeps only the transcoded
    // file.
    @JsonAlias("check-local-first")
    val checkLocalFirst: Boolean = false,
)

data class AppConfig(
    val port: Int = 8080,
    // Optional. When unset, links in mirrored feeds are built from the Host
    // header of the incoming request, which is usually what you want.
    val baseUrl: String? = null,
    val podcasts: List<PodcastConfig> = emptyList(),
    val users: List<UserConfig> = emptyList(),
    val transcode: TranscodeConfig = TranscodeConfig(),
)

/**
 * Loads config.yaml from the data directory and manages generated user keys.
 *
 * Keys live in a separate generated file (generated/user-keys.yaml) so that
 * config.yaml stays purely hand-edited: you add a user by name, the server
 * generates and remembers the key for that name.
 */
class ConfigStore(private val dataDir: Path) {
    private val log = LoggerFactory.getLogger(ConfigStore::class.java)
    private val yaml: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    val configFile: Path = dataDir.resolve("config.yaml")
    private val keysFile: Path = dataDir.resolve("generated").resolve("user-keys.yaml")

    @Volatile
    var config: AppConfig = AppConfig()
        private set

    /** user name -> generated key */
    @Volatile
    var userKeys: Map<String, String> = emptyMap()
        private set

    fun load() {
        if (!Files.exists(configFile)) {
            error("Config file not found: $configFile")
        }
        val loaded = yaml.readValue<AppConfig>(configFile.toFile())

        val duplicateIds = loaded.podcasts.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicateIds.isEmpty()) { "Duplicate podcast ids in config: $duplicateIds" }
        val transcodeSections = listOf("transcode" to loaded.transcode) +
            loaded.podcasts.mapNotNull { p -> p.transcode?.let { "podcasts[${p.id}].transcode" to it } }
        for ((where, t) in transcodeSections) {
            require(t.codec in setOf("opus", "aac")) { "$where.codec must be \"opus\" or \"aac\", got \"${t.codec}\"" }
            require(t.bitrateKbps in 6..510) { "$where.bitrateKbps must be between 6 and 510, got ${t.bitrateKbps}" }
        }

        config = loaded
        userKeys = loadOrGenerateKeys(loaded.users)
        log.info(
            "Config loaded from {}: {} podcast(s), {} user(s)",
            configFile, loaded.podcasts.size, loaded.users.size
        )
    }

    fun podcast(id: String): PodcastConfig? = config.podcasts.find { it.id == id }

    /**
     * Appends a podcast to config.yaml and reloads. The entry is inserted
     * right below the `podcasts:` line (or a new section is added at the end
     * if there is none) so the hand-edited file keeps its comments and
     * layout. The candidate content is parsed and validated before it
     * replaces the file, so a bad insert can never corrupt the config.
     */
    @Synchronized
    fun addPodcast(
        id: String,
        url: String,
        title: String?,
        transcode: Boolean = false,
        checkLocalFirst: Boolean = true,
    ): PodcastConfig {
        require(id.matches(Regex("[a-z0-9][a-z0-9-]*"))) {
            "id must be lowercase letters, digits and dashes, got '$id'"
        }

        val entryLines = mutableListOf("  - id: $id", "    url: ${quote(url)}")
        if (title != null) entryLines += "    title: ${quote(title)}"
        if (transcode) {
            entryLines += "    transcode:"
            entryLines += "      enabled: true"
            entryLines += "      codec: aac       # AAC-LC in m4a: plays natively on iOS/Apple Podcasts"
            entryLines += "      bitrateKbps: 64  # ~half the size of the usual 128 kbps MP3"
            entryLines += "      checkLocalFirst: $checkLocalFirst  # source originals from an untranscoded entry with the same url"
        }

        val original = Files.readString(configFile)
        val originalParsed = yaml.readValue<AppConfig>(original)
        require(originalParsed.podcasts.none { it.id == id }) { "A podcast with id '$id' already exists" }

        val lines = original.lines()
        val sectionAt = lines.indexOfFirst { it.matches(Regex("""podcasts:\s*(#.*)?""")) }
        val updated = (
            if (sectionAt >= 0) lines.take(sectionAt + 1) + entryLines + lines.drop(sectionAt + 1)
            else lines + listOf("", "podcasts:") + entryLines
            ).joinToString("\n")

        val parsed = yaml.readValue<AppConfig>(updated)
        check(parsed.podcasts.size == originalParsed.podcasts.size + 1 &&
            parsed.podcasts.any {
                it.id == id && it.url == url && (it.transcode?.enabled == true) == transcode &&
                    (!transcode || it.transcode?.checkLocalFirst == checkLocalFirst)
            }) {
            "Could not insert into $configFile automatically - please add the podcast by hand"
        }

        val temp = configFile.resolveSibling("config.yaml.tmp")
        Files.writeString(temp, updated)
        Files.move(temp, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        load()
        log.info("Added podcast '{}' ({}) to {}", id, url, configFile)
        return podcast(id)!!
    }

    /** YAML-safe double-quoted scalar for user-supplied values. */
    private fun quote(value: String) =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Returns the user name that owns this key, or null if the key is unknown. */
    fun userForKey(key: String): String? =
        userKeys.entries.find { it.value == key }?.key

    private fun loadOrGenerateKeys(users: List<UserConfig>): Map<String, String> {
        val existing: Map<String, String> =
            if (Files.exists(keysFile)) yaml.readValue(keysFile.toFile()) else emptyMap()

        val keys = existing.toMutableMap()
        for (user in users) {
            if (user.name !in keys) {
                keys[user.name] = newKey()
                log.info("Generated key for new user '{}': {}", user.name, keys[user.name])
            }
        }

        if (keys != existing) {
            Files.createDirectories(keysFile.parent)
            val header = "# Generated by the server. One key per user from config.yaml.\n" +
                "# Delete a line and reload to force a new key for that user.\n"
            Files.writeString(
                keysFile,
                header + keys.entries.joinToString("") { "${it.key}: ${it.value}\n" }
            )
        }

        // Only users still present in config.yaml may access the server, even
        // if old keys remain in the generated file.
        val active = users.map { it.name }.toSet()
        return keys.filterKeys { it in active }
    }

    private fun newKey(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
