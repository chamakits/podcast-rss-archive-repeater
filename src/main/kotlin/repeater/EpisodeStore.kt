package repeater

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.fileSize

/** One entry in a podcast's episode index: what we know from the feed. */
data class IndexEntry(
    val url: String,
    val title: String? = null,
)

/** Sidecar metadata written next to each cached media file. */
data class EpisodeMeta(
    val url: String,
    val title: String? = null,
    val contentType: String,
    val sizeBytes: Long,
    val downloadedAt: String,
    // Set when the episode was transcoded to Opus: the size as downloaded.
    val originalSizeBytes: Long? = null,
)

data class CachedEpisode(
    val mediaFile: Path,
    val meta: EpisodeMeta,
    val wasAlreadyCached: Boolean,
)

/**
 * All episode state lives on disk, no database:
 *
 *   cache/<podcastId>/episodes.json     index: episodeId -> original url + title
 *   cache/<podcastId>/feed.xml          last successfully fetched original feed
 *   cache/<podcastId>/<episodeId>.media the cached episode audio
 *   cache/<podcastId>/<episodeId>.meta.json
 *   archive/<podcastId>/<timestamp>-<episodeId>.*   archived copies
 *
 * Episode ids are a hash of the original enclosure URL, so they are stable
 * across restarts and feed refetches.
 */
