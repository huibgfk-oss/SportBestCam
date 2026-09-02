SportBestCam 0002.9.1 - Automatic Persistent APK Signing

SCOP
----
Toate APK-urile SportBestCam construite de GitHub Actions pe workflow-ul
`build-handball-apk.yml` folosesc aceeași cheie persistentă.

Workflow-ul păstrează buildul actual `assembleDefaultDebug`, deci NU schimbă:
- applicationId-ul folosit de buildul debug;
- flavor-ul `default`;
- structura aplicației;
- funcțiile SportBestCam.

În plus, workflow-ul păstrează versionCode monoton:
  100000 + GitHub run number

ASTA PERMITE UPDATE PESTE APK-UL EXISTENT
----------------------------------------
Android acceptă update-ul numai dacă:
1. package/applicationId este același;
2. APK-ul nou este semnat cu aceeași cheie;
3. versionCode-ul nou este mai mare.

BUILD 0002.9.1 fixează punctele 2 și 3 pentru buildurile GitHub Actions.

SETUP AUTOMAT, O SINGURĂ DATĂ
-----------------------------
După aplicarea patch-ului, rulează:

  bash ~/SportBestCam/SETUP_SPORTBESTCAM_SIGNING.sh

Scriptul:
- verifică dacă secretul GitHub există deja;
- dacă există, NU îl înlocuiește;
- dacă lipsește, generează o cheie RSA stabilă cu OpenSSL;
- construiește un PKCS#12 cu alias `androiddebugkey`;
- salvează backup-ul în:
    /storage/emulated/0/Documents/SportBestCam/signing/
- creează GitHub Secret:
    SPORTBESTCAM_DEBUG_KEYSTORE_B64

Nu folosește `keytool` în Termux pentru generarea cheii, evitând problema
`PerfettoTrace; aborting` întâlnită la Evaluare App.

GITHUB ACTIONS
--------------
În runner:
1. secretul PKCS#12 este restaurat;
2. este verificat cu OpenSSL;
3. este convertit în ~/.android/debug.keystore;
4. alias/parola sunt validate;
5. se setează versionCode monoton;
6. se construiește `assembleDefaultDebug`;
7. semnătura APK-ului final este verificată;
8. APK-ul este încărcat ca artifact.

MIGRARE
-------
Dacă APK-ul instalat acum este semnat cu o cheie temporară diferită,
mai este necesară O SINGURĂ dezinstalare/reinstalare.

Dacă APK-ul instalat este deja semnat cu cheia persistentă existentă în
`SPORTBESTCAM_DEBUG_KEYSTORE_B64`, NU trebuie reinstalat.

După instalarea primului APK semnat cu cheia stabilă, buildurile viitoare
pot fi instalate direct ca UPDATE, atât timp cât cheia nu este schimbată.

IMPORTANT
---------
Nu șterge și nu distribui:
  /storage/emulated/0/Documents/SportBestCam/signing/

Nu regenera cheia dacă GitHub Secret există deja.
GitHub Secrets nu pot fi citite înapoi după salvare.
