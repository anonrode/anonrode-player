---
name: mobile-app-engineering
description: Best practices and design principles for building high-performance, snappy Android video player applications with Jetpack Compose, ExoPlayer/Media3, and 60fps UI architecture.
---

# Mobile App Engineering & Modern Player UI Guide

This skill governs high-performance media player engineering and responsive Compose architecture.

## 1. Zero-Jank Compose Rendering
- **Avoid Heavy Work on Main Thread**: Never decode full-resolution video frames (e.g. 1080p, 8MB) synchronously on scroll. Always decode at bounded thumbnail dimensions (e.g. 240x135) with memory and disk caching.
- **Stable State Identification**: Use @Stable / @Immutable on state holders and data models. Always supply stable, unique keys to LazyColumn and LazyRow items.
- **Isolate Hot Paths**: Seek bars, position tickers, and buffer indicators must read dedicated State<Float> primitives so that 10-60Hz position ticks recompose only the seek bar, never the entire player surface.

## 2. Media Player UX Standards
- **Non-Blocking Auto-Resume**: Resume playback immediately if watch progress exists (> 5s). Provide a subtle, non-intrusive transient toast or bottom snackbar (Resumed at mm:ss · Start over) rather than popping up a blocking modal dialog.
- **Smart Aspect-Ratio Matching**:
  - Automatically match video geometry: launch widescreen videos (w > h) in landscape full-screen.
  - Provide a responsive aspect-ratio switcher sheet (Fit, Fill, Original, Stretch, 16:9, 4:3).
- **Unobstructed Dialogue & Subtitle Layer**:
  - Subtitles must occupy the lower-third or center-bottom without interference.
  - Gesture feedback (Volume, Brightness, Speed, Zoom) must float at TopCenter with status-bar insets, never dead-center over dialogue.
- **Sleek Scrubber Design**:
  - Progress line: thin 2.5dp–3dp track with high-contrast accent.
  - Thumb: solid, circular 14dp indicator with subtle drop shadow. No jarring vertical ticks or lines.
- **Scrollable & Minimizable Quick Ribbon**:
  - Quick action bar below header must be horizontally scrollable left-to-right.
  - Include an expand/collapse toggle (❮ / ❯) allowing users to minimize down to essentials or expand the full ribbon.
