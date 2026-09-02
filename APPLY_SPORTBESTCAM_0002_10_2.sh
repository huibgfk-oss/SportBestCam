#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

REPO="$HOME/SportBestCam"
cd "$REPO"

A="app/src/main/java/com/fadcam/opengl/GLRecordingPipeline.java"
R="app/src/main/java/com/fadcam/opengl/GLWatermarkRenderer.java"
G="app/build.gradle.kts"

echo
echo "=================================================="
echo " SPORTBESTCAM 0002.10.2"
echo " AUDIO FIX + FX PACK + REAL IN-APP UPDATE"
echo "=================================================="
echo

test -f "$A" || { echo "Lipsește $A"; exit 1; }
test -f "$R" || { echo "Lipsește $R"; exit 1; }
test -f "$G" || { echo "Lipsește $G"; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/audio_hook.txt" <<'EOF'
                        // SportBestCam Live FX: digital SFX are mixed AFTER the
                        // realtime-mute block, so they work with mic ON or MUTE.
                        com.fadcam.effects.SportEffectsAudioMixer.mixIntoPcm16(
                                readBuffer,
                                read,
                                audioSampleRate,
                                audioChannelCount
                        );

EOF

echo "1/4 Repar poziția mixerului audio..."

CALL_LINE="$(
  grep -nF \
    'com.fadcam.effects.SportEffectsAudioMixer.mixIntoPcm16(' \
    "$A" \
    | head -n1 \
    | cut -d: -f1
)"

if [ -n "${CALL_LINE:-}" ]; then
  START="$CALL_LINE"
  PREV=$((CALL_LINE - 1))

  if sed -n "${PREV}p" "$A" | grep -Fq 'Live FX'; then
    START="$PREV"
  fi

  END="$CALL_LINE"
  LIMIT=$((CALL_LINE + 12))

  while [ "$END" -le "$LIMIT" ]; do
    if sed -n "${END}p" "$A" | grep -Fq ');'; then
      break
    fi
    END=$((END + 1))
  done

  if [ "$END" -gt "$LIMIT" ]; then
    echo "EROARE: nu pot identifica finalul vechiului hook audio."
    exit 1
  fi

  sed -i "${START},${END}d" "$A"
fi

FILL_LINE="$(
  grep -nF \
    'java.util.Arrays.fill(readBuffer, 0, read, (byte) 0);' \
    "$A" \
    | head -n1 \
    | cut -d: -f1
)"

[ -n "${FILL_LINE:-}" ] || {
  echo "EROARE: nu găsesc realtime mute PCM."
  exit 1
}

CLOSE_LINE=$((FILL_LINE + 1))

if ! sed -n "${CLOSE_LINE}p" "$A" \
    | grep -Eq '^[[:space:]]*}[[:space:]]*$'; then
  echo "EROARE: structura audio nu corespunde după curățarea hook-ului."
  sed -n "$((FILL_LINE - 3)),$((FILL_LINE + 8))p" "$A"
  exit 1
fi

sed -i "${CLOSE_LINE}r $TMP/audio_hook.txt" "$A"

CALL_LINE="$(
  grep -nF \
    'com.fadcam.effects.SportEffectsAudioMixer.mixIntoPcm16(' \
    "$A" \
    | head -n1 \
    | cut -d: -f1
)"

OFFSET_REL="$(
  tail -n "+$((CALL_LINE + 1))" "$A" \
    | grep -nF -m1 'int offset = 0;' \
    | cut -d: -f1
)"

OFFSET_LINE=""
if [ -n "${OFFSET_REL:-}" ]; then
  OFFSET_LINE=$((CALL_LINE + OFFSET_REL))
fi

if [ -z "${CALL_LINE:-}" ] \
    || [ -z "${OFFSET_LINE:-}" ] \
    || [ "$CALL_LINE" -le "$CLOSE_LINE" ] \
    || [ "$CALL_LINE" -ge "$OFFSET_LINE" ]; then
  echo "EROARE: mixerul audio nu este între mute și encoder."
  exit 1
fi

echo "    audio mixer: după mute, înainte de AAC"

echo "2/4 Verific encoderul Live FX..."

if ! grep -Fq \
    'SportEffectsGlEncoderBridge.drawIfActive' \
    "$R"; then
  echo "EROARE: lipsește hook-ul video Live FX din encoder."
  echo "Actualizează mai întâi main și rulează din nou."
  exit 1
fi

echo "    GL encoder hook: OK"

echo "3/4 Setez versiunea beta10.8..."

sed -i -E \
  's#versionNameSuffix[[:space:]]*=[[:space:]]*"-beta10\.[0-9]+".*#versionNameSuffix = "-beta10.8" // SportBestCam 0002.10.2 FX + updater + apksigner FIX1#' \
  "$G"

grep -Fq 'versionNameSuffix = "-beta10.8"' "$G" || {
  echo "EROARE: nu am putut seta beta10.8."
  grep -n 'versionNameSuffix' "$G" || true
  exit 1
}

grep -n 'versionNameSuffix' "$G" | head -n1

echo "4/4 Curăț helper-ele vechi..."

rm -f \
  APPLY_SPORTBESTCAM_0002_10_0.sh \
  APPLY_SPORTBESTCAM_0002_10_1.sh \
  APPLY_SPORTBESTCAM_0002_10_1_FIX2.sh \
  APPLY_SPORTBESTCAM_0002_10_1_FIX3_DIRECT.sh \
  SPORTBESTCAM_BUILD_0002_10_0_LIVE_FX.txt \
  SPORTBESTCAM_BUILD_0002_10_1_LIVE_FX_FIX.txt \
  SPORTBESTCAM_BUILD_0002_10_1_LIVE_FX_FIX2.txt \
  SPORTBESTCAM_BUILD_0002_10_1_LIVE_FX_FIX3_DIRECT.txt
rm -rf patches

echo
echo "Validare finală..."

test -f \
  app/src/main/java/com/fadcam/effects/SportBestCamUpdateManager.java

grep -Fq \
  'android.permission.REQUEST_INSTALL_PACKAGES' \
  app/src/debug/AndroidManifest.xml

grep -Fq \
  'Publish GitHub Release for in-app updater' \
  .github/workflows/build-handball-apk.yml

grep -Fq \
  'SportEffectsAudioMixer.mixIntoPcm16' \
  "$A"

grep -Fq \
  'SportEffectsGlEncoderBridge.drawIfActive' \
  "$R"

git diff --check

echo
echo "=================================================="
echo " SPORTBESTCAM 0002.10.2 SOURCE OK"
echo "=================================================="
echo
echo "Audio FX: mic ON + mic MUTE"
echo "Visual FX catalog: expanded"
echo "GitHub Release updater: enabled"
echo "Signature check before install: enabled"
echo
echo "Acum:"
echo "  git add ."
echo '  git commit -m "SportBestCam 0002.10.2 audio FX visual pack and in-app updater"'
echo "  git push origin main"
