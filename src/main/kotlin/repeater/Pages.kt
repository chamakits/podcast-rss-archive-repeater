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

    fun podcastList(rows: List<PodcastRow>, userNames: List<String>, selectedUser: String?): String {
        val userLinks = userNames.joinToString(" ") { name ->
            val marker = if (name == selectedUser) " class=\"selected\"" else ""
            "<a$marker href=\"/?user=${esc(name)}\">${esc(name)}</a>"
        }

        val intro = if (selectedUser == null) {
            "<p>Pick a user to get feed links you can paste into a podcatcher:</p>"
        } else {
            "<p>Feed links for <strong>${esc(selectedUser)}</strong> — paste one into your podcatcher:</p>"
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
            <p class="users">Users: $userLinks</p>
            $intro
            $cards
            <p class="footer"><a href="/endpoints">All REST endpoints of this service</a></p>
            """.trimIndent()
        )
    }

    fun endpoints(): String {
        val rows = listOf(
            Triple("GET", "/", "This UI. Add ?user=&lt;name&gt; for that user's feed links."),
            Triple("GET", "/endpoints", "This page."),
            Triple("GET", "/feed/{key}/{podcastId}", "Mirrored RSS feed. Subscribe to this in a podcatcher."),
            Triple("GET", "/media/{key}/{podcastId}/{episodeId}.mp3", "Episode audio: served from the local cache, downloaded from the original source on first request."),
            Triple("GET", "/api/podcasts", "JSON list of podcasts with cache stats. Add ?key=&lt;key&gt; to include mirrored feed URLs."),
            Triple("GET", "/api/podcasts/{podcastId}/episodes", "JSON list of known episodes with their episodeId and cached state."),
            Triple("POST", "/api/archive/{podcastId}/{episodeId}", "Move the cached copy to the archive directory; next download re-fetches from the original source."),
            Triple("POST", "/api/reload", "Reload config.yaml without restarting (new podcasts, new users &rarr; new keys)."),
        ).joinToString("\n") { (method, path, description) ->
            "<tr><td><code>$method</code></td><td><code>$path</code></td><td>$description</td></tr>"
        }

        return page(
            "Endpoints",
            """
            <h1>REST endpoints</h1>
            <table>
              <tr><th>Method</th><th>Path</th><th>Description</th></tr>
              $rows
            </table>
            <p class="footer"><a href="/">Back to podcasts</a></p>
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
            .users a { margin-right: 0.6rem; }
            .users a.selected { font-weight: bold; text-decoration: none; }
            table { border-collapse: collapse; width: 100%; }
            th, td { text-align: left; padding: 0.4rem 0.6rem; border-bottom: 1px solid #ddd; vertical-align: top; }
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
