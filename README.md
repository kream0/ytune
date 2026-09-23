# YTune

A sideloadable Android music player for YouTube, styled after Nothing OS (dot-matrix type, black and white, one red accent).

- **Search** YouTube or YouTube Music: songs, videos, playlists, albums. Search suggestions work as you type. You can also paste a link, or share one from the YouTube app to YTune.
- **Add whole playlists** straight from the search results with the `+` button, or open one to play, shuffle, queue, download or bookmark it.
- **Stream, or stream + download.** The `STREAM / STREAM + DL` toggle sits in the header and on the player.
  - **Progressive:** downloads the current track and the next *N* in the queue as you listen (default look-ahead is 2).
  - **All at once:** downloads everything you add to the queue, such as a full playlist, right away.
  - An explicit **Download** on a track or playlist always works, whichever mode is on.
- **Offline library:** downloaded songs, saved playlists and a download queue with progress. Once a track is on disk it always plays from the local file.
- **Player:** queue, shuffle, repeat, seek, a dot-matrix seek bar and "halftone" dot artwork. Tap the cover to switch to the photo.
- **Headset and Nothing Ear controls:** playback runs in a Media3 `MediaSession`, so it works from Bluetooth media keys (play/pause, next, previous), the lock screen and the notification. It pauses when the earbuds disconnect or come out, and pressing play with the app closed resumes your last queue.

## Install

Every push builds a signed APK and publishes it as the rolling **`latest`** release:

**https://github.com/kream0/ytune/releases/download/latest/ytune.apk**

Open that link on your phone, allow "install unknown apps" for your browser, then install. Later builds install over the top. Requires Android 8.0 or newer.

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
3. Push. CI rebuilds and republishes `latest`.

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
