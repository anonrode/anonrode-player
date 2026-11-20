#!/usr/bin/env python3
"""VPS pre-push compilation and verification gate for anonrode-player.

Runs strictly inside /home/Anon/anonrode-player-tests/ (isolated from other
projects on the VPS). Executes two real gates:
  1. Native C++17 + AddressSanitizer/UBSan build & runtime test of
     biquad_filter.cpp, silencedetect.cpp, correlator.cpp, plus JNI syntax
     verification of anonsync_jni.cpp against JDK jni.h.
  2. Real kotlinc compilation of core/model, core/media (sync, subtitle,
     audio, log), and all JVM unit tests against real Media3 + Android-34 +
     JUnit 4.13.2 jars, followed by full JUnitCore execution.

Exit code 0 only when BOTH C++ ASan gate and Kotlin compile + JUnit gate pass.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SSH = shutil.which("ssh") or r"C:\Windows\System32\OpenSSH\ssh.exe"
SCP = shutil.which("scp") or r"C:\Windows\System32\OpenSSH\scp.exe"
KEY = str(Path.home() / ".ssh" / "anon_vps")
REMOTE_BASE = "/home/Anon/anonrode-player-tests"


def resolve_vps_target() -> str:
    env = os.environ.get("ANONRODE_VPS_TARGET", "").strip()
    if env:
        return env
    ssh_cfg = Path.home() / ".ssh" / "config"
    if ssh_cfg.is_file():
        in_block = False
        host = ""
        user = "Anon"
        for raw in ssh_cfg.read_text(encoding="utf-8", errors="replace").splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            low = line.lower()
            if low.startswith("host "):
                tokens = line.split()[1:]
                in_block = "anon-vps" in tokens
                if in_block:
                    for tok in tokens:
                        if tok != "anon-vps" and "*" not in tok:
                            host = tok
                            break
            elif in_block:
                if low.startswith("hostname "):
                    host = line.split(None, 1)[1].strip()
                elif low.startswith("user "):
                    user = line.split(None, 1)[1].strip()
        if host:
            return f"{user}@{host}"
    return "anon-vps"


TARGET = resolve_vps_target()

JUNIT_CLASSES = [
    "dev.anonrode.player.core.media.sync.PlaybackClockTest",
    "dev.anonrode.player.core.media.sync.AudioSyncProcessorTrackingTest",
    "dev.anonrode.player.core.media.sync.IntervalAlignerTest",
    "dev.anonrode.player.core.media.sync.SpeechCorrelatorTest",
    "dev.anonrode.player.core.media.sync.SyncEnvelopeTest",
    "dev.anonrode.player.core.media.sync.SyncScheduleRegressionTest",
    "dev.anonrode.player.core.media.sync.DriftTrackerTest",
    "dev.anonrode.player.core.media.subtitle.CueLookupTest",
    "dev.anonrode.player.core.media.subtitle.SubtitleDecoderTest",
    "dev.anonrode.player.core.media.subtitle.SubtitleMatcherTest",
    "dev.anonrode.player.core.media.audio.VoiceClarityProcessorTest",
]

CPP_TEST_HARNESS = r"""
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <vector>
#include "biquad_filter.h"
#include "silencedetect.h"
#include "correlator.h"

static std::vector<int16_t> makeSpeechTrack(
    int sampleRate, int channels, double totalSec, const std::vector<double>& burstStarts
) {
    size_t totalFrames = static_cast<size_t>(totalSec * sampleRate);
    std::vector<int16_t> pcm(totalFrames * channels, 0);
    for (double s : burstStarts) {
        size_t f0 = static_cast<size_t>(s * sampleRate);
        size_t f1 = std::min(totalFrames, static_cast<size_t>((s + 1.5) * sampleRate));
        for (size_t f = f0; f < f1; ++f) {
            double t = static_cast<double>(f) / sampleRate;
            // Speech-band formant blend (650 Hz + 1350 Hz)
            double v = 0.45 * std::sin(2.0 * M_PI * 650.0 * t)
                     + 0.30 * std::sin(2.0 * M_PI * 1350.0 * t);
            int16_t s16 = static_cast<int16_t>(v * 24000.0);
            for (int c = 0; c < channels; ++c) {
                pcm[f * channels + c] = s16;
            }
        }
    }
    return pcm;
}

