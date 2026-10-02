#!/bin/sh
# Builds app/libs/turn.aar from the vk-turn-proxy Go core.
#
# Android counterpart of the iOS fork's Scripts/build-turn-xcframework.sh. The .aar is a
# gomobile bind of github.com/cacggghp/vk-turn-proxy/mobile/anywhere, which exposes the
# embeddable clientcore.Dialer (VLESS-mode TURN pipeline) to Kotlin as
# app.anywhere.turn.anywhere.{Anywhere, Dialer, Stream, LogSink}.
# The binary is NOT checked into git — run this script after cloning, before building.
#
# Requirements:
#   * Go 1.25+
#   * the sagernet gomobile fork:
#       go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.13
#       go install github.com/sagernet/gomobile/cmd/gobind@v0.1.13
#   * Android SDK ($ANDROID_HOME) with NDK 28.0.13004108 (or $ANDROID_NDK_HOME), JDK 17
#   * a checkout of vk-turn-proxy (branch dev) — path below or $VK_TURN_PROXY.
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE="${VK_TURN_PROXY:-$ROOT/../../../Turn/References/moroka8/vk-turn-proxy}"
OUT="$ROOT/app/libs/turn.aar"

export PATH="$PATH:$(go env GOPATH)/bin:/opt/homebrew/bin"

[ -d "$CORE" ] || { echo "vk-turn-proxy not found at $CORE (set \$VK_TURN_PROXY)"; exit 1; }
command -v gomobile >/dev/null || { echo "gomobile not found; see header of this script"; exit 1; }
command -v gobind   >/dev/null || { echo "gobind not found; see header of this script";   exit 1; }
[ -n "$ANDROID_HOME" ] || { echo "ANDROID_HOME is not set"; exit 1; }
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.0.13004108}"
[ -d "$ANDROID_NDK_HOME" ] || { echo "NDK not found at $ANDROID_NDK_HOME"; exit 1; }

mkdir -p "$(dirname "$OUT")"
cd "$CORE"
rm -f "$OUT"
# arm64 only, matching the app's abiFilters. -checklinkname=0 is mandatory:
# github.com/wlynxg/anet (pulled in by pion/transport/stdnet on Android) links against
# net.zoneCache, which Go 1.23+ refuses without it.
gomobile bind -v \
    -target=android/arm64 \
    -androidapi 26 \
    -javapkg app.anywhere.turn \
    -ldflags="-checklinkname=0 -s -w" \
    -o "$OUT" \
    ./mobile/anywhere

echo "built $OUT"
ls -l "$OUT"
