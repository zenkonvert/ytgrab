# YTGrab — Android yt-dlp front-end

Single-app, no-server architecture:
- **yt-dlp**: self-updating. On first launch (and daily thereafter) the app downloads the
  latest standalone `yt-dlp_linux_aarch64` / `yt-dlp_linux_armv7l` binary straight from
  yt-dlp's official GitHub releases. This is what keeps the app working when YouTube
  changes something — you never have to push a code update for extractor breakage.
- **ffmpeg/ffprobe**: bundled into the APK at build time (see setup step below) since
  ffmpeg's CLI is stable and doesn't need runtime updates. Bundling avoids depending on
  any third-party binary host staying online forever.
- **Storage**: files save to public `Music/YTGrab` (audio) and `Movies/YTGrab` (video),
  visible in any file manager, and immediately indexed via `MediaScanner`.
- **Min/target SDK**: 24 (Android 7.0) through 35 (latest).

## One-time setup you need to do: add the ffmpeg binaries

Because ffmpeg is bundled (not downloaded at runtime), you need to drop prebuilt
Android ffmpeg/ffprobe binaries into the project once, before your first build.

1. Get Android-NDK-built (Bionic libc) arm64-v8a + armeabi-v7a `ffmpeg` and `ffprobe`
   executables. Good sources (check current releases, links change over time):
   - https://github.com/hzw1199/Android-FFmpeg-Prebuilt (arm64-v8a, ready to use)
   - https://github.com/husen-hn/ffmpeg-android-binary (multi-ABI)
   - Or compile your own via https://github.com/guardianproject/android-ffmpeg for full control.

   **Important**: do NOT use `shaka-project/static-ffmpeg-binaries` — those are built for
   generic glibc Linux, not Android's Bionic libc, and will fail to run on-device.

2. Android requires native binaries shipped via `jniLibs` to be named `lib*.so`. Rename
   the executables accordingly and place them here:

   ```
   app/src/main/jniLibs/arm64-v8a/libffmpeg_bin.so   (the ffmpeg binary, renamed)
   app/src/main/jniLibs/arm64-v8a/libffprobe_bin.so  (the ffprobe binary, renamed)
   app/src/main/jniLibs/armeabi-v7a/libffmpeg_bin.so
   app/src/main/jniLibs/armeabi-v7a/libffprobe_bin.so
   ```

3. That's it — `BinaryManager.kt` copies these from the app's native lib directory into
   app-private storage as plain `ffmpeg` / `ffprobe` files at first launch, and yt-dlp is
   told to use them via `--ffmpeg-location`.

## Building

```
./gradlew assembleRelease
```

Output APK: `app/build/outputs/apk/release/app-release.apk`

This APK is **not Play Store compliant** (yt-dlp bundling/download violates Play policy) —
distribute via direct APK sideload, which fits a rooted/custom-ROM device fine.

## Project layout

```
core/BinaryManager.kt    - bundled ffmpeg + self-updating yt-dlp binary handling
core/DownloadEngine.kt   - builds yt-dlp args, parses progress, emits Flow<DownloadEvent>
core/DownloadService.kt  - foreground service so long downloads survive backgrounding
core/MediaScanner.kt     - makes downloaded files show up instantly in file managers
MainActivity.kt          - Compose UI: Audio/Video toggle, link field, format/quality picker
```

## Notes / things to sanity-check on a real device before relying on this

- Test on an actual Android 7 device/emulator if you have one — `armeabi-v7a` ffmpeg
  binaries are less commonly maintained than arm64, so verify that path works.
- yt-dlp occasionally needs `--extractor-args` tweaks for certain sites; the current
  setup handles plain YouTube links well. If you hit "Sign in to confirm you're not a
  bot" errors, that's YouTube's bot-detection — usually resolved by a newer yt-dlp
  release (which the auto-updater will pick up) or by adding cookie support later.
- The GitHub API for update checks is unauthenticated and rate-limited to 60 requests/hour
  per IP — fine for daily-once checks per user, but be aware if testing repeatedly.
