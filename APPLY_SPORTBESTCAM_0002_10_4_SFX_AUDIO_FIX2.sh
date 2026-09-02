#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd "$HOME/SportBestCam"

A="app/src/main/java/com/fadcam/opengl/GLRecordingPipeline.java"
G="app/build.gradle.kts"

echo
echo "=================================================="
echo " SPORTBESTCAM 0002.10.4 - SFX AUDIO FIX2"
echo " blank-line tolerant PCM hook"
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

echo "1/4 Verific/insertez mixerul recorder..."

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
    echo "EROARE: nu găsesc Arrays.fill din audioMuted."
    exit 1
  }

  CLOSE_LINE=""

  for N in 1 2 3 4 5 6; do
    CANDIDATE=$((FILL_LINE + N))
    LINE="$(
      sed -n "${CANDIDATE}p" "$A" \
      | tr -d '[:space:]'
    )"

    if [ "$LINE" = "}" ]; then
      CLOSE_LINE="$CANDIDATE"
      break
    fi
  done

  [ -n "${CLOSE_LINE:-}" ] || {
    echo "EROARE: nu găsesc închiderea blocului audioMuted după Arrays.fill."
    sed -n "$((FILL_LINE - 4)),$((FILL_LINE + 12))p" "$A"
    exit 1
  }

  OFFSET_LINE=""

  for N in 1 2 3 4 5 6; do
    CANDIDATE=$((CLOSE_LINE + N))

    if sed -n "${CANDIDATE}p" "$A" \
        | grep -Fq 'int offset = 0;'; then
      OFFSET_LINE="$CANDIDATE"
      break
    fi
  done

  [ -n "${OFFSET_LINE:-}" ] || {
    echo "EROARE: nu găsesc int offset = 0 după audioMuted."
    sed -n "$((FILL_LINE - 4)),$((FILL_LINE + 14))p" "$A"
    exit 1
  }

  sed -i "${CLOSE_LINE}r $TMP/sfx_hook.txt" "$A"
  echo "    mixer inserat după linia $CLOSE_LINE"
fi

echo "2/4 Verific exact un singur hook..."

COUNT="$(
  grep -Fc \
    'com.fadcam.effects.SportEffectsAudioMixer.mixIntoPcm16(' \
    "$A"
)"

[ "$COUNT" -eq 1 ] || {
  echo "EROARE: mixIntoPcm16 apare de $COUNT ori."
  exit 1
}

HOOK_LINE="$(
  grep -nF \
    'com.fadcam.effects.SportEffectsAudioMixer.mixIntoPcm16(' \
    "$A" \
    | head -n1 \
    | cut -d: -f1
)"

OFFSET_AFTER="$(
  tail -n "+$((HOOK_LINE + 1))" "$A" \
    | grep -nF -m1 'int offset = 0;' \
    | cut -d: -f1
)"

[ -n "${OFFSET_AFTER:-}" ] || {
  echo "EROARE: hook-ul nu este înainte de AAC."
  exit 1
}

echo "    hook recorder: OK"

echo "3/4 Verific monitorul live..."

M="app/src/main/java/com/fadcam/effects/SportEffectsAudioMixer.java"

grep -Fq '.USAGE_MEDIA' "$M" || {
  echo "EROARE: monitorul nu este pe USAGE_MEDIA."
  exit 1
}

grep -Fq 'track.setVolume(1.0f);' "$M" || {
  echo "EROARE: volumul monitorului nu este 1.0."
  exit 1
}

echo "    monitor MEDIA: OK"

echo "4/4 Setez beta10.9..."

sed -i -E \
  's#versionNameSuffix[[:space:]]*=[[:space:]]*"-beta10\.[0-9]+".*#versionNameSuffix = "-beta10.9" // SportBestCam 0002.10.4 SFX audio FIX2#' \
  "$G"

grep -Fq 'versionNameSuffix = "-beta10.9"' "$G" || {
  echo "EROARE: nu pot seta beta10.9."
  exit 1
}

git diff --check

echo
echo "Context final audio:"
sed -n "$((HOOK_LINE - 8)),$((HOOK_LINE + 12))p" "$A"

echo
echo "=================================================="
echo " SFX AUDIO FIX2 OK"
echo "=================================================="
echo
echo "Acum:"
echo "  git add ."
echo '  git commit -m "SportBestCam 0002.10.4 SFX audio FIX2"'
echo "  git push origin main"
