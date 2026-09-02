#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd "$HOME/SportBestCam"

A="app/src/main/java/com/fadcam/opengl/GLRecordingPipeline.java"
G="app/build.gradle.kts"

echo
echo "=================================================="
echo " SPORTBESTCAM 0002.10.3 - AUDIO FX REAL FIX"
echo "=================================================="
echo

test -f "$A" || {
  echo "EROARE: lipsește $A"
  exit 1
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/mixer_hook.txt" <<'EOF'
                        // SportBestCam Live FX: mix SFX digitally into captured
                        // PCM before the buffer is handed to the AAC encoder.
                        com.fadcam.effects.SportEffectsAudioMixer.mixIntoPcm16(
                                readBuffer,
                                read,
                                audioSampleRate,
                                audioChannelCount
                        );

EOF

echo "1/3 Verific hook-ul recorder PCM..."

if grep -Fq \
  'SportEffectsAudioMixer.mixIntoPcm16(' \
  "$A"; then
  echo "    hook mixer deja prezent"
else
  FILL_LINE="$(
    grep -nF \
      'java.util.Arrays.fill(readBuffer, 0, read, (byte) 0);' \
      "$A" \
      | head -n1 \
      | cut -d: -f1
  )"

  [ -n "${FILL_LINE:-}" ] || {
    echo "EROARE: nu găsesc blocul audioMuted."
    exit 1
  }

  CLOSE_LINE=$((FILL_LINE + 1))

  if ! sed -n "${CLOSE_LINE}p" "$A" \
      | grep -Eq '^[[:space:]]*}[[:space:]]*$'; then
    echo "EROARE: structura audio locală este diferită."
    sed -n "$((FILL_LINE - 4)),$((FILL_LINE + 8))p" "$A"
    exit 1
  fi

  OFFSET_LINE=$((CLOSE_LINE + 1))

  if ! sed -n "${OFFSET_LINE}p" "$A" \
      | grep -Fq 'int offset = 0;'; then
    echo "EROARE: encoderul AAC nu urmează blocul mute."
    sed -n "$((FILL_LINE - 4)),$((FILL_LINE + 10))p" "$A"
    exit 1
  fi

  sed -i "${CLOSE_LINE}r $TMP/mixer_hook.txt" "$A"
  echo "    hook inserat după audioMuted și înainte de AAC"
fi

echo "2/3 Verific exact o singură inserție..."

COUNT="$(
  grep -Fc \
    'SportEffectsAudioMixer.mixIntoPcm16(' \
    "$A"
)"

[ "$COUNT" -eq 1 ] || {
  echo "EROARE: mixIntoPcm16 apare de $COUNT ori."
  exit 1
}

echo "3/3 Setez beta10.9..."

sed -i -E \
  's#versionNameSuffix[[:space:]]*=[[:space:]]*"-beta10\.[0-9]+".*#versionNameSuffix = "-beta10.9" // SportBestCam 0002.10.3 Audio FX real fix#' \
  "$G"

grep -Fq \
  'versionNameSuffix = "-beta10.9"' \
  "$G" || {
    echo "EROARE: nu pot seta beta10.9."
    exit 1
  }

test -f \
  app/src/main/java/com/fadcam/effects/SportEffectsAudioMixer.java

grep -Fq \
  'SportEffectsAudioMixer.trigger(effect.audioKind, now)' \
  app/src/main/java/com/fadcam/effects/SportEffectsState.java

grep -Fq \
  'SFX monitor PLAY' \
  app/src/main/java/com/fadcam/effects/SportEffectsAudioMixer.java

git diff --check

echo
echo "=================================================="
echo " AUDIO FX 0002.10.3 OK"
echo "=================================================="
echo
echo "La apăsare:"
echo "  1. SFX se aude imediat prin telefon / BT"
echo "  2. același SFX intră digital în PCM -> AAC -> MP4"
echo
echo "Acum:"
echo "  git add ."
echo '  git commit -m "SportBestCam 0002.10.3 fix audible and recorded SFX"'
echo "  git push origin main"
