#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd "$HOME/SportBestCam"

G="app/build.gradle.kts"

echo
echo "=================================================="
echo " SPORTBESTCAM 0002.10.2 SIGNING FIX2"
echo " stable debug key forced explicitly"
echo "=================================================="
echo

test -f "$G" || {
  echo "EROARE: lipsește $G"
  exit 1
}

echo "1/2 Configurez debug build pentru cheia stabilă..."

if ! grep -Fq 'sportbestcamStableDebugSigning' "$G"; then
  sed -i '/^[[:space:]]*isDebuggable = true[[:space:]]*$/a\
            val stableDebugSigning = project.providers.gradleProperty("sportbestcamStableDebugSigning").orNull == "true"\
            if (stableDebugSigning) {\
                check(releaseSigningConfigValid) { "Stable SportBestCam signing requested but KEYSTORE_FILE is missing." }\
                signingConfig = signingConfigs.getByName("release")\
            }' "$G"
fi

grep -Fq 'sportbestcamStableDebugSigning' "$G" || {
  echo "EROARE: nu am putut configura stable debug signing."
  exit 1
}

echo "2/2 Setez versiunea beta10.8..."

sed -i -E \
  's#versionNameSuffix[[:space:]]*=[[:space:]]*"-beta10\.[0-9]+".*#versionNameSuffix = "-beta10.8" // SportBestCam 0002.10.2 stable signing FIX2#' \
  "$G"

grep -Fq 'versionNameSuffix = "-beta10.8"' "$G" || {
  echo "EROARE: beta10.8 nu a fost setat."
  exit 1
}

echo
echo "Verificări:"
grep -nF 'sportbestcamStableDebugSigning' "$G"
grep -nF 'signingConfig = signingConfigs.getByName("release")' "$G" | head -n 2
grep -nF 'versionNameSuffix = "-beta10.8"' "$G"

git diff --check

echo
echo "SIGNING FIX2 OK"
echo
echo "Acum:"
echo "  git add ."
echo '  git commit -m "SportBestCam 0002.10.2 force stable debug signing"'
echo "  git push origin main"
