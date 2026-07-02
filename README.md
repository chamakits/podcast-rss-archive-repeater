# podcast-rss-archive-repeater

Mirrors podcast RSS feeds through your own server. The first time a
podcatcher downloads an episode through the mirror, the server keeps a local
copy; every later download is served from that copy instead of the original
source. All state is plain files — no database.

## Run

```sh
./gradlew installDist
build/install/podcast-rss-archive-repeater/bin/podcast-rss-archive-repeater /path/to/data-dir
```

On first start with an empty data directory, the server writes a commented
`config.yaml` template into it and exits. Edit it, then start again.

## Configuration (`<data-dir>/config.yaml`)

```yaml
port: 8080

# Optional: pin the host used in mirrored feed links. Defaults to the Host
# header of each incoming request.
# baseUrl: http://192.168.1.10:8080

podcasts:
  - id: my-show                        # short unique id, used in URLs
    url: https://example.com/feed.xml  # the original RSS feed
    title: My Favorite Show            # optional display title

users:
  - name: alice
```

Apply edits without restarting: `curl -X POST localhost:8080/api/reload`

### User keys

For every user in the config the server generates a key into
`<data-dir>/generated/user-keys.yaml` (also logged on generation). The key is
part of every podcatcher-facing URL. To revoke/rotate a key, delete its line
from that file and reload. Removing a user from `config.yaml` disables their
key immediately on reload.

## URLs for your podcatcher

```
http://<host>:<port>/feed/<key>/<podcast-id>
```

Subscribe to that in your podcatcher. Episode enclosure links inside the feed
are rewritten to `/media/<key>/<podcast-id>/<episode-id>.mp3` automatically.

Podcast artwork is rewritten to `/logo/<key>/<podcast-id>/<image-id>.png`,
which serves the original art stamped with a blue border and a "MIRROR" band —
so the mirror is easy to tell apart when it sits next to the original podcast
in your podcatcher. Undecodable formats (e.g. webp) are served unstamped.

## Web UI (no key required — private network)

| Method | Path | What it does |
|---|---|---|
| GET | `/` | HTML list of podcasts. Add `?key=<your-key>` to get copy-paste feed URLs for a podcatcher. |
| GET | `/endpoints` | HTML reference of every endpoint with clickable "try it" links. Also takes `?key=` to fill in feed links. |

## Management API (no key required — private network)

| Method | Path | What it does |
|---|---|---|
| GET | `/api/podcasts` | List podcasts with origin URL and cache stats. Add `?key=<your-key>` to include ready-to-paste mirrored feed URLs. |
| GET | `/api/podcasts/{id}/episodes` | List known episodes with their `episodeId` and whether they are cached. |
| POST | `/api/archive/{id}/{episodeId}` | Move the cached copy to `<data-dir>/archive/<id>/<timestamp>-<episodeId>.media`. The next client download re-fetches from the original source. |
| POST | `/api/reload` | Reload `config.yaml` (new podcasts, new users → new keys). |

## Data directory layout

```
config.yaml                      hand-edited configuration
generated/user-keys.yaml         generated per-user keys
cache/<podcast-id>/
  feed.xml                       last successfully fetched original feed
  episodes.json                  episodeId -> original URL + title
  <episodeId>.media              cached episode audio
  <episodeId>.meta.json          origin URL, content type, size, download time
  images.json                    imageId -> original artwork URL
  art-<imageId>.img              cached stamped artwork
  art-<imageId>.meta.json        origin URL + content type
archive/<podcast-id>/            archived copies (timestamped)
```

Episode ids are a stable hash of the original enclosure URL, so restarts and
feed refetches never change them.

## Behavior when the origin is down

- Feed requests fall back to the last saved copy of the original feed.
- Cached episodes keep serving normally.
- Uncached episodes fail with a 500 and a logged error.

## Code map

| File | Purpose |
|---|---|
| `Main.kt` | Startup, wiring, config template generation |
| `Config.kt` | `config.yaml` loading + user key generation |
| `FeedMirror.kt` | Fetch origin feed, rewrite enclosure URLs |
| `EpisodeStore.kt` | Download, cache, archive episodes (all file-based) |
| `ArtworkStore.kt` | Download and cache artwork, stamp it with the MIRROR overlay |
| `Server.kt` | HTTP routes (Javalin) |
