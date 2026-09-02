#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

REPO="${HOME}/SportBestCam"
BACKUP_DIR="/storage/emulated/0/Documents/SportBestCam/signing"
KEYSTORE="${BACKUP_DIR}/sportbestcam-debug.p12"
INFO="${BACKUP_DIR}/SPORTBESTCAM_SIGNING_BACKUP.txt"
SECRET_NAME="SPORTBESTCAM_DEBUG_KEYSTORE_B64"
ALIAS="androiddebugkey"
PASSWORD="android"

echo
echo "=================================================="
echo " SPORTBESTCAM - STABLE APK SIGNING SETUP"
echo " BUILD 0002.9.1"
echo "=================================================="
echo

if [ ! -d "$REPO/.git" ]; then
    echo "EROARE: repo SportBestCam nu există la:"
    echo "$REPO"
    exit 1
fi

command -v gh >/dev/null 2>&1 || {
    echo "EROARE: GitHub CLI (gh) nu este instalat."
    exit 1
}

if ! command -v openssl >/dev/null 2>&1; then
    echo "OpenSSL nu este disponibil. Încerc instalarea..."
    pkg install -y openssl-tool || pkg install -y openssl || {
        echo "EROARE: OpenSSL nu a putut fi instalat."
        exit 1
    }
fi

cd "$REPO"

echo "Repository:"
git remote -v | head -2
echo

SECRET_EXISTS=0
if gh secret list --json name --jq '.[].name' 2>/dev/null | grep -qx "$SECRET_NAME"; then
    SECRET_EXISTS=1
fi

mkdir -p "$BACKUP_DIR"

if [ "$SECRET_EXISTS" -eq 1 ]; then
    echo "Secretul GitHub $SECRET_NAME există deja."
    echo "NU îl înlocuiesc, pentru a păstra aceeași semnătură APK."

    if [ ! -f "$KEYSTORE" ]; then
        echo
        echo "ATENȚIE:"
        echo "Secretul există în GitHub, dar backup-ul local nu este prezent:"
        echo "$KEYSTORE"
        echo
        echo "GitHub nu permite citirea înapoi a valorii secretului."
        echo "Nu genera o cheie nouă cât timp vrei compatibilitate cu APK-urile deja semnate."
    else
        echo "Backup local găsit:"
        echo "$KEYSTORE"
    fi

    echo
    echo "Signing setup este deja activ."
    exit 0
fi

if [ -s "$KEYSTORE" ]; then
    echo "Folosesc cheia stabilă existentă din backup:"
    echo "$KEYSTORE"

    if ! openssl pkcs12 \
        -in "$KEYSTORE" \
        -passin "pass:$PASSWORD" \
        -noout; then
        echo "EROARE: backup-ul existent nu este PKCS#12 valid cu parola așteptată."
        exit 1
    fi
else
    echo "Generez cheia stabilă SportBestCam cu OpenSSL..."

    TMP_KEY="${BACKUP_DIR}/.sportbestcam-key.pem"
    TMP_CERT="${BACKUP_DIR}/.sportbestcam-cert.pem"
    rm -f "$TMP_KEY" "$TMP_CERT" "$KEYSTORE"

    openssl req \
        -x509 \
        -newkey rsa:3072 \
        -sha256 \
        -nodes \
        -keyout "$TMP_KEY" \
        -out "$TMP_CERT" \
        -days 10000 \
        -subj "/CN=SportBestCam Debug/O=SportBestCam/C=RO"

    openssl pkcs12 \
        -export \
        -out "$KEYSTORE" \
        -inkey "$TMP_KEY" \
        -in "$TMP_CERT" \
        -name "$ALIAS" \
        -passout "pass:$PASSWORD"

    rm -f "$TMP_KEY" "$TMP_CERT"
    chmod 600 "$KEYSTORE" 2>/dev/null || true
fi

if [ ! -s "$KEYSTORE" ]; then
    echo "EROARE: keystore-ul nu a fost creat."
    exit 1
fi

openssl pkcs12 \
    -in "$KEYSTORE" \
    -passin "pass:$PASSWORD" \
    -noout

{
    echo "SPORTBESTCAM STABLE SIGNING BACKUP"
    echo
    echo "Keystore: sportbestcam-debug.p12"
    echo "Alias: $ALIAS"
    echo "Store password: $PASSWORD"
    echo "Key password: $PASSWORD"
    echo
    echo "IMPORTANT:"
    echo "- păstrează acest folder;"
    echo "- nu încărca keystore-ul în Git;"
    echo "- nu înlocui cheia după ce ai instalat primul APK semnat cu ea;"
    echo "- toate buildurile viitoare trebuie să folosească aceeași cheie."
} > "$INFO"

chmod 600 "$INFO" 2>/dev/null || true

echo
echo "Încarc cheia în GitHub Secrets..."

KEYSTORE_B64="$(base64 "$KEYSTORE" | tr -d '\r\n')"
gh secret set "$SECRET_NAME" --body "$KEYSTORE_B64"

echo
echo "Verific existența secretului..."
gh secret list --json name --jq '.[].name' | grep -qx "$SECRET_NAME"

echo
echo "=================================================="
echo " SIGNING STABIL ACTIVAT"
echo "=================================================="
echo
echo "Backup local:"
echo "$BACKUP_DIR"
echo
echo "Secret GitHub:"
echo "$SECRET_NAME"
echo
echo "Poți face acum commit + push."