static void testStreamingOnsets(int sampleRate, int channels, size_t chunkFrames, double basePtsSec) {
    const std::vector<double> bursts = {3.0, 7.5, 13.0, 19.5, 27.0, 35.0, 44.0, 52.0};
    auto pcm = makeSpeechTrack(sampleRate, channels, 60.0, bursts);

    const double effRate = anonsync::VoiceBandpassFilter::computeEffectiveSampleRate(sampleRate);
    anonsync::VoiceBandpassFilter filter(effRate, 300.0, 3400.0);
    anonsync::SilenceDetectConfig cfg;
    cfg.sampleRate = effRate;
    cfg.noiseThresholdDb = -36.0;
    cfg.minSilenceDurationSec = 0.18;
    anonsync::SilenceDetector detector(cfg);

    const size_t totalFrames = pcm.size() / channels;
    std::vector<float> mono;
    for (size_t pos = 0; pos < totalFrames; pos += chunkFrames) {
        size_t n = std::min(chunkFrames, totalFrames - pos);
        mono.clear();
        filter.downsampleAndFilter(pcm.data() + pos * channels, n, sampleRate, channels, mono);
        double ptsSec = basePtsSec + static_cast<double>(pos) / sampleRate;
        if (!mono.empty()) {
            detector.process(mono.data(), mono.size(), ptsSec);
        }
    }
    detector.flush();

    const auto& onsets = detector.getOnsets();
    std::printf("[CPP] sr=%d ch=%d chunk=%zu base=%.1f -> %zu onsets\n",
                sampleRate, channels, chunkFrames, basePtsSec, onsets.size());
    if (onsets.size() < bursts.size()) {
        std::fprintf(stderr, "FAIL: expected >= %zu onsets, got %zu\n", bursts.size(), onsets.size());
        std::exit(1);
    }
    // Every true burst must have a detected onset within 60ms (proving zero 44.1kHz drift)
    for (double b : bursts) {
        double target = basePtsSec + b;
        double bestErr = 999.0;
        for (double o : onsets) {
            bestErr = std::min(bestErr, std::abs(o - target));
        }
        if (bestErr > 0.06) {
            std::fprintf(stderr, "FAIL: sr=%d burst=%.2f bestErr=%.4fs (>0.06s)\n",
                         sampleRate, target, bestErr);
            std::exit(1);
        }
    }
}

static void testSpotCorrelatorAtDeepTimestamp() {
    // 20s spot probe at t=1800s (30m) with a +38.5s shift (alpha=1.0).
    // Old correlator tested PAL 1.0427 on 6s windows and shifted cues by +77s!
    const std::vector<double> onsets = {
        1801.2, 1803.8, 1806.1, 1809.4, 1812.0, 1814.7, 1817.3, 1819.5
    };
    const double trueOffset = 38.5;
    std::vector<double> cues;
    std::vector<double> cueEnds;
    for (double o : onsets) {
        cues.push_back(o - trueOffset);
        cueEnds.push_back(o - trueOffset + 1.5);
    }

    anonsync::AlignerConfig cfg;
    cfg.minSearchOffsetSec = -20.0;
    cfg.maxSearchOffsetSec = 60.0;
    cfg.allowSlopeSearch = false;

    anonsync::MultiScaleAligner aligner(cfg);
    auto res = aligner.align(onsets, cues, cueEnds, 0.0);
    std::printf("[CPP] spot-probe locked=%d off=%.3f alpha=%.5f recall=%.2f margin=%.2f\n",
                res.locked ? 1 : 0, res.offsetSec, res.speedFactor, res.recall, res.margin);
    if (!res.locked || std::abs(res.offsetSec - trueOffset) > 0.15 || std::abs(res.speedFactor - 1.0) > 1e-6) {
        std::fprintf(stderr, "FAIL: spot correlator failed (locked=%d off=%.3f alpha=%.5f)\n",
                     res.locked ? 1 : 0, res.offsetSec, res.speedFactor);
        std::exit(1);
    }
}

