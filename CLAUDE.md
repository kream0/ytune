# YTune — notes for Claude

## Working with the owner
- The owner does not do manual steps (GitHub UI, git commands, CI buttons). Do everything
  yourself; if an action truly needs their authorization, send them the exact link
  (GitHub access: https://claude.ai/connect-github) instead of instructions.
- They install and update from their phone: the in-app updater follows published releases.

## Releasing
- Bump `VERSION` (e.g. `1.0.0` → `1.0.1`) and push to the working branch. CI tags that
  commit `vX.Y.Z` and publishes the GitHub release with `ytune.apk` + `version.json`.
- Plain pushes only build a test APK (Actions artifact); the app never offers those.
- Release when a user-facing change is ready and they asked for it (e.g. "release it",
  "ship it"), and say which version went out.

## Building
- This sandbox can't reach Google's Maven/SDK hosts, so Android builds only run in GitHub
  Actions (`.github/workflows/build.yml`). Push, then read the run with the GitHub MCP tools;
  the "Show compiler errors" step prints Kotlin errors compactly.
- The Glyph Matrix SDK AAR is downloaded at build time (pinned + SHA-256) because its licence
  forbids redistribution: never commit `app/libs/`.
- Pure-Kotlin parts (e.g. `glyph/MatrixRenderer.kt`, `glyph/DotFont.kt`) can be compiled on the
  JVM locally for quick checks; use the Maven Central mirror
  `https://maven-central.storage-download.googleapis.com/maven2` (Central rate-limits here).
