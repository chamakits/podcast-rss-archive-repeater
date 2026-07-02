package repeater

import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import io.javalin.http.UnauthorizedResponse
import org.slf4j.LoggerFactory
import java.io.FileInputStream
import kotlin.io.path.fileSize

class Server(
    private val configStore: ConfigStore,
    private val feedMirror: FeedMirror,
    private val episodeStore: EpisodeStore,
) {
    private val log = LoggerFactory.getLogger(Server::class.java)

    fun start(port: Int): Javalin {
        val app = Javalin.create()

        // Podcatcher-facing routes; the per-user key is part of the path.
        app.get("/feed/{key}/{podcastId}", ::handleFeed)
        app.get("/media/{key}/{podcastId}/{episodeFile}", ::handleMedia)

        // Management API. The server runs on a private network, so these are
        // open; only the podcatcher-facing routes require a key.
        app.get("/api/podcasts", ::handleListPodcasts)
        app.get("/api/podcasts/{podcastId}/episodes", ::handleListEpisodes)
        app.post("/api/archive/{podcastId}/{episodeId}", ::handleArchive)
        app.post("/api/reload", ::handleReload)

        app.exception(Exception::class.java) { e, ctx ->
            log.error("Request failed: {} {}", ctx.method(), ctx.path(), e)
            ctx.status(500).json(mapOf("error" to (e.message ?: e.javaClass.simpleName)))
        }

        return app.start(port)
    }

    // ---- podcatcher-facing ------------------------------------------------

    private fun handleFeed(ctx: Context) {
        val user = requireUser(ctx)
        val podcast = requirePodcast(ctx)
        val xml = feedMirror.mirroredFeed(podcast, ctx.pathParam("key"), baseUrl(ctx))
        log.info("[{}] served feed to user '{}'", podcast.id, user)
        ctx.contentType("application/rss+xml; charset=utf-8").result(xml)
    }

    private fun handleMedia(ctx: Context) {
        val user = requireUser(ctx)
        val podcast = requirePodcast(ctx)
        // The URL ends in e.g. "3fa8b2c19e04d7aa.mp3"; the extension is only
        // there to keep podcatchers happy.
        val episodeId = ctx.pathParam("episodeFile").substringBefore('.')

        var entry = episodeStore.indexEntry(podcast.id, episodeId)
        if (entry == null) {
            // Unknown id, e.g. the index file was deleted. Rebuild it from the
            // origin feed and try once more.
            feedMirror.refreshIndex(podcast)
            entry = episodeStore.indexEntry(podcast.id, episodeId)
        }
        if (entry == null) {
            log.warn("[{}] no episode with id {} in index", podcast.id, episodeId)
            throw NotFoundResponse("Unknown episode: $episodeId")
        }

        val episode = episodeStore.getOrDownload(podcast.id, episodeId, entry)
        log.info(
            "[{}] serving episode {} ('{}') to user '{}' from {}",
            podcast.id, episodeId, episode.meta.title ?: "?", user,
            if (episode.wasAlreadyCached) "cache" else "fresh download"
        )
        ctx.writeSeekableStream(
            FileInputStream(episode.mediaFile.toFile()),
            episode.meta.contentType,
            episode.mediaFile.fileSize(),
        )
    }

    // ---- management API ---------------------------------------------------

    private fun handleListPodcasts(ctx: Context) {
        // Pass ?key=<your-key> to get ready-to-paste feed URLs for that user.
        val key = ctx.queryParam("key")?.takeIf { configStore.userForKey(it) != null }
        val base = baseUrl(ctx)

        ctx.json(configStore.config.podcasts.map { podcast ->
            val stats = episodeStore.cacheStats(podcast.id)
            mapOf(
                "id" to podcast.id,
                "title" to (podcast.title ?: podcast.id),
                "originalRssUrl" to podcast.url,
                "mirroredRssUrl" to key?.let { "$base/feed/$it/${podcast.id}" },
                "cachedEpisodes" to stats.episodeCount,
                "cachedBytes" to stats.totalBytes,
            )
        })
    }

    private fun handleListEpisodes(ctx: Context) {
        val podcast = requirePodcast(ctx)
        ctx.json(episodeStore.readIndex(podcast.id).map { (episodeId, entry) ->
            mapOf(
                "episodeId" to episodeId,
                "title" to entry.title,
                "originalUrl" to entry.url,
                "cached" to episodeStore.isCached(podcast.id, episodeId),
            )
        })
    }

    private fun handleArchive(ctx: Context) {
        val podcast = requirePodcast(ctx)
        val episodeId = ctx.pathParam("episodeId")
        val archivedTo = episodeStore.archive(podcast.id, episodeId)
        ctx.json(
            mapOf(
                "podcastId" to podcast.id,
                "episodeId" to episodeId,
                "archivedTo" to archivedTo.toString(),
                "note" to "Next client download will re-fetch from the original source.",
            )
        )
    }

    private fun handleReload(ctx: Context) {
        configStore.load()
        ctx.json(
            mapOf(
                "podcasts" to configStore.config.podcasts.size,
                "users" to configStore.config.users.size,
            )
        )
    }

    // ---- helpers ----------------------------------------------------------

    private fun requireUser(ctx: Context): String {
        val key = ctx.pathParam("key")
        return configStore.userForKey(key) ?: run {
            log.warn("Rejected request with unknown key '{}' from {}", key, ctx.ip())
            throw UnauthorizedResponse("Unknown key")
        }
    }

    private fun requirePodcast(ctx: Context): PodcastConfig {
        val id = ctx.pathParam("podcastId")
        return configStore.podcast(id)
            ?: throw NotFoundResponse("No podcast with id '$id' in config")
    }

    private fun baseUrl(ctx: Context): String =
        configStore.config.baseUrl?.trimEnd('/') ?: "${ctx.scheme()}://${ctx.host()}"
}