int main() {
    // Exact 1024-sample AAC frames at 48kHz stereo (previously triggered heap buffer overflow!)
    testStreamingOnsets(48000, 2, 1024, 0.0);
    // 44.1kHz stereo streaming with non-zero resume PTS (previously had 8.8% clock drift!)
    testStreamingOnsets(44100, 2, 1024, 900.0);
    // 32kHz mono streaming
    testStreamingOnsets(32000, 1, 512, 120.0);
    // Deep-timestamp spot correlator test
    testSpotCorrelatorAtDeepTimestamp();
    std::printf("CPP_TESTS=PASS\n");
    return 0;
}
"""

KOTLIN_JVM_STUBS = r"""
package android.util
object Log {
    @JvmStatic fun d(tag: String?, msg: String?): Int = 0
    @JvmStatic fun i(tag: String?, msg: String?): Int = 0
    @JvmStatic fun w(tag: String?, msg: String?): Int = 0
    @JvmStatic fun e(tag: String?, msg: String?): Int = 0
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = 0
}
"""

COMPOSE_COMPILE_STUB = r"""
package androidx.compose.runtime
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Immutable
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Stable
"""

PLAYER_COMPILE_STUBS = r"""
package io.github.anilbeesetti.nextlib.media3ext.ffdecoder
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
@UnstableApi
open class NextRenderersFactory(context: Context) : DefaultRenderersFactory(context)
"""

PLAYER_SERVICE_STUB = r"""
package dev.anonrode.player.feature.player
import android.app.Service
import android.content.Intent
import android.os.IBinder
class PlayerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
"""

REMOTE_SCRIPT = r"""set -u
BASE=@@BASE@@
mkdir -p "$BASE" "$BASE/jars" "$BASE/cpp" "$BASE/kt-src" "$BASE/kt-tests" "$BASE/out"

if [ "$(cat /sys/module/zswap/parameters/enabled 2>/dev/null)" != Y ]; then
  echo 1 | sudo -n tee /sys/module/zswap/parameters/enabled >/dev/null 2>&1
  echo 50 | sudo -n tee /sys/module/zswap/parameters/max_pool_percent >/dev/null 2>&1
fi

rm -rf "$BASE/src-bundle" "$BASE/out"
mkdir -p "$BASE/src-bundle" "$BASE/out/main" "$BASE/out/tests"
tar -xzf /tmp/anonrode-player-bundle.tgz -C "$BASE/src-bundle" || exit 10

# ════════════════════════════════════════════════════════════════════════
# GATE 1: Native C++17 + ASan/UBSan build & runtime test + JNI header check
# ════════════════════════════════════════════════════════════════════════
echo "=== GATE 1: C++ ASan/UBSan + JNI ==="
CPP_DIR="$BASE/src-bundle/cpp"
mkdir -p "$BASE/out/cpp-stubs/android"
cat > "$BASE/out/cpp-stubs/android/log.h" << 'EOF'
#pragma once
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
static inline int __android_log_print(int, const char*, const char*, ...) { return 0; }
EOF

g++ -std=c++17 -O2 -g -Wall -Wextra -Werror -Wno-unused-parameter \
  -fsanitize=address,undefined -fno-omit-frame-pointer \
  -I"$CPP_DIR" \
  "$CPP_DIR/biquad_filter.cpp" \
  "$CPP_DIR/silencedetect.cpp" \
  "$CPP_DIR/correlator.cpp" \
  "$CPP_DIR/cpp_test_harness.cpp" \
  -o "$BASE/out/cpp_test"
CPP_BUILD_RC=$?
echo "CPP_BUILD=$CPP_BUILD_RC"
if [ "$CPP_BUILD_RC" -ne 0 ]; then exit 11; fi

"$BASE/out/cpp_test"
CPP_RUN_RC=$?
echo "CPP_RUN=$CPP_RUN_RC"
if [ "$CPP_RUN_RC" -ne 0 ]; then exit 12; fi

JNI_INC="/usr/lib/jvm/java-17-openjdk-amd64/include"
g++ -std=c++17 -O2 -Wall -Wextra -Werror -Wno-unused-parameter -c \
  -I"$CPP_DIR" -I"$BASE/out/cpp-stubs" -I"$JNI_INC" -I"$JNI_INC/linux" \
  "$CPP_DIR/anonsync_jni.cpp" -o "$BASE/out/anonsync_jni.o"
