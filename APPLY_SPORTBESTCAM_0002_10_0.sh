#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

REPO="$HOME/SportBestCam"
PATCH="$REPO/patches/SPORTBESTCAM_0002_10_0_LIVE_FX_CORE.diff"

echo
echo "=================================================="
echo " SPORTBESTCAM 0002.10.0 - LIVE FX"
echo "=================================================="
echo

if [ ! -d "$REPO/.git" ]; then
    echo "EROARE: repo-ul nu există la $REPO"
    exit 1
fi

cd "$REPO"

if grep -q "SportBestCam Live FX: mix generated SFX" \
    app/src/main/java/com/fadcam/opengl/GLRecordingPipeline.java 2>/dev/null \
    && grep -q "SportBestCam Live FX uses the same full-frame" \
    app/src/main/java/com/fadcam/opengl/GLWatermarkRenderer.java 2>/dev/null; then
    echo "Core Live FX este deja aplicat."
else
    echo "Verific patch-ul GL/audio..."
    git apply --check "$PATCH" || {
        echo
        echo "EROARE: baza repo-ului diferă de versiunea pentru care a fost creat 0002.10.0."
        echo "Nu am aplicat modificări parțiale în fișierele GL."
        echo "Trimite-mi 'git status --short' și ultimul commit."
        exit 1
    }

    git apply "$PATCH"
fi

echo
echo "Validări..."

test -f app/src/main/java/com/fadcam/effects/SportEffectsState.java
test -f app/src/main/java/com/fadcam/effects/SportEffectsAudioMixer.java
test -f app/src/main/java/com/fadcam/effects/SportEffectsFrameRenderer.java
test -f app/src/main/java/com/fadcam/effects/SportEffectsController.java
test -f app/src/main/java/com/fadcam/effects/SportEffectsBootstrapProvider.java
test -f app/src/debug/AndroidManifest.xml

grep -q "SportEffectsState.wrapWatermark" \
    app/src/main/java/com/fadcam/handball/HandballVideoOverlay.java

grep -q "SportEffectsAudioMixer.mixIntoPcm16" \
    app/src/main/java/com/fadcam/opengl/GLRecordingPipeline.java

grep -q "SportEffectsFrameRenderer.draw" \
    app/src/main/java/com/fadcam/opengl/GLWatermarkRenderer.java

grep -q 'versionNameSuffix = "-beta10.7"' app/build.gradle.kts

echo
echo "Live FX instalat în sursă."
echo
echo "Efecte rapide: Hearts / BOOM / Thunder / Goal / Goal Horn / FX"
echo "Lista completă este disponibilă din butonul FX în Fullscreen Preview."
echo
echo "Următorii pași:"
echo "  git add ."
echo '  git commit -m "SportBestCam 0002.10.0 Live FX visual and audio effects"'
echo "  git push origin main"
echo
