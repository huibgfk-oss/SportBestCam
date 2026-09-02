#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd "$HOME/SportBestCam"

A="app/src/main/java/com/fadcam/opengl/GLRecordingPipeline.java"
G="app/build.gradle.kts"

echo
echo "=================================================="
echo " SPORTBESTCAM 0002.10.4 - SFX AUDIO FIX"
echo "=================================================="
echo

test -f "$A" || {
  echo "EROARE: lipsește $A"
  exit 1
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/sfx_hook.txt" <<'EOF'
                        // SportBestCam Live FX: mix synthesized SFX into the
                        // captured PCM before it enters the AAC encoder.
                        com.fadcam.effects.SportEffectsAudioMixer.mixIntoPcm16(
                                readBuffer,
                                read,
                                audioSampleRate,
                                audioChannelCount
                        );

EOF

echo "1/4 Verific/insertez mixerul în recorder..."

if grep -Fq \
  'com.fadcam.effects.SportEffectsAudioMixer.mixIntoPcm16(' \
  "$A"; then
  echo "    mixIntoPcm16 deja prezent"
else
  FILL_LINE="$(
    grep -nF \
      'java.util.Arrays.fill(readBuffer, 0, read, (byte) 0);' \
      "$A" \
      | head -n1 \
      | cut -d: -f1
  )"

  [ -n "${FILL_LINE:-}" ] || {
    echo "EROARE: nu găsesc blocul realtime audio mute."
    exit 1
  }

  CLOSE_LINE=$((FILL_LINE + 1))
  OFFSET_LINE=$((FILL_LINE + 2))

  if ! sed -n "${CLOSE_LINE}p" "$A" \
      | grep -Eq '^[[:space:]]*}[[:space:]]*$'; then
    echo "EROARE: linia de după Arrays.fill nu închide audioMuted."
    sed -n "$((FILL_LINE - 4)),$((FILL_LINE + 8))p" "$A"
    exit 1
  fi

  if ! sed -n "${OFFSET_LINE}p" "$A" \
      | grep -Fq 'int offset = 0;'; then
    echo "EROARE: AAC input nu urmează blocul audioMuted."
    sed -n "$((FILL_LINE - 4)),$((FILL_LINE + 10))p" "$A"
    exit 1
  fi

  sed -i "${CLOSE_LINE}r $TMP/sfx_hook.txt" "$A"
  echo "    mixer inserat după audioMuted și înainte de AAC"
fi

echo "2/4 Verific unicitatea hook-ului..."

COUNT="$(
  grep -Fc \
    'com.fadcam.effects.SportEffectsAudioMixer.mixIntoPcm16(' \
    "$A"
)"

[ "$COUNT" -eq 1 ] || {
  echo "EROARE: mixIntoPcm16 apare de $COUNT ori."
  exit 1
}

echo "3/4 Verific monitorul audio live..."

M="app/src/main/java/com/fadcam/effects/SportEffectsAudioMixer.java"

grep -Fq '.USAGE_MEDIA' "$M" || {
  echo "EROARE: monitorul SFX nu este pe USAGE_MEDIA."
  exit 1
}

grep -Fq 'track.setVolume(1.0f);' "$M" || {
  echo "EROARE: volumul monitorului SFX nu este 1.0."
  exit 1
}

echo "4/4 Setez beta10.9..."

sed -i -E \
  's#versionNameSuffix[[:space:]]*=[[:space:]]*"-beta10\.[0-9]+".*#versionNameSuffix = "-beta10.9" // SportBestCam 0002.10.4 SFX audio fix#' \
  "$G"

grep -Fq 'versionNameSuffix = "-beta10.9"' "$G" || {
  echo "EROARE: nu pot seta beta10.9."
  exit 1
}

git diff --check

echo
echo "Context recorder după fix:"
HOOK_LINE="$(
  grep -nF \
    'com.fadcam.effects.SportEffectsAudioMixer.mixIntoPcm16(' \
    "$A" \
    | head -n1 \
    | cut -d: -f1
)"
sed -n "$((HOOK_LINE - 7)),$((HOOK_LINE + 10))p" "$A"

echo
echo "=================================================="
echo " SFX AUDIO FIX OK"
echo "=================================================="
echo
echo "Live monitor: Android MEDIA volume"
echo "Recorded SFX: PCM -> AAC -> MP4"
echo
echo "Acum:"
echo "  git add ."
echo '  git commit -m "SportBestCam 0002.10.4 fix SFX audio path"'
echo "  git push origin main"