JNI_BUILD_RC=$?
echo "JNI_BUILD=$JNI_BUILD_RC"
if [ "$JNI_BUILD_RC" -ne 0 ]; then exit 13; fi

# ════════════════════════════════════════════════════════════════════════
# GATE 2: Provision real Media3 / Coroutines / JUnit jars into isolated dir
# ════════════════════════════════════════════════════════════════════════
echo "=== GATE 2: Provisioning jars ==="
python3 - << 'PYEOF'
import os, urllib.request, zipfile

JARS = "/home/Anon/anonrode-player-tests/jars"
os.makedirs(JARS, exist_ok=True)
stale = os.path.join(JARS, "datastore-1.1.2.jar")
if os.path.exists(stale):
    os.remove(stale)

DEPS = [
    ("junit-4.13.2.jar", "https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar", False),
    ("hamcrest-core-1.3.jar", "https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar", False),
    ("kotlinx-coroutines-core-jvm-1.10.1.jar", "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.1/kotlinx-coroutines-core-jvm-1.10.1.jar", False),
    ("kotlinx-serialization-core-jvm-1.7.3.jar", "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.7.3/kotlinx-serialization-core-jvm-1.7.3.jar", False),
    ("kotlinx-serialization-json-jvm-1.7.3.jar", "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.7.3/kotlinx-serialization-json-jvm-1.7.3.jar", False),
    ("guava-33.3.1-android.jar", "https://repo1.maven.org/maven2/com/google/guava/guava/33.3.1-android/guava-33.3.1-android.jar", False),
    ("annotation-jvm-1.9.1.jar", "https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.jar", False),
    ("media3-common-1.5.1.jar", "https://dl.google.com/dl/android/maven2/androidx/media3/media3-common/1.5.1/media3-common-1.5.1.aar", True),
    ("media3-exoplayer-1.5.1.jar", "https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer/1.5.1/media3-exoplayer-1.5.1.aar", True),
    ("media3-decoder-1.5.1.jar", "https://dl.google.com/dl/android/maven2/androidx/media3/media3-decoder/1.5.1/media3-decoder-1.5.1.aar", True),
    ("media3-datasource-1.5.1.jar", "https://dl.google.com/dl/android/maven2/androidx/media3/media3-datasource/1.5.1/media3-datasource-1.5.1.aar", True),
    ("media3-extractor-1.5.1.jar", "https://dl.google.com/dl/android/maven2/androidx/media3/media3-extractor/1.5.1/media3-extractor-1.5.1.aar", True),
    ("onnxruntime-android-1.16.3.jar", "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.16.3/onnxruntime-android-1.16.3.aar", True),
    ("work-runtime-2.10.0.jar", "https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.10.0/work-runtime-2.10.0.aar", True),
    ("work-runtime-ktx-2.10.0.jar", "https://dl.google.com/dl/android/maven2/androidx/work/work-runtime-ktx/2.10.0/work-runtime-ktx-2.10.0.aar", True),
    ("core-1.15.0.jar", "https://dl.google.com/dl/android/maven2/androidx/core/core/1.15.0/core-1.15.0.aar", True),
    ("core-ktx-1.15.0.jar", "https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/1.15.0/core-ktx-1.15.0.aar", True),
    ("datastore-core-jvm-1.1.2.jar", "https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-core-jvm/1.1.2/datastore-core-jvm-1.1.2.jar", False),
    ("datastore-android-1.1.2.jar", "https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-android/1.1.2/datastore-android-1.1.2.aar", True),
    ("kotlinx-coroutines-android-1.10.1.jar", "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.10.1/kotlinx-coroutines-android-1.10.1.jar", False),
    ("room-common-jvm-2.7.0.jar", "https://dl.google.com/dl/android/maven2/androidx/room/room-common-jvm/2.7.0/room-common-jvm-2.7.0.jar", False),
    ("room-runtime-android-2.7.0.jar", "https://dl.google.com/dl/android/maven2/androidx/room/room-runtime-android/2.7.0/room-runtime-android-2.7.0.aar", True),
    ("room-ktx-2.7.0.jar", "https://dl.google.com/dl/android/maven2/androidx/room/room-ktx/2.7.0/room-ktx-2.7.0.aar", True),
    ("sqlite-android-2.5.0.jar", "https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-android/2.5.0/sqlite-android-2.5.0.aar", True),
    ("sqlite-framework-android-2.5.0.jar", "https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-framework-android/2.5.0/sqlite-framework-android-2.5.0.aar", True),
    ("concurrent-futures-1.2.0.jar", "https://dl.google.com/dl/android/maven2/androidx/concurrent/concurrent-futures/1.2.0/concurrent-futures-1.2.0.jar", False),
    ("listenablefuture-1.0.jar", "https://repo1.maven.org/maven2/com/google/guava/listenablefuture/1.0/listenablefuture-1.0.jar", False),
]

