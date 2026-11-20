#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IOS_RES_DIR="$ROOT_DIR/ios/HarmonyDirector/Resources"

METRO_DEST="$IOS_RES_DIR/Audio/Metronome"
PIANO_DEST="$IOS_RES_DIR/Audio/Piano"
ICON_DEST="$IOS_RES_DIR/Assets.xcassets/AppIcon.appiconset/AppIcon.png"

mkdir -p "$METRO_DEST" "$PIANO_DEST" "$(dirname "$ICON_DEST")"

echo "Syncing bundled metronome WAV samples..."
cp -f "$ROOT_DIR"/app/src/main/res/raw/metro_*.wav "$METRO_DEST/"

echo "Syncing bundled acoustic piano WAV multi-samples..."
cp -f "$ROOT_DIR"/app/src/main/assets/samples/piano/*.wav "$PIANO_DEST/"

ICON_SRC="$ROOT_DIR/app/src/main/res/drawable/app_icon_harmony_1794965097415.jpg"
if [ -f "$ICON_SRC" ]; then
  if command -v sips >/dev/null 2>&1; then
    echo "Generating 1024x1024 AppIcon.png via sips..."
    sips -s format png -z 1024 1024 "$ICON_SRC" --out "$ICON_DEST" >/dev/null
  else
    echo "sips not available; copying icon source directly."
    cp -f "$ICON_SRC" "$ICON_DEST"
  fi
fi

echo "Prepared iOS assets:"
echo "  Metronome WAVs: $(ls -1 "$METRO_DEST"/*.wav | wc -l | tr -d ' ')"
echo "  Piano WAVs:     $(ls -1 "$PIANO_DEST"/*.wav | wc -l | tr -d ' ')"
echo "  AppIcon exists: $( [ -f "$ICON_DEST" ] && echo yes || echo no )"
