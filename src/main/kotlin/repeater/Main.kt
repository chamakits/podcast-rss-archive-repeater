package repeater

import org.slf4j.LoggerFactory
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

private val EXAMPLE_CONFIG = """
    # podcast-rss-archive-repeater configuration.
    # Edit freely, then POST /api/reload to apply without restarting.

    port: 8080

    # Optional: uncomment to pin the host used in mirrored feed links.
    # By default links are built from the Host header of each request.
    # baseUrl: http://192.168.1.10:8080

    podcasts:
      # - id: my-show              # short unique id, used in URLs
      #   url: https://example.com/feed.xml
      #   title: My Favorite Show  # optional, for the /api/podcasts listing

    users:
      # The server generates a key per user into generated/user-keys.yaml.
      # Feed URLs look like: http://<host>/feed/<key>/<podcast-id>
      # - name: alice
""".trimIndent() + "\n"

fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("repeater.Main")

    val dataDir = Path.of(args.firstOrNull() ?: "./data").toAbsolutePath().normalize()
    Files.createDirectories(dataDir)

    val configStore = ConfigStore(dataDir)
    if (!Files.exists(configStore.configFile)) {
        Files.writeString(configStore.configFile, EXAMPLE_CONFIG)
        log.info("No config found. Wrote a commented template to {} - edit it and start again.", configStore.configFile)
        return
    }
    configStore.load()

    val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    val episodeStore = EpisodeStore(dataDir, http)
    val feedMirror = FeedMirror(http, episodeStore)

    Server(configStore, feedMirror, episodeStore).start(configStore.config.port)
    log.info("Serving data dir {} on port {}", dataDir, configStore.config.port)
}
