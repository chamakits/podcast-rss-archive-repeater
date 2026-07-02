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

    # Optional: re-encode downloaded episodes to save disk space (typically
    # 50-75% smaller). Requires ffmpeg on the PATH. Lossy but hard to hear
    # at these settings; already-cached episodes are not touched.
    # transcode:
    #   enabled: true
    #   codec: opus       # opus: smallest, most podcatchers (not iOS/Apple
    #                     # Podcasts); aac: m4a, plays everywhere incl. iOS
    #   bitrateKbps: 32   # 32 suits speech with opus; use 64+ for aac

    podcasts:
      # - id: my-show              # short unique id, used in URLs
      #   url: https://example.com/feed.xml
      #   title: My Favorite Show  # optional, for the /api/podcasts listing
      #   transcode:               # optional per-podcast override
      #     enabled: true
      #     codec: aac
      #     bitrateKbps: 64

    users:
      # The server generates a key per user into generated/user-keys.yaml.
      # Feed URLs look like: http://<host>/feed/<key>/<podcast-id>
      # - name: alice
""".trimIndent() + "\n"

fun main(args: Array<String>) {
    // Artwork stamping uses AWT; make sure it never looks for a display.
    System.setProperty("java.awt.headless", "true")
    // Podcast enclosures are often wrapped in chains of tracking redirectors
    // (gum.fm -> podderapp -> mgln.ai -> the real CDN...) that are deeper than
    // the JDK's default limit of 5, which makes HttpClient give up and return
    // the last 302 as the response.
    System.setProperty("jdk.httpclient.redirects.retrylimit", "16")
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
        // ALWAYS rather than NORMAL: some tracking redirectors hop through
        // plain-http URLs, which NORMAL refuses to follow from https.
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    val transcoder = Transcoder(configStore)
    val transcodeInUse = configStore.config.transcode.enabled ||
        configStore.config.podcasts.any { it.transcode?.enabled == true }
    if (transcodeInUse && transcoder.available()) {
        log.info("Transcoding enabled: new episodes are re-encoded per the transcode config")
    }

    val episodeStore = EpisodeStore(dataDir, http, transcoder)
    val artworkStore = ArtworkStore(dataDir, http)
    val feedMirror = FeedMirror(http, episodeStore, artworkStore, transcoder)

    Server(configStore, feedMirror, episodeStore, artworkStore).start(configStore.config.port)
    log.info("Serving data dir {} on port {}", dataDir, configStore.config.port)
}
