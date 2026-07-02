# Rules for Claude Code in this repo

## Scope: do what was asked, ask before doing more

- Do exactly what the request says, at the size it says. If fulfilling it
  would require anything beyond its literal scope — especially **new code to
  satisfy a "configure X" / "set up X" / "add an entry" request** — STOP and
  say so: explain the gap, propose the change, and wait for approval before
  writing code.
- A request to investigate, explain, or recommend is not a request to
  implement. Report findings and stop.
- If a request seems to imply a missing feature, that is a question for the
  user, not an implementation detail to resolve silently.

## Never download binaries or install tools

- If a tool is missing (ffmpeg, imagemagick, anything), do not download,
  build, or install it — not even to a temp/scratch directory. Ask the user
  to install it and wait.

## Never touch processes Claude didn't start

- The user runs their own instance of this app (typically port 8080, data
  dir `~/apps/data/podcast-rss-archive-repeater`). Never kill, restart, or
  send reload requests to it without being asked.
- For testing, run a separate instance on a free port with a scratch data
  directory, and kill only the processes started in this session.

## Live config is production

- `~/apps/data/podcast-rss-archive-repeater/config.yaml` configures the
  running instance. Only edit it when explicitly asked, make the smallest
  edit that satisfies the request, and leave applying it (reload/restart)
  to the user unless told otherwise.

## Code style

- Kotlin, simple and readable, minimal dependencies, file-based state (no
  database), useful logging. Match the existing code's comment density and
  structure.
- Don't commit or push unless asked.
