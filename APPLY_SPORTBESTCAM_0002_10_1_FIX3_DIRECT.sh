#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

REPO="${SPORTBESTCAM_REPO:-$HOME/SportBestCam}"
cd "$REPO"

R="app/src/main/java/com/fadcam/opengl/GLWatermarkRenderer.java"
A="app/src/main/java/com/fadcam/opengl/GLRecordingPipeline.java"
G="app/build.gradle.kts"

echo
echo "=================================================="
echo " SPORTBESTCAM 0002.10.1 LIVE FX - FIX3 DIRECT"
echo " NO git apply / NO .diff"
echo "=================================================="
echo

test -f "$R" || { echo "Lipsește $R"; exit 1; }
test -f "$A" || { echo "Lipsește $A"; exit 1; }
test -f "$G" || { echo "Lipsește $G"; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/renderer_hook.txt" <<'EOF'
                // SportBestCam Live FX: composite directly on the encoder EGL
                // surface before the frame is handed to MediaCodec.
                com.fadcam.effects.SportEffectsGlEncoderBridge.drawIfActive(
                        watermarkProgram,
                        watermarkPositionHandle,
                        watermarkTexCoordHandle,
                        watermarkSamplerHandle,
                        forensicsOverlayRectBuffer,
                        watermarkTexCoordBuffer,
                        encoderWidth > 0 ? encoderWidth : videoWidth,
                        encoderHeight > 0 ? encoderHeight : videoHeight
                );
EOF

cat > "$TMP/audio_hook.txt" <<'EOF'
                        // SportBestCam Live FX: mix generated PCM before AAC.
                        com.fadcam.effects.SportEffectsAudioMixer.mixIntoPcm16(
                                readBuffer,
                                read,
                                audioSampleRate,
                                audioChannelCount
                        );

EOF

echo "1/3 Renderer encoder hook..."

sed -i \
  '/^[[:space:]]*com\.fadcam\.effects\.SportEffectsFrameRenderer\.draw(canvas);[[:space:]]*$/d' \
  "$R"

if grep -Fq "SportEffectsGlEncoderBridge.drawIfActive" "$R"; then
    echo "    deja prezent"
else
    R_LINE="$(
      grep -nF '                drawWatermark();' "$R" \
      | head -n 1 \
      | cut -d: -f1
    )"

    [ -n "${R_LINE:-}" ] || {
        echo "EROARE: nu găsesc drawWatermark() în encoder."
        exit 1
    }

    if ! sed -n "$((R_LINE + 1)),$((R_LINE + 4))p" "$R" \
        | grep -Fq 'eglSwapBuffers(eglDisplay, eglSurface)'; then
        echo "EROARE: primul drawWatermark() nu este encoderul așteptat."
        sed -n "$((R_LINE - 4)),$((R_LINE + 8))p" "$R"
        exit 1
    fi

    sed -i "${R_LINE}r $TMP/renderer_hook.txt" "$R"
    echo "    inserat după linia $R_LINE"
fi

echo "2/3 Audio SFX hook..."

if grep -Fq "SportEffectsAudioMixer.mixIntoPcm16" "$A"; then
    echo "    deja prezent"
else
    A_LINE="$(
      grep -nF '                            java.util.Arrays.fill(readBuffer, 0, read, (byte) 0);' "$A" \
      | head -n 1 \
      | cut -d: -f1
    )"

    [ -n "${A_LINE:-}" ] || {
        echo "EROARE: nu găsesc punctul de mute PCM."
        exit 1
    }

    if ! sed -n "$((A_LINE + 1)),$((A_LINE + 5))p" "$A" \
        | grep -Fq 'int offset = 0;'; then
        echo "EROARE: punctul PCM nu corespunde structurii așteptate."
        sed -n "$((A_LINE - 4)),$((A_LINE + 8))p" "$A"
        exit 1
    fi

    sed -i "${A_LINE}r $TMP/audio_hook.txt" "$A"
    echo "    inserat după linia $A_LINE"
fi

echo "3/3 Version name..."

if grep -Fq 'versionNameSuffix = "-beta10.6"' "$G"; then
    sed -i \
      's#versionNameSuffix = "-beta10\.6".*#versionNameSuffix = "-beta10.7" // SportBestCam 0002.10.1 Live FX FIX3#' \
      "$G"
    echo "    beta10.6 -> beta10.7"
elif grep -Fq 'versionNameSuffix = "-beta10.7"' "$G"; then
    echo "    beta10.7 deja prezent"
else
    echo "    suffix diferit; nu îl modific"
fi

echo
echo "Curăț helper-ele vechi eșuate..."
rm -f \
  APPLY_SPORTBESTCAM_0002_10_0.sh \
  APPLY_SPORTBESTCAM_0002_10_1.sh \
  APPLY_SPORTBESTCAM_0002_10_1_FIX2.sh \
  SPORTBESTCAM_BUILD_0002_10_1_LIVE_FX_FIX.txt \
  SPORTBESTCAM_BUILD_0002_10_1_LIVE_FX_FIX2.txt
rm -rf patches

echo
echo "Validare finală..."

test -f app/src/main/java/com/fadcam/effects/SportEffectsGlEncoderBridge.java
grep -Fq "drawerPanel" app/src/main/java/com/fadcam/effects/SportEffectsController.java
grep -Fq "scroll.setVisibility(View.GONE)" app/src/main/java/com/fadcam/effects/SportEffectsController.java
grep -Fq "SportEffectsGlEncoderBridge.drawIfActive" "$R"
grep -Fq "SportEffectsAudioMixer.mixIntoPcm16" "$A"

R_COUNT="$(grep -Fc "SportEffectsGlEncoderBridge.drawIfActive" "$R")"
A_COUNT="$(grep -Fc "SportEffectsAudioMixer.mixIntoPcm16" "$A")"

[ "$R_COUNT" -eq 1 ] || { echo "EROARE: renderer hook count=$R_COUNT"; exit 1; }
[ "$A_COUNT" -eq 1 ] || { echo "EROARE: audio hook count=$A_COUNT"; exit 1; }

if grep -Fq "SportEffectsState.wrapWatermark" \
    app/src/main/java/com/fadcam/handball/HandballVideoOverlay.java; then
    echo "EROARE: vechiul FX-through-watermark este încă prezent."
    exit 1
fi

git diff --check

echo
echo "=================================================="
echo " FIX3 DIRECT OK"
echo "=================================================="
echo
echo "Renderer hook: 1"
echo "Audio hook:    1"
echo "FX panel:      corrected"
echo
echo "Acum:"
echo "  git add ."
echo '  git commit -m "SportBestCam 0002.10.1 FIX3 direct Live FX encoder"'
echo "  git push origin main"
