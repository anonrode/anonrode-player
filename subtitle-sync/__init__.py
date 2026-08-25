"""Subtitle auto-sync engine (design-phase Python prototype).

This package is the standalone design prototype for the subtitle auto-sync
engine used by ANONRODE PLAYER. The algorithm here is intentionally kept
readable (not clever) so it can be ported to Kotlin later.

Public API:
    parse_srt(text) -> list[Cue]
    parse_vtt(text) -> list[Cue]
    build_speech_signal(cues, duration_s, sample_rate_hz) -> np.ndarray
    build_energy_envelope(audio, sample_rate_hz, target_rate_hz) -> np.ndarray
    find_global_offset(speech, audio_env, sample_rate_hz) -> SyncResult
    find_drift(speech, audio_env, sample_rate_hz, num_windows=8) -> DriftResult
    sync_subtitles(cues, audio, sample_rate_hz) -> FinalSyncResult
"""

from .engine import (
    Cue,
    SyncResult,
    DriftResult,
    FinalSyncResult,
    parse_srt,
    parse_vtt,
    build_speech_signal,
    build_energy_envelope,
    find_global_offset,
    find_drift,
    sync_subtitles,
)

__all__ = [
    "Cue",
    "SyncResult",
    "DriftResult",
    "FinalSyncResult",
    "parse_srt",
    "parse_vtt",
    "build_speech_signal",
    "build_energy_envelope",
    "find_global_offset",
    "find_drift",
    "sync_subtitles",
]