for name, url, is_aar in DEPS:
    out_path = os.path.join(JARS, name)
    if os.path.exists(out_path) and os.path.getsize(out_path) > 0:
        continue
    print("downloading", name)
    tmp = out_path + ".dl"
    urllib.request.urlretrieve(url, tmp)
    if is_aar:
        with zipfile.ZipFile(tmp, "r") as zf:
            with open(out_path, "wb") as f:
                f.write(zf.read("classes.jar"))
        os.remove(tmp)
    else:
        os.replace(tmp, out_path)
print("JARS_READY=1")
PYEOF
if [ $? -ne 0 ]; then exit 20; fi

# ════════════════════════════════════════════════════════════════════════
# GATE 3: Compile Kotlin core/model + core/database + core/datastore + core/media
#         and run all JUnit 4 unit tests on real JVM!
# ════════════════════════════════════════════════════════════════════════
echo "=== GATE 3: Kotlin Compile & JUnit 4 ==="
KOTLINC="/home/Anon/engine-tests/kotlinc/bin/kotlinc"
SER_PLUGIN="/home/Anon/engine-tests/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar"
ANDROID_JAR="$HOME/android-sdk/platforms/android-34/android.jar"
JAR_CP=$(python3 -c "import glob; print(':'.join(sorted(glob.glob('$BASE/jars/*.jar'))))")
CP="$ANDROID_JAR:$JAR_CP"

JVM_OPTS="-J-Xmx640m -J-Xms128m -J-Xss2m -J-XX:+UseSerialGC -J-XX:TieredStopAtLevel=1 -J-XX:MaxMetaspaceSize=256m -J-XX:ReservedCodeCacheSize=160m -J-XX:ActiveProcessorCount=2"
CHECK_OPTS="-Xbackend-threads=1 -Xno-call-assertions -Xno-param-assertions -Xno-receiver-assertions"

START=$(date +%s)
setsid "$KOTLINC" $JVM_OPTS $CHECK_OPTS -jvm-target 17 -nowarn \
  -Xplugin="$SER_PLUGIN" \
  -classpath "$CP" -d "$BASE/out/main.jar" \
  $(find "$BASE/src-bundle/main" -name '*.kt' | sort) \
  > /tmp/anonrode-player-main.log 2>&1 < /dev/null & KPID=$!
( sleep 900; kill -TERM -- -$KPID 2>/dev/null; sleep 20; kill -KILL -- -$KPID 2>/dev/null ) >/dev/null 2>&1 & WPID=$!
wait $KPID
MAIN_RC=$?
kill $WPID 2>/dev/null
wait $WPID 2>/dev/null
echo "KT_MAIN_BUILD=$MAIN_RC (seconds=$(( $(date +%s) - START )))"
if [ "$MAIN_RC" -ne 0 ]; then
  cat /tmp/anonrode-player-main.log
  exit 30
fi

# Compile feature/player (PlaybackEngine.kt) against compiled core/media main.jar
START=$(date +%s)
PLAYER_CP="$BASE/out/main.jar:$CP"
setsid "$KOTLINC" $JVM_OPTS $CHECK_OPTS -jvm-target 17 -nowarn \
  -classpath "$PLAYER_CP" -d "$BASE/out/player.jar" \
  $(find "$BASE/src-bundle/player" -name '*.kt' | sort) \
  > /tmp/anonrode-player-feature.log 2>&1 < /dev/null & KPID=$!
