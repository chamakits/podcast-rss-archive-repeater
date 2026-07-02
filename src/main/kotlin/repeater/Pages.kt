package repeater

/**
 * Plain HTML pages, built with string templates. No template engine — these
 * two pages are the whole UI.
 */
object Pages {

    data class PodcastRow(
        val id: String,
        val title: String,
        val originUrl: String,
        val feedUrl: String?, // null when no user is selected
        val cachedEpisodes: Int,
        val cachedBytes: Long,
    )

    fun podcastList(rows: List<PodcastRow>, user: String?, key: String?): String {
        val keyQuery = key?.let { "?key=${esc(it)}" } ?: ""
        val intro = if (user == null) {
            """
            <p>Add <code>?key=&lt;your-key&gt;</code> to the URL of this page to get
            feed links you can paste into a podcatcher.</p>
            """.trimIndent()
        } else {
            "<p>Feed links for <strong>${esc(user)}</strong> — paste one into your podcatcher:</p>"
        }

        val cards = rows.joinToString("\n") { row ->
            val feedLine = row.feedUrl?.let {
                """
                <div class="copyrow">
                  <input readonly value="${esc(it)}" onclick="this.select()">
                  <button onclick="copyLink(this)">Copy</button>
                </div>
                """.trimIndent()
            } ?: ""
            """
            <div class="card">
              <h2>${esc(row.title)}</h2>
              <div class="meta">
                id: <code>${esc(row.id)}</code>
                &middot; ${row.cachedEpisodes} cached episode(s), ${formatBytes(row.cachedBytes)}
                &middot; <a href="${esc(row.originUrl)}">original feed</a>
              </div>
              $feedLine
            </div>
            """.trimIndent()
        }

        return page(
            "Podcasts",
            """
            <h1>Podcasts</h1>
            $intro
            $cards
            <p class="footer"><a href="/endpoints$keyQuery">All REST endpoints of this service</a></p>
            """.trimIndent()
        )
    }

    private data class Endpoint(val method: String, val path: String, val description: String, val tryIt: String)

    fun endpoints(podcastIds: List<String>, key: String?): String {
        val keyQuery = key?.let { "?key=${esc(it)}" } ?: ""

        fun perPodcastLinks(urlFor: (String) -> String): String =
            if (podcastIds.isEmpty()) "<em>no podcasts configured</em>"
            else podcastIds.joinToString(" ") { id -> "<a href=\"${esc(urlFor(id))}\">${esc(id)}</a>" }

        val feedLinks =
            if (key == null) "<em>add ?key=&lt;your-key&gt; to this page to get links</em>"
            else perPodcastLinks { "/feed/$key/$it" }

        val archiveForm = """
            <form onsubmit="return postArchive(this)">
              <select name="podcastId">${podcastIds.joinToString("") { "<option>${esc(it)}</option>" }}</select>
              <input name="episodeId" placeholder="episodeId" required>
              <button>POST</button>
            </form>
        """.trimIndent()

        val rows = listOf(
            Endpoint(
                "GET", "/",
                "HTML list of podcasts. Add ?key=&lt;your-key&gt; to include your feed links.",
                "<a href=\"/$keyQuery\">open</a>"
            ),
            Endpoint(
                "GET", "/endpoints",
                "This page. Also takes ?key=&lt;your-key&gt; to fill in the links below.",
                "<a href=\"/endpoints$keyQuery\">open</a>"
            ),
            Endpoint(
                "GET", "/feed/{key}/{podcastId}",
                "Mirrored RSS feed. Subscribe to this in a podcatcher.",
                feedLinks
            ),
            Endpoint(
                "GET", "/media/{key}/{podcastId}/{episodeId}.mp3",
                "Episode audio: served from the local cache, downloaded from the original source on first request.",
                "<em>links are inside the mirrored feed (opening one downloads the episode)</em>"
            ),
            Endpoint(
                "GET", "/api/podcasts",
                "JSON list of podcasts with cache stats. Add ?key=&lt;key&gt; to include mirrored feed URLs.",
                "<a href=\"/api/podcasts$keyQuery\">open</a>"
            ),
            Endpoint(
                "GET", "/api/podcasts/{podcastId}/episodes",
                "JSON list of known episodes with their episodeId and cached state.",
                perPodcastLinks { "/api/podcasts/$it/episodes" }
            ),
            Endpoint(
                "POST", "/api/archive/{podcastId}/{episodeId}",
                "Move the cached copy to the archive directory; next download re-fetches from the original source. Get the episodeId from the episodes listing above.",
                archiveForm
            ),
            Endpoint(
                "POST", "/api/reload",
                "Reload config.yaml without restarting (new podcasts, new users &rarr; new keys).",
                "<button onclick=\"post('/api/reload')\">POST</button>"
            ),
        ).joinToString("\n") { endpoint ->
            "<tr><td><code>${endpoint.method}</code></td><td><code>${endpoint.path}</code></td>" +
                "<td>${endpoint.description}</td><td class=\"tryit\">${endpoint.tryIt}</td></tr>"
        }

        return page(
            "Endpoints",
            """
            <h1>REST endpoints</h1>
            <table>
              <tr><th>Method</th><th>Path</th><th>Description</th><th>Try it</th></tr>
              $rows
            </table>
            <pre id="result"></pre>
            <p class="footer"><a href="/$keyQuery">Back to podcasts</a></p>
            """.trimIndent()
        )
    }

    private fun page(title: String, body: String): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>$title - podcast-rss-archive-repeater</title>
          <style>
            body { font-family: system-ui, sans-serif; max-width: 46rem; margin: 2rem auto; padding: 0 1rem; color: #222; }
            h1 { font-size: 1.4rem; }
            .card { border: 1px solid #ddd; border-radius: 8px; padding: 0.8rem 1rem; margin: 0.8rem 0; }
            .card h2 { font-size: 1.1rem; margin: 0 0 0.3rem; }
            .meta { color: #666; font-size: 0.85rem; margin-bottom: 0.5rem; }
            .copyrow { display: flex; gap: 0.5rem; }
            .copyrow input { flex: 1; font-family: monospace; font-size: 0.85rem; padding: 0.35rem; }
            .copyrow button { padding: 0.35rem 0.9rem; cursor: pointer; }
            table { border-collapse: collapse; width: 100%; }
            th, td { text-align: left; padding: 0.4rem 0.6rem; border-bottom: 1px solid #ddd; vertical-align: top; }
            .tryit a { margin-right: 0.5rem; white-space: nowrap; }
            .tryit form { display: flex; gap: 0.3rem; flex-wrap: wrap; }
            .tryit input { width: 9rem; }
            .tryit em { color: #666; }
            #result { background: #f6f6f6; padding: 0.6rem; white-space: pre-wrap; word-break: break-all; }
            #result:empty { display: none; }
            .footer { margin-top: 1.5rem; }
          </style>
        </head>
        <body>
        $body
        <script>
          // execCommand instead of navigator.clipboard: the latter needs
          // https, and this runs over plain http on a private network.
          function copyLink(button) {
            const input = button.previousElementSibling;
            input.select();
            document.execCommand('copy');
            button.textContent = 'Copied!';
            setTimeout(() => button.textContent = 'Copy', 1500);
          }
          async function post(url) {
            const response = await fetch(url, { method: 'POST' });
            document.getElementById('result').textContent =
              'POST ' + url + '\n' + response.status + ' ' + await response.text();
          }
          function postArchive(form) {
            post('/api/archive/' + form.podcastId.value + '/' + form.episodeId.value.trim());
            return false;
          }
        </script>
        </body>
        </html>
    """.trimIndent()

    private fun esc(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f kB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}
