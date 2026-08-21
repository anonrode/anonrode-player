# ANONRODE PLAYER

Native Android video player. Kotlin + Jetpack Compose + Media3 (ExoPlayer) + FFmpeg (nextlib).

Plays everything (mkv/avi/hevc/ac3/dts), auto-matches and auto-syncs subtitles, auto-groups
downloaded series folders, remembers resume position + per-episode track/speed/offset state.

## Status

Engine-first build. The UI in this repo is functional-but-minimal — it is intended to be
redesigned separately.

## Modules

- `:app` — application entry, activities, minimal UI
- `:core:model` — data models (video, series, subtitle cues, playback state)
- `:core:database` — Room: per-video playback state (`media_state`)
- `:core:datastore` — global player settings (JSON DataStore)
- `:core:media` — the engine: MediaStore scanning, folder→series auto-grouping,
  subtitle parsers (SRT/VTT/ASS), subtitle matcher, live auto-sync (PCM analysis),
  media-state persistence
- `:core:ui` — theme (dark, anonrode brand)
- `:feature:player` — MediaSessionService (foreground playback), decoder setup,
  resume restore/save, subtitle offset application
- `:feature:library` — library + series detail view models

## Engine highlights

- **Decoders**: Media3 + `nextlib-media3ext` (FFmpeg audio/video). `setEnableDecoderFallback(true)`
  always; decoder priority setting (device / app / device-only).
- **Resume**: position written every 5s + on pause/stop/discontinuity; completion sentinel
  at duration−1s; duration-identity check resets stale positions; clamp on restore.
- **Subtitle matching**: episode extraction + token overlap + duration similarity + language
  hints; cached per video.
- **Auto-sync**: live PCM analysis through a Media3 `AudioProcessor`; onset detection with
  adaptive floor/peak; histogram correlation against the subtitle cue model; coarse→fine
  offset search with confidence gating. Runs silently; only a toast when it locks.
- **Library**: MediaStore + ContentObserver (debounced); folder whitelist via SAF tree;
  folder → series auto-grouping using episode regexes.

## Build

```bash
./gradlew assembleDebug
```

## License

GPL-3.0 — required by the FFmpeg-based `nextlib` dependency.
