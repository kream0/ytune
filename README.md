# YTune

A sideloadable Android music player for YouTube, styled after Nothing OS (dot-matrix type, black and white, one red accent).

- **Search** YouTube or YouTube Music: songs, videos, playlists, albums. Search suggestions work as you type. You can also paste a link, or share one from the YouTube app to YTune.
- **Add whole playlists** straight from the search results with the `+` button, or open one to play, shuffle, queue, download or bookmark it.
- **Stream, or stream + download.** The `STREAM / STREAM + DL` toggle sits in the header and on the player.
  - **Progressive:** downloads the current track and the next *N* in the queue as you listen (default look-ahead is 2).
  - **All at once:** downloads everything you add to the queue, such as a full playlist, right away.
  - An explicit **Download** on a track or playlist always works, whichever mode is on.
- **Offline library:** downloaded songs, saved playlists and a download queue with progress. Once a track is on disk it always plays from the local file.
- **Offline at a glance:** one colour scale everywhere, red (not saved) → orange → yellow → green (saved on the phone):
  - On the seek bar, the dots ahead of the playhead fill with that colour as the track downloads, and the whole bar turns green once it's saved. Grey means stream-only playback buffer.
  - The player shows a chip: `OFFLINE`, `SAVING 42%`, `QUEUED`, `WAITING FOR WI-FI`, `FAILED · RETRY` or `STREAMING · SAVE`. Tap it to save or retry.
  - List rows get a matching ring, or a green dot when the track is saved.
- **Player:** queue, shuffle, repeat, seek, a dot-matrix seek bar and "halftone" dot artwork. Tap the cover to switch to the photo.
- **Headset and Nothing Ear controls:** playback runs in a Media3 `MediaSession`, so it works from Bluetooth media keys (play/pause, next, previous), the lock screen and the notification. It pauses when the earbuds disconnect or come out, and pressing play with the app closed resumes your last queue.

## Glyph Matrix (Nothing Phone (3) / (4a) Pro)

- **While music plays**, the back of the phone scrolls *title · artist* in a dot-matrix font (the same Doto dots as the app), with a progress bar underneath (and a small equalizer on the Phone (3)'s bigger matrix). It clears when you pause. Toggle it in *Settings → Glyph Matrix*, which also has a live on-screen preview.
- **Phone (4a) Pro:** third-party toys only exist as always-on toys there. To keep the title on the back with the phone face down, choose *Settings → Glyph Interface → Flip to Glyph → Always-on Glyph Toy → YTune · Now playing*. Nothing else needs setting up.
- **Phone (3):** in *Settings → Glyph Matrix → Add*, add "YTune · Now playing" to the Glyph Button carousel. Select it with a short press on the Glyph Button; **long-press to play / pause**. Toys outrank app content on the matrix, so use the toy if something else keeps taking over the display.
- **Test + status:** *Settings → Glyph Matrix → Test* scrolls a test message for 8 seconds. The lines under it show whether Nothing's Glyph service was found, connected and accepted YTune, and how many frames went out. If the matrix stays dark, that's where the reason shows up.
- Non-Latin titles (CJK, Cyrillic, …) fall back to the system font squeezed onto the matrix; emoji are skipped.
- The app targets Android 16 because Nothing's Glyph service only waives its API key for apps that do. Nothing's Glyph SDK is closed source and can't be redistributed, so it isn't in this repo: the build downloads it from [Nothing's Glyph-Developer-Kit](https://github.com/Nothing-Developer-Programme/Glyph-Developer-Kit) at a pinned commit and checks its SHA-256.

## Updates

The app follows **published releases** (not every push). When it opens (at most every 3 hours), it looks at the repo's latest release. If that version is newer, it downloads the APK in the background, checks the SHA-256 against the `version.json` published with the release, and asks whether to install. Android then shows its own confirmation. The first time, it asks you to let YTune install apps. Pre-releases are ignored, so you can use them for test builds. You can also check manually in *Settings → Updates*, or turn off auto-download.

## Install

**https://github.com/kream0/ytune/releases/latest/download/ytune.apk**

Open that link on your phone, allow "install unknown apps" for your browser, then install. After that the app updates itself from new releases. Requires Android 8.0 or newer.

## Releasing

Use any of these; each builds the APK and attaches `ytune.apk` + `version.json` to the release:

- **Bump `VERSION`:** set the file to e.g. `1.1.0` and push. If `v1.1.0` isn't released yet, CI tags that commit and publishes the release.
- **GitHub UI:** *Releases → Draft a new release*, create a tag like `v1.2.0`, then publish.
- **Git:** `git tag v1.2.0 && git push origin v1.2.0`. The release is created for you, with generated notes.
- **Actions tab:** run the *Build APK* workflow by hand and enter `1.2.0`.

Tags must look like `vMAJOR.MINOR.PATCH`; the Android versionCode is derived from it (`v1.2.3` → `1002003`). Pushes to branches only build a test APK, which you can download from the workflow run's artifacts. It installs as `0.dev.<run>`, and any release updates it.

## How it works

| Piece | Library |
| --- | --- |
| YouTube search, playlists, stream URLs | [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (the engine behind NewPipe) |
| Playback, media session, notification | AndroidX Media3 / ExoPlayer |
| Downloads | Ranged HTTP in 2 MB chunks (like yt-dlp's chunked mode), resumable |
| UI | Jetpack Compose, fonts Doto, Space Grotesk and Space Mono (OFL) |

Queue items are only `ytune://track/<videoId>` placeholders. The real audio URL is extracted just before a track plays, or the local file is used if it's downloaded. That keeps huge queues cheap and avoids expired URLs. A 512 MB stream cache makes replays instant.

Why not yt-dlp? yt-dlp is Python and now needs an external JavaScript runtime for YouTube, which is heavy on a phone. NewPipeExtractor is pure Java, handles YouTube's signature and throttling challenges itself, and is maintained against YouTube changes by the NewPipe team.

### When YouTube breaks something

YouTube changes things regularly. When search or playback stops working:

1. Check [NewPipeExtractor commits/releases](https://github.com/TeamNewPipe/NewPipeExtractor/commits/dev) for a fix.
2. Bump `newpipeExtractor` in `gradle/libs.versions.toml` to the new tag or commit hash.
3. Push, then publish a new release (see *Releasing*).

## Signing

Without any configuration, CI signs builds with the **public dev key** in `keystore/ytune-dev.jks`. That keeps updates installable, but anyone could sign an APK with it. To use a private key, add these repository secrets:

```
keytool -genkeypair -keystore ytune.jks -alias ytune -keyalg RSA -keysize 2048 -validity 36500
base64 -w0 ytune.jks   # → KEYSTORE_BASE64
```

`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Android refuses to update across signing keys, so uninstall the dev-key build once when you switch.

## Build locally

JDK 17 plus the Android SDK (compileSdk 35):

```
./gradlew assembleRelease     # → app/build/outputs/apk/release/app-release.apk
```

## Note

For personal use. Respect YouTube's terms and the rights of the artists whose music you download.