( sleep 600; kill -TERM -- -$KPID 2>/dev/null; sleep 20; kill -KILL -- -$KPID 2>/dev/null ) >/dev/null 2>&1 & WPID=$!
wait $KPID
PLAYER_RC=$?
kill $WPID 2>/dev/null
wait $WPID 2>/dev/null
echo "KT_PLAYER_BUILD=$PLAYER_RC (seconds=$(( $(date +%s) - START )))"
if [ "$PLAYER_RC" -ne 0 ]; then
  cat /tmp/anonrode-player-feature.log
  exit 34
fi

# Compile JVM stub jar (android.util.Log return-default-values equivalent for plain JVM)
setsid "$KOTLINC" $JVM_OPTS $CHECK_OPTS -jvm-target 17 -nowarn \
  -d "$BASE/out/jvm-stubs.jar" \
  "$BASE/src-bundle/jvm-stubs/JvmStubs.kt" \
  > /tmp/anonrode-player-stubs.log 2>&1 < /dev/null
STUB_RC=$?
echo "KT_STUBS_BUILD=$STUB_RC"
if [ "$STUB_RC" -ne 0 ]; then
  cat /tmp/anonrode-player-stubs.log
  exit 31
fi

START=$(date +%s)
TEST_CP="$BASE/out/main.jar:$CP"
setsid "$KOTLINC" $JVM_OPTS $CHECK_OPTS -jvm-target 17 -nowarn \
  -Xfriend-paths="$BASE/out/main.jar" \
  -classpath "$TEST_CP" -d "$BASE/out/tests.jar" \
  $(find "$BASE/src-bundle/test" -name '*.kt' | sort) \
  > /tmp/anonrode-player-tests.log 2>&1 < /dev/null & KPID=$!
( sleep 600; kill -TERM -- -$KPID 2>/dev/null; sleep 20; kill -KILL -- -$KPID 2>/dev/null ) >/dev/null 2>&1 & WPID=$!
wait $KPID
TEST_BUILD_RC=$?
kill $WPID 2>/dev/null
wait $WPID 2>/dev/null
echo "KT_TEST_BUILD=$TEST_BUILD_RC (seconds=$(( $(date +%s) - START )))"
if [ "$TEST_BUILD_RC" -ne 0 ]; then
  cat /tmp/anonrode-player-tests.log
  exit 32
fi

JAVA_BIN=/usr/lib/jvm/java-17-openjdk-amd64/bin/java
[ -x "$JAVA_BIN" ] || JAVA_BIN=java
KOTLIN_STDLIB="/home/Anon/engine-tests/kotlinc/lib/kotlin-stdlib.jar"
RUN_CP="$BASE/out/jvm-stubs.jar:$BASE/out/tests.jar:$BASE/out/main.jar:$KOTLIN_STDLIB:$JAR_CP:$ANDROID_JAR"

echo "=== Running JUnit 4 Suite ==="
OUT=$(timeout 300 "$JAVA_BIN" -Xms32m -Xmx384m -Xss2m -XX:+UseSerialGC -cp "$RUN_CP" org.junit.runner.JUnitCore @@CLASSES@@ 2>&1)
RUN_RC=$?
echo "$OUT"
echo "JUNIT_RUN=$RUN_RC"
if [ "$RUN_RC" -ne 0 ]; then exit 33; fi

