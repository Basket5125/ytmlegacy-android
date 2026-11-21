# YTM Legacy (Android)

A lightweight YouTube Music client for **Android 2.3 and newer**, styled after the Holo look
of the Android 4 era. It needs no account and has no ads or tracking. Metadata comes from
InnerTube (WEB_REMIX). Audio comes straight from YouTube's servers (googlevideo), with no
Invidious instance or other middleman.

## Features

- Tabs: **Home / Explore / Library / Settings**.
- Search button in the top-right corner, with suggestions, search history and filters (songs,
  videos, albums, artists, playlists).
- Pages for albums, playlists (paged), artists, charts, moods and genres.
- **Now Playing** screen:
  - shows the cover, title, artist and a progress bar;
  - has controls for shuffle, repeat (all or one), volume and the queue (top-right corner);
  - has buttons for lyrics, a sleep timer, liking and downloading;
  - closes with the triangle button in the top-left corner, by dragging down or with Back.
- **Endless radio.** YouTube's own radio continuation starts repeating itself after a few
  pages. The app remembers every song it queued. When a page brings too few new songs, it
  starts the radio again from a random recently played song.
- **Background playback** with a notification:
  - Android 2.3: a plain notification.
  - Android 3.0–4.0: a notification with buttons.
  - Android 4.0–4.4: lock-screen controls with cover art (RemoteControlClient).
  - Android 5+: MediaSession with a MediaStyle notification (the system media controls).
  - Headset and Bluetooth buttons work on all versions.
- **Hardware Menu key**: opens the classic options panel. The entries depend on the screen
  (search, Now Playing, refresh, playlist and album options, new playlist, CSV import, sleep
  timer, equalizer, settings, exit). In Now Playing it opens the song menu instead.
- **Audio quality**: Low (AAC 48 kbps), Normal (AAC 128 kbps), High (Opus 160 kbps, Android
  8+ only). Mobile data and downloads can each have their own quality.
- **Cache** (optional): played songs and the next songs in the queue are kept on disk, so they
  start at once. Old files are removed when the size limit is reached.
- **Offline downloads** of single songs, albums and playlists, with their cover art.
- **CSV import and export of playlists.** Supported sources:
  - Spotify exports (Exportify);
  - Google Takeout (YouTube);
  - plain lists of links or video IDs.
  Rows without a video ID are matched by title, artist and length.
- **Audio settings**:
  - crossfade with an adjustable length (1–12 s);
  - equalizer with presets and bands;
  - bass boost and virtualizer;
  - volume normalisation based on YouTube's loudness data.
- **Look and language**:
  - dark and light themes; following the system theme needs Android 10+;
  - app language: phone default, English or Polish.
- Options that need a newer Android are greyed out and say which version they need.

## How audio plays

1. The app asks the InnerTube `player` endpoint for a googlevideo audio URL. It uses the
   VISIONOS client with visitorData first, and falls back to the ANDROID client (itag 18).
2. googlevideo throttles plain GET requests. The app therefore downloads the file in chunks
   (with Range headers) into its cache. MediaPlayer reads it through a small local server on
   `127.0.0.1`.
3. On Android 4.4+ MediaPlayer plays the DASH MP4 directly.
4. Android 2.3–4.3 cannot read fragmented MP4. There the file is repacked into a classic MP4.
   The audio samples are left unchanged; only the container changes.
5. If a device's player refuses DASH MP4, the app notices and switches to repacking by itself.

## TLS on old Android

- Android 4.0–4.4: TLS 1.2 through Conscrypt.
- Android 2.3: Conscrypt aborts natively there, so the system TLS is used.
- Android up to 7.1: a few current root certificates are bundled
  (`res/raw/extra_roots.pem`).
- Certificate checks are never turned off.

## Building

```
JAVA_HOME=<JDK 17> ./gradlew assembleRelease
```

- The APK is written to `app/build/outputs/apk/release/app-release.apk`.
- The release build is signed with the local debug key. Replace it with your own key before
  publishing.
- Unit tests: `./gradlew testDebugUnitTest`. They cover the parsers (against recorded InnerTube
  answers), CSV handling and the play queue.

## Tested on

- Emulators: Android 2.3.7, 4.1, 4.4 and 13.
- Phone: Samsung Galaxy S4 (Android 4.4.2).

If YouTube changes its client version, set the new one under
Settings → Advanced → "WEB_REMIX client version".
