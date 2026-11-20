#!/usr/bin/env bash
# Builds and runs the host unit tests for the CutVideo native engine.
# The same .cpp files are compiled into libcutvideo.so by the NDK build, so this is a real check of the
# shipped C++ core (jni_bridge.cpp is excluded because it needs <jni.h>).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/native-tests/build"
mkdir -p "$OUT"

CXX="${CXX:-g++}"
FLAGS=(-std=c++17 -O2 -Wall -Wextra -Wno-unused-parameter -I"$ROOT/app/src/main/cpp")

echo "== Compiling native engine (host) =="
"$CXX" "${FLAGS[@]}" \
  "$ROOT/tools/native_tests.cpp" \
  "$ROOT/app/src/main/cpp/video_processor.cpp" \
  "$ROOT/app/src/main/cpp/filter_engine.cpp" \
  "$ROOT/app/src/main/cpp/audio_processor.cpp" \
  "$ROOT/app/src/main/cpp/timeline_renderer.cpp" \
  "$ROOT/app/src/main/cpp/native_engine.cpp" \
  -o "$OUT/native_tests"

echo "== Running native engine tests =="
"$OUT/native_tests"