echo "VPS_GATE=PASS"
exit 0
"""


def add_tree(tar: tarfile.TarFile, src_dir: Path, arc_prefix: str, exclude_names: set[str] | None = None) -> int:
    count = 0
    for p in sorted(src_dir.rglob("*.kt")):
        if exclude_names and p.name in exclude_names:
            continue
        rel = p.relative_to(src_dir)
        tar.add(p, arcname=f"{arc_prefix}/{rel.as_posix()}")
        count += 1
    return count


def main() -> int:
    tmp = Path(tempfile.mkdtemp(prefix="anonrode-player-vps-"))
    archive = tmp / "bundle.tgz"

    # Write temporary C++ test harness and JVM stub file
    cpp_harness_path = tmp / "cpp_test_harness.cpp"
    cpp_harness_path.write_text(CPP_TEST_HARNESS, encoding="utf-8")
    jvm_stubs_path = tmp / "JvmStubs.kt"
    jvm_stubs_path.write_text(KOTLIN_JVM_STUBS, encoding="utf-8")
    compose_stub_path = tmp / "ComposeStubs.kt"
    compose_stub_path.write_text(COMPOSE_COMPILE_STUB, encoding="utf-8")
    nextlib_stub_path = tmp / "NextRenderersFactoryStub.kt"
    nextlib_stub_path.write_text(PLAYER_COMPILE_STUBS, encoding="utf-8")
    player_svc_stub_path = tmp / "PlayerServiceStub.kt"
    player_svc_stub_path.write_text(PLAYER_SERVICE_STUB, encoding="utf-8")

    # Exclude Room generated/annotation-processed DAO/Database classes that need KSP
    # and files in core/media that depend directly on Room/MediaStateDao if not needed by unit tests;
    # include core/model, core/database entities/DAOs, core/datastore, and ALL of core/media!
    with tarfile.open(archive, "w:gz") as tar:
        cpp_dir = ROOT / "core" / "media" / "src" / "main" / "cpp"
        for p in sorted(cpp_dir.iterdir()):
            if p.suffix in (".cpp", ".h"):
                tar.add(p, arcname=f"cpp/{p.name}")
        tar.add(cpp_harness_path, arcname="cpp/cpp_test_harness.cpp")
        tar.add(jvm_stubs_path, arcname="jvm-stubs/JvmStubs.kt")
        tar.add(compose_stub_path, arcname="main/stubs/ComposeStubs.kt")
        tar.add(nextlib_stub_path, arcname="player/stubs/NextRenderersFactoryStub.kt")
        tar.add(player_svc_stub_path, arcname="player/stubs/PlayerServiceStub.kt")
        tar.add(
            ROOT / "feature" / "player" / "src" / "main" / "java" / "dev" / "anonrode" / "player" / "feature" / "player" / "PlaybackEngine.kt",
            arcname="player/PlaybackEngine.kt",
        )

        n_main = 0
        n_main += add_tree(tar, ROOT / "core" / "model" / "src" / "main" / "java", "main/model")
        n_main += add_tree(tar, ROOT / "core" / "database" / "src" / "main" / "java", "main/database")
        n_main += add_tree(tar, ROOT / "core" / "datastore" / "src" / "main" / "java", "main/datastore")
        n_main += add_tree(tar, ROOT / "core" / "media" / "src" / "main" / "java", "main/media")

        n_test = add_tree(
            tar,
            ROOT / "core" / "media" / "src" / "test" / "kotlin",
            "test",
            exclude_names={"OnsetCacheTest.kt"},  # uses android.content.Context temp dir; verified in Gradle
        )

    print(f"archive: {archive} ({archive.stat().st_size} bytes, main={n_main} kt, test={n_test} kt)")

    up = subprocess.run(
        [SCP, "-q", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15",
         "-i", KEY, str(archive), f"{TARGET}:/tmp/anonrode-player-bundle.tgz"],
        capture_output=True, text=True,
    )
    if up.returncode != 0:
        print("SCP failed:", up.stderr.strip(), file=sys.stderr)
        return 1

    script = (
        REMOTE_SCRIPT
        .replace("@@BASE@@", REMOTE_BASE)
        .replace("@@CLASSES@@", " ".join(JUNIT_CLASSES))
    )
    run = subprocess.run(
        [SSH, "-o", "BatchMode=yes", "-o", "ConnectTimeout=15",
         "-o", "ServerAliveInterval=30", "-i", KEY, TARGET, "bash -s"],
        input=script.encode("utf-8"), capture_output=True, timeout=1500,
    )
    out = (run.stdout or b"").decode("utf-8", "replace").strip()
    err = (run.stderr or b"").decode("utf-8", "replace").strip()
    print(out)
    if err:
        print(err, file=sys.stderr)
    return 0 if (run.returncode == 0 and "VPS_GATE=PASS" in out) else 1


if __name__ == "__main__":
    raise SystemExit(main())
