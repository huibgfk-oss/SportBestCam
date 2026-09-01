SportBestCam 0002.9.0 - Persistent Update Signing

PURPOSE
All GitHub Actions debug APKs use the same persistent signing key.
The workflow also assigns an increasing CI versionCode (100000 + GitHub run number).

ONE-TIME SETUP REQUIRED
Create a permanent debug keystore with the standard Android debug alias/password:

  keytool -genkeypair -v \
    -keystore sportbestcam-debug.keystore \
    -storepass android \
    -alias androiddebugkey \
    -keypass android \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=SportBestCam Debug,O=SportBestCam"

Create a one-line Base64 value:

  base64 -w 0 sportbestcam-debug.keystore > sportbestcam-debug-keystore.b64

In GitHub repository huibgfk-oss/SportBestCam:
Settings -> Secrets and variables -> Actions -> New repository secret
Name: SPORTBESTCAM_DEBUG_KEYSTORE_B64
Value: paste the complete contents of sportbestcam-debug-keystore.b64

Keep sportbestcam-debug.keystore backed up securely. Never commit it to the public repository.

IMPORTANT MIGRATION NOTE
The APK currently installed on the phone may have been signed by a temporary GitHub runner key.
If so, the FIRST build using this new permanent key cannot update that old installation.
One final uninstall/reinstall may be required. After the permanent-key build is installed,
all future builds from this workflow can update it in place, provided this same secret/key is retained.