class EpisodeStore(
    dataDir: Path,
    private val http: HttpClient,
    private val transcoder: Transcoder,
    private val configStore: ConfigStore,
) {
    private val log = LoggerFactory.getLogger(EpisodeStore::class.java)
    private val json = jacksonObjectMapper().writerWithDefaultPrettyPrinter()
    private val jsonReader = jacksonObjectMapper()

    private val cacheDir = dataDir.resolve("cache")
    private val archiveDir = dataDir.resolve("archive")

    /** One lock object per episode so concurrent clients trigger a single download. */
    private val downloadLocks = ConcurrentHashMap<String, Any>()

    /** One lock per podcast for index read-merge-write cycles. */
    private val indexLocks = ConcurrentHashMap<String, Any>()

    companion object {
        fun episodeId(originalUrl: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(originalUrl.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
    }

    private fun podcastCacheDir(podcastId: String) = cacheDir.resolve(podcastId)
    private fun mediaFile(podcastId: String, episodeId: String) = podcastCacheDir(podcastId).resolve("$episodeId.media")
    private fun metaFile(podcastId: String, episodeId: String) = podcastCacheDir(podcastId).resolve("$episodeId.meta.json")
    private fun indexFile(podcastId: String) = podcastCacheDir(podcastId).resolve("episodes.json")
    private fun feedFile(podcastId: String) = podcastCacheDir(podcastId).resolve("feed.xml")

    // ---- feed copy -------------------------------------------------------

    fun saveFeedCopy(podcastId: String, xml: ByteArray) {
        Files.createDirectories(podcastCacheDir(podcastId))
        writeAtomically(feedFile(podcastId)) { Files.write(it, xml) }
    }

    /**
     * Writes to a temp file, then atomically renames onto the target, so
     * concurrent readers always see either the old or the new complete file,
     * never a half-written one.
     */
    private fun writeAtomically(target: Path, write: (Path) -> Unit) {
        val temp = target.resolveSibling("${target.fileName}.tmp")
        write(temp)
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun cachedFeedCopy(podcastId: String): ByteArray? =
        feedFile(podcastId).takeIf { it.exists() }?.let { Files.readAllBytes(it) }

    // ---- episode index ---------------------------------------------------

    /** Merges new entries into the index so episodes that drop off the feed stay resolvable. */
    fun updateIndex(podcastId: String, entries: Map<String, IndexEntry>) {
        val lock = indexLocks.computeIfAbsent(podcastId) { Any() }
        synchronized(lock) {
            Files.createDirectories(podcastCacheDir(podcastId))
            val merged = readIndex(podcastId) + entries
            writeAtomically(indexFile(podcastId)) { json.writeValue(it.toFile(), merged) }
        }
    }

    fun readIndex(podcastId: String): Map<String, IndexEntry> {
        val file = indexFile(podcastId)
        if (!file.exists()) return emptyMap()
        return jsonReader.readValue(file.toFile())
    }

    fun indexEntry(podcastId: String, episodeId: String): IndexEntry? =
        readIndex(podcastId)[episodeId]

    // ---- cache -----------------------------------------------------------

    fun isCached(podcastId: String, episodeId: String): Boolean =
        mediaFile(podcastId, episodeId).exists() && metaFile(podcastId, episodeId).exists()

    /**
     * Returns the cached episode, downloading it from the original source
     * first if we don't have it yet.
     */
    fun getOrDownload(podcast: PodcastConfig, episodeId: String, entry: IndexEntry): CachedEpisode {
        val podcastId = podcast.id
        val lock = downloadLocks.computeIfAbsent("$podcastId/$episodeId") { Any() }
        synchronized(lock) {
            if (isCached(podcastId, episodeId)) {
                return CachedEpisode(
                    mediaFile = mediaFile(podcastId, episodeId),
                    meta = readMeta(podcastId, episodeId),
                    wasAlreadyCached = true,
                )
            }
            return download(podcast, episodeId, entry)
        }
    }

    /**
     * The configured podcast that mirrors the same origin url without
     * transcoding, if any - the source of originals for checkLocalFirst.
     */
    private fun untranscodedTwin(podcast: PodcastConfig): PodcastConfig? =
        configStore.config.podcasts.find {
            it.id != podcast.id && it.url == podcast.url && !transcoder.settingsFor(it).enabled
        }

    private fun download(podcast: PodcastConfig, episodeId: String, entry: IndexEntry): CachedEpisode {
        val podcastId = podcast.id

        val settings = transcoder.settingsFor(podcast)
        if (settings.enabled && settings.checkLocalFirst) {
            val twin = untranscodedTwin(podcast)
            if (twin != null) return transcodeFromTwin(podcast, twin, episodeId, entry)
            log.warn(
                "[{}] checkLocalFirst is set but no untranscoded podcast in the config shares url {} - downloading directly",
                podcastId, podcast.url
            )
        }

        log.info("[{}] downloading episode {} from {}", podcastId, episodeId, entry.url)
        val startedAt = System.currentTimeMillis()

        Files.createDirectories(podcastCacheDir(podcastId))
        val target = mediaFile(podcastId, episodeId)
        val partFile = podcastCacheDir(podcastId).resolve("$episodeId.part")

        val request = HttpRequest.newBuilder(URI.create(entry.url)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            response.body().close()
            error("Origin returned HTTP ${response.statusCode()} for ${entry.url}")
        }

        val originalContentType = response.headers().firstValue("Content-Type").orElse("audio/mpeg")
        var contentType = originalContentType
        var originalSizeBytes: Long? = null

        val transcodedPartFile = podcastCacheDir(podcastId).resolve("$episodeId.transcode.part")
        try {
            response.body().use { body ->
                Files.copy(body, partFile, StandardCopyOption.REPLACE_EXISTING)
            }
            if (transcoder.wantsTranscode(podcast, originalContentType) &&
                transcoder.transcode(partFile, transcodedPartFile, podcast, "$podcastId/$episodeId")
            ) {
                contentType = transcoder.contentTypeFor(podcast)
                originalSizeBytes = partFile.fileSize()
                Files.move(transcodedPartFile, partFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.move(partFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.deleteIfExists(partFile)
            Files.deleteIfExists(transcodedPartFile)
            throw e
        }

        val meta = EpisodeMeta(
            url = entry.url,
            title = entry.title,
            contentType = contentType,
            sizeBytes = target.fileSize(),
            downloadedAt = Instant.now().toString(),
            originalSizeBytes = originalSizeBytes,
        )
        json.writeValue(metaFile(podcastId, episodeId).toFile(), meta)

        log.info(
            "[{}] cached episode {} ({} bytes in {} ms)",
            podcastId, episodeId, meta.sizeBytes, System.currentTimeMillis() - startedAt
        )
        return CachedEpisode(mediaFile = target, meta = meta, wasAlreadyCached = false)
    }

    /**
     * Sources the episode from the untranscoded twin's cache instead of the
     * origin: reuses the twin's copy when cached, otherwise downloads into
     * the twin's cache (where it stays, serving the twin's feed), then
     * transcodes it into this podcast's cache.
     *
     * Nesting getOrDownload here is deadlock-free: a transcoded podcast
     * takes the twin's lock while holding its own, but never the reverse -
     * the twin's downloads never touch a transcoded podcast's lock.
     */
    private fun transcodeFromTwin(
        podcast: PodcastConfig,
        twin: PodcastConfig,
        episodeId: String,
        entry: IndexEntry,
    ): CachedEpisode {
        val raw = getOrDownload(twin, episodeId, entry)
        log.info(
            "[{}] sourcing episode {} from '{}' cache instead of re-downloading",
            podcast.id, episodeId, twin.id
        )

        Files.createDirectories(podcastCacheDir(podcast.id))
        val target = mediaFile(podcast.id, episodeId)
        val partFile = podcastCacheDir(podcast.id).resolve("$episodeId.part")
        val transcodedPartFile = podcastCacheDir(podcast.id).resolve("$episodeId.transcode.part")

        var contentType = raw.meta.contentType
        var originalSizeBytes: Long? = null
        try {
            if (transcoder.wantsTranscode(podcast, raw.meta.contentType) &&
                transcoder.transcode(raw.mediaFile, transcodedPartFile, podcast, "${podcast.id}/$episodeId")
            ) {
                contentType = transcoder.contentTypeFor(podcast)
                originalSizeBytes = raw.mediaFile.fileSize()
                Files.move(transcodedPartFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                // Transcode failed or doesn't apply: serve the same bytes as the twin.
                Files.copy(raw.mediaFile, partFile, StandardCopyOption.REPLACE_EXISTING)
                Files.move(partFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            Files.deleteIfExists(partFile)
            Files.deleteIfExists(transcodedPartFile)
            throw e
        }

        val meta = EpisodeMeta(
            url = entry.url,
            title = entry.title,
            contentType = contentType,
            sizeBytes = target.fileSize(),
            downloadedAt = Instant.now().toString(),
            originalSizeBytes = originalSizeBytes,
        )
        json.writeValue(metaFile(podcast.id, episodeId).toFile(), meta)
        log.info("[{}] cached episode {} ({} bytes, from local original)", podcast.id, episodeId, meta.sizeBytes)
        return CachedEpisode(mediaFile = target, meta = meta, wasAlreadyCached = false)
    }

    fun readMeta(podcastId: String, episodeId: String): EpisodeMeta =
        jsonReader.readValue(metaFile(podcastId, episodeId).toFile())

    // ---- archive ---------------------------------------------------------

    /**
     * Moves the cached copy of an episode into the archive directory. Because
     * the file leaves the cache, the next client request re-downloads it from
     * the original source. Returns the archived media path.
     */
    fun archive(podcastId: String, episodeId: String): Path {
        val lock = downloadLocks.computeIfAbsent("$podcastId/$episodeId") { Any() }
        synchronized(lock) {
            check(isCached(podcastId, episodeId)) {
                "Episode $episodeId of '$podcastId' is not in the cache, nothing to archive"
            }
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val targetDir = archiveDir.resolve(podcastId)
            Files.createDirectories(targetDir)

            val archivedMedia = targetDir.resolve("$timestamp-$episodeId.media")
            Files.move(mediaFile(podcastId, episodeId), archivedMedia, StandardCopyOption.ATOMIC_MOVE)
            Files.move(metaFile(podcastId, episodeId), targetDir.resolve("$timestamp-$episodeId.meta.json"), StandardCopyOption.ATOMIC_MOVE)

            log.info("[{}] archived episode {} to {}", podcastId, episodeId, archivedMedia)
            return archivedMedia
        }
    }

    // ---- stats for the listing endpoint ----------------------------------

    data class CacheStats(val episodeCount: Int, val totalBytes: Long)

    fun cacheStats(podcastId: String): CacheStats {
        val dir = podcastCacheDir(podcastId)
        if (!dir.exists()) return CacheStats(0, 0)
        val mediaFiles = Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".media") }.toList()
        }
        return CacheStats(mediaFiles.size, mediaFiles.sumOf { it.fileSize() })
    }
}
