package repeater

import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Fetches the original RSS feed and rewrites every episode enclosure URL to
 * point back at this server, so the podcatcher downloads episodes through us.
 */
class FeedMirror(private val http: HttpClient, private val store: EpisodeStore) {
    private val log = LoggerFactory.getLogger(FeedMirror::class.java)

    /**
     * Returns the feed XML (UTF-8 bytes) with enclosure URLs rewritten to
     * "$baseUrl/media/$key/$podcastId/$episodeId$extension".
     *
     * The feed is handled as bytes throughout: the XML parser honors the
     * encoding declared in the feed itself, and we always serialize back out
     * as UTF-8. Round-tripping through Strings once corrupted non-ASCII
     * characters (e.g. "©") for podcatchers.
     */
    fun mirroredFeed(podcast: PodcastConfig, key: String, baseUrl: String): ByteArray {
        val originalXml = fetchFeed(podcast)
        val document = parseXml(originalXml)

        val index = mutableMapOf<String, IndexEntry>()
        val enclosures = document.getElementsByTagName("enclosure")
        for (i in 0 until enclosures.length) {
            val enclosure = enclosures.item(i) as Element
            val originalUrl = enclosure.getAttribute("url")
            if (originalUrl.isBlank()) continue

            val episodeId = EpisodeStore.episodeId(originalUrl)
            index[episodeId] = IndexEntry(url = originalUrl, title = itemTitle(enclosure))
            enclosure.setAttribute(
                "url",
                "$baseUrl/media/$key/${podcast.id}/$episodeId${fileExtension(originalUrl)}"
            )
        }

        store.updateIndex(podcast.id, index)
        log.info("[{}] mirrored feed with {} episode(s)", podcast.id, index.size)
        return serializeXml(document)
    }

    /**
     * Re-fetches the feed just to refresh the episode index. Used when a media
     * request arrives for an episode id we don't know yet (e.g. after the
     * index file was deleted). Failures are logged and swallowed.
     */
    fun refreshIndex(podcast: PodcastConfig) {
        try {
            val document = parseXml(fetchFeed(podcast))
            val index = mutableMapOf<String, IndexEntry>()
            val enclosures = document.getElementsByTagName("enclosure")
            for (i in 0 until enclosures.length) {
                val enclosure = enclosures.item(i) as Element
                val originalUrl = enclosure.getAttribute("url")
                if (originalUrl.isBlank()) continue
                index[EpisodeStore.episodeId(originalUrl)] = IndexEntry(originalUrl, itemTitle(enclosure))
            }
            store.updateIndex(podcast.id, index)
        } catch (e: Exception) {
            log.warn("[{}] could not refresh episode index: {}", podcast.id, e.message)
        }
    }

    /**
     * Fetches the original feed, keeping a local copy. If the origin is down,
     * falls back to the last copy we saved so podcatchers keep working.
     */
    private fun fetchFeed(podcast: PodcastConfig): ByteArray {
        try {
            val request = HttpRequest.newBuilder(URI.create(podcast.url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() !in 200..299) {
                error("HTTP ${response.statusCode()}")
            }
            store.saveFeedCopy(podcast.id, response.body())
            return response.body()
        } catch (e: Exception) {
            val fallback = store.cachedFeedCopy(podcast.id)
                ?: throw IllegalStateException("Feed fetch failed for ${podcast.url} and no local copy exists", e)
            log.warn("[{}] feed fetch failed ({}), serving last saved copy", podcast.id, e.message)
            return fallback
        }
    }

    /** The title of the <item> this enclosure belongs to, if present. */
    private fun itemTitle(enclosure: Element): String? {
        val item = enclosure.parentNode as? Element ?: return null
        val titles = item.getElementsByTagName("title")
        return if (titles.length > 0) titles.item(0).textContent.trim() else null
    }

    /** ".mp3" from "https://host/ep/42/audio.mp3?x=1", or "" if there is none. */
    private fun fileExtension(url: String): String {
        val fileName = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        return if (extension.isNotEmpty() && extension.length <= 4 && extension.all { it.isLetterOrDigit() }) {
            ".$extension"
        } else ""
    }

    private fun parseXml(xml: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Feeds are untrusted input; don't resolve external entities.
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    }

    private fun serializeXml(document: Document): ByteArray {
        val out = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }.transform(DOMSource(document), StreamResult(out))
        return out.toByteArray()
    }
}
