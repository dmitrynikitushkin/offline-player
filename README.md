# Offline Player

A personal Android app: paste or share a YouTube link, download it as **MP4 video** or **M4A audio**, then watch or listen offline. No account, ads, recommendations, analytics or server.

## Why no server or Mac companion is needed

The download engine is [yt-dlp](https://github.com/yt-dlp/yt-dlp), bundled into the app by the open-source **youtubedl-android** library (it ships a small Python runtime and ffmpeg for merging video + audio). Everything runs on the phone and files are saved straight to it. yt-dlp can update itself in place from **Library ▸ ⋮ ▸ Update downloader**, so when YouTube changes something you normally don't need a new app build.

Trade-off: the APK is large (~70–100 MB per ABI). The first launch takes a few seconds to unpack the engine.

## Build and install

1. Open the `OfflinePlayer` folder in **Android Studio** (Narwhal or newer). Let it sync. If it offers to upgrade Gradle, AGP or library versions, accept.
2. Plug in the phone with USB debugging on, choose the **app** configuration and press **Run**.
   For a standalone APK: **Build ▸ Generate App Bundles or APKs ▸ Build APK(s)**, then copy `app/build/outputs/apk/debug/app-debug.apk` to the phone and open it.
3. Allow notifications when asked (needed for download progress and lock-screen controls).

Requires Android 8.0 (API 26) or newer.

> This project was written without access to an Android SDK, so it has **not been compiled yet**. Expect a few small fixes on first build. The likeliest spots are noted in *Known risks* below.

## How to use

- **Paste link** (always visible) → the app reads the clipboard, checks the link and shows the title, thumbnail and qualities with estimated sizes. Pick **Video (MP4)** + quality, or **Audio only (M4A)**, then **Download**.
- Or in YouTube: **Share ▸ Offline Player**.
- **Downloads** shows the queue (one at a time, in order) with progress, **Cancel**, **Retry** and **Remove**. Failed items show the reason.
- **Library** shows finished items with thumbnail, duration, size, format, and a bar showing where you stopped. The top shows total storage used. Tap an item to play the whole library from that item. **⋮** gives **Add to queue** and **Delete file…** (with confirmation).
- **Player**: play/pause, seek, previous/next, ±10 s, speed 0.5–2×, **Listen** (hide the picture, keep the audio) and **Full screen**. Below is the queue: tap to jump, ↑/↓ to reorder, ✕ to remove from the queue (the file stays).

## Architecture

```
downloader/   ← the only module that knows about yt-dlp
  Downloader.kt       interface + models (probe, download, cancel, update)
  YtDlpDownloader.kt  yt-dlp implementation, format selection, size estimates
  LinkParser.kt       accepts single YouTube videos, explains anything else
  ErrorMapper.kt      yt-dlp errors → plain-language messages
app/
  data/       Room DB: items (downloads + library + saved position) and the play queue
  download/   DownloadWorker (WorkManager): sequential, survives restarts, foreground notification
  playback/   PlaybackService (Media3 MediaSessionService + ExoPlayer)
  ui/         Compose: Library, Downloads, Player, Paste sheet
```

- **Downloading is separate from playback.** The app only sees the `Downloader` interface, so the engine can be updated (in-app) or swapped (bump the version in `downloader/build.gradle.kts`) without touching the player.
- **Playback lives in a service.** Audio continues with the screen locked or the app in the background. The MediaSession provides lock-screen and notification controls, Bluetooth/headset buttons and audio focus. Playback pauses when headphones disconnect.
- **Queue and positions are persisted.** Every queue change is mirrored to the DB. Positions are saved every 5 s, on pause, and when switching items. Finished items restart from 0. On reopen the service restores the queue, the current item and its position.
- **Files** are in app-private storage (`Android/data/app.offlineplayer/files/media`). No storage permission is needed. Uninstalling the app removes them.

## Limits by design

- Only public, single YouTube videos. Private, members-only, age-gated (sign-in) and DRM-protected videos, live streams and playlists are refused with an explanation. The app never uses cookies or accounts.
- Links, titles and history stay in the on-device database. Nothing is sent anywhere except the requests to YouTube needed to download.
- YouTube's Terms of Service restrict downloading except where YouTube offers it. Use this for content you're allowed to keep offline.

## Acceptance test checklist

| Criterion | How to check |
|---|---|
| Supported link → playable local MP4/M4A | Download one video as MP4 (720p) and one as M4A; both appear in the Library and play. |
| Plays in airplane mode | Turn on airplane mode, force-stop and reopen the app, play both. |
| Audio continues with screen locked | Play the M4A, lock the phone: audio continues and lock-screen controls work. |
| Three queued clips play consecutively | Library ▸ Add to queue ×3 (short clips) ▸ Player ▸ play the first and wait. |
| Restart restores library, queue, positions | Pause mid-clip, swipe the app away, reopen: same queue, same item, same position. |
| Failed downloads explain and can be retried | Paste a private-video or deleted-video link (clear error). Turn off Wi-Fi mid-download (network error) ▸ Retry. |

## Known risks on first build

- **youtubedl-android API**: `execute(request, processId, callback)` uses named arguments. If the library changed parameter names or the callback signature, adjust `YtDlpDownloader.download`. Same for `YoutubeDL.UpdateChannel.STABLE` / `UpdateStatus` in `update()`.
- **Media3 `onAddMediaItems` override types** (`List` vs `MutableList`): adjust if the compiler complains.
- Versions were pinned to known-good mid-2025 releases. Android Studio's suggested upgrades are fine.
