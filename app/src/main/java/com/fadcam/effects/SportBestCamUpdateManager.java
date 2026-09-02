package com.fadcam.effects;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

public final class SportBestCamUpdateManager {
    private static final String API =
            "https://api.github.com/repos/huibgfk-oss/SportBestCam/releases?per_page=20";
    private static final String TAG_PREFIX = "android-v";
    private static final String PREFS = "sportbestcam_updater";
    private static final String LAST_CHECK = "last_check_ms";
    private static final String PENDING_URL = "pending_url";
    private static final String PENDING_NAME = "pending_name";
    private static final String PENDING_CODE = "pending_code";
    private static final long AUTO_INTERVAL_MS = 12L * 60L * 60L * 1000L;

    private static volatile boolean checking = false;
    private static volatile boolean downloading = false;

    private SportBestCamUpdateManager() {}

    public static void onActivityResumed(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        SharedPreferences prefs = activity.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );

        String pendingUrl = prefs.getString(PENDING_URL, "");
        if (!pendingUrl.isEmpty() && canInstallPackages(activity)) {
            String pendingName = prefs.getString(
                    PENDING_NAME,
                    "SportBestCam update"
            );
            long pendingCode = prefs.getLong(PENDING_CODE, 0L);
            prefs.edit()
                    .remove(PENDING_URL)
                    .remove(PENDING_NAME)
                    .remove(PENDING_CODE)
                    .apply();
            downloadAndInstall(
                    activity,
                    pendingUrl,
                    pendingName,
                    pendingCode
            );
            return;
        }

        boolean autoUpdateEnabled =
                com.fadcam.SharedPreferencesManager.getInstance(activity)
                        .sharedPreferences
                        .getBoolean(
                                com.fadcam.SharedPreferencesManager.PREF_AUTO_UPDATE_CHECK,
                                true
                        );
        if (!autoUpdateEnabled) return;

        long now = System.currentTimeMillis();
        long last = prefs.getLong(LAST_CHECK, 0L);

        if (now - last >= AUTO_INTERVAL_MS) {
            prefs.edit().putLong(LAST_CHECK, now).apply();
            check(activity, false);
        }
    }

    public static void checkNow(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        Toast.makeText(
                activity,
                "Verific actualizările SportBestCam...",
                Toast.LENGTH_SHORT
        ).show();
        check(activity, true);
    }

    private static void check(Activity activity, boolean manual) {
        if (checking) {
            if (manual) {
                Toast.makeText(
                        activity,
                        "Verificarea este deja în curs.",
                        Toast.LENGTH_SHORT
                ).show();
            }
            return;
        }

        checking = true;

        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                URL url = new URL(API);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(20000);
                connection.setRequestProperty(
                        "User-Agent",
                        "SportBestCam-Android-Updater"
                );
                connection.setRequestProperty(
                        "Accept",
                        "application/vnd.github+json"
                );

                int response = connection.getResponseCode();
                if (response < 200 || response >= 300) {
                    throw new IllegalStateException(
                            "GitHub HTTP " + response
                    );
                }

                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream())
                )) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line);
                    }
                }

                long installedCode = installedVersionCode(activity);
                JSONArray releases = new JSONArray(body.toString());

                long bestCode = installedCode;
                String bestUrl = "";
                String bestName = "";

                for (int i = 0; i < releases.length(); i++) {
                    JSONObject release = releases.getJSONObject(i);

                    if (release.optBoolean("draft", false)
                            || release.optBoolean("prerelease", false)) {
                        continue;
                    }

                    String tag = release.optString("tag_name", "");
                    if (!tag.startsWith(TAG_PREFIX)) continue;

                    long code = parseReleaseCode(tag);
                    if (code <= bestCode) continue;

                    JSONArray assets = release.optJSONArray("assets");
                    if (assets == null) continue;

                    String assetUrl = chooseBestApk(assets);
                    if (assetUrl.isEmpty()) continue;

                    bestCode = code;
                    bestUrl = assetUrl;
                    bestName = release.optString(
                            "name",
                            "SportBestCam build " + code
                    );
                }

                final long updateCode = bestCode;
                final String updateUrl = bestUrl;
                final String updateName = bestName;

                activity.runOnUiThread(() -> {
                    checking = false;

                    if (updateUrl.isEmpty()) {
                        if (manual) {
                            Toast.makeText(
                                    activity,
                                    "Ai deja cea mai nouă versiune SportBestCam.",
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                        return;
                    }

                    showUpdateDialog(
                            activity,
                            updateName,
                            updateCode,
                            updateUrl
                    );
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    checking = false;
                    if (manual) {
                        Toast.makeText(
                                activity,
                                "Nu pot verifica update-ul: " + e.getMessage(),
                                Toast.LENGTH_LONG
                        ).show();
                    }
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "SportBestCam-UpdateCheck").start();
    }

    private static String chooseBestApk(JSONArray assets) {
        String abi = Build.SUPPORTED_ABIS != null
                && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0].toLowerCase(Locale.ROOT)
                : "";

        String preferred = abi.contains("arm64")
                ? "arm64-v8a"
                : abi.contains("armeabi")
                        ? "armeabi-v7a"
                        : "";

        String universal = "";
        String anyApk = "";

        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;

            String name = asset.optString("name", "");
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".apk")) continue;

            String url = asset.optString(
                    "browser_download_url",
                    ""
            );
            if (url.isEmpty()) continue;

            if (!preferred.isEmpty() && lower.contains(preferred)) {
                return url;
            }

            if (lower.contains("universal")) universal = url;
            if (anyApk.isEmpty()) anyApk = url;
        }

        return !universal.isEmpty() ? universal : anyApk;
    }

    private static long parseReleaseCode(String tag) {
        try {
            return Long.parseLong(
                    tag.substring(TAG_PREFIX.length()).trim()
            );
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static void showUpdateDialog(
            Activity activity,
            String name,
            long code,
            String url
    ) {
        new AlertDialog.Builder(activity)
                .setTitle("SportBestCam update")
                .setMessage(
                        name
                                + "\n\nBuild " + code
                                + " este disponibil."
                                + "\n\nAPK-ul va fi verificat înainte de instalare:"
                                + "\n• același package"
                                + "\n• versionCode mai mare"
                                + "\n• aceeași semnătură APK"
                )
                .setNegativeButton("Mai târziu", null)
                .setPositiveButton(
                        "Actualizează",
                        (dialog, which) -> prepareInstall(
                                activity,
                                url,
                                name,
                                code
                        )
                )
                .show();
    }

    private static void prepareInstall(
            Activity activity,
            String url,
            String name,
            long code
    ) {
        if (!canInstallPackages(activity)) {
            SharedPreferences prefs = activity.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
            );
            prefs.edit()
                    .putString(PENDING_URL, url)
                    .putString(PENDING_NAME, name)
                    .putLong(PENDING_CODE, code)
                    .apply();

            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())
                );
                activity.startActivity(intent);

                Toast.makeText(
                        activity,
                        "Permite o singură dată instalarea update-urilor SportBestCam.",
                        Toast.LENGTH_LONG
                ).show();
            } catch (Exception e) {
                Toast.makeText(
                        activity,
                        "Nu pot deschide permisiunea de update.",
                        Toast.LENGTH_LONG
                ).show();
            }
            return;
        }

        downloadAndInstall(activity, url, name, code);
    }

    private static boolean canInstallPackages(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return activity.getPackageManager()
                    .canRequestPackageInstalls();
        }
        return true;
    }

    private static void downloadAndInstall(
            Activity activity,
            String url,
            String name,
            long code
    ) {
        if (downloading) {
            Toast.makeText(
                    activity,
                    "Un update este deja în curs de descărcare.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        downloading = true;

        try {
            File dir = activity.getExternalFilesDir(
                    Environment.DIRECTORY_DOWNLOADS
            );
            if (dir == null) {
                throw new IllegalStateException(
                        "Folderul pentru update nu este disponibil."
                );
            }

            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException(
                        "Nu pot crea folderul de update."
                );
            }

            File target = new File(
                    dir,
                    "SportBestCam-update-" + code + ".apk"
            );
            if (target.exists()) target.delete();

            DownloadManager.Request request =
                    new DownloadManager.Request(Uri.parse(url));
            request.setTitle(name);
            request.setDescription("SportBestCam update");
            request.setMimeType(
                    "application/vnd.android.package-archive"
            );
            request.setNotificationVisibility(
                    DownloadManager.Request
                            .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            );
            request.setDestinationInExternalFilesDir(
                    activity,
                    Environment.DIRECTORY_DOWNLOADS,
                    target.getName()
            );

            DownloadManager manager = (DownloadManager)
                    activity.getSystemService(
                            Context.DOWNLOAD_SERVICE
                    );

            long id = manager.enqueue(request);

            Toast.makeText(
                    activity,
                    "Descarc update-ul...",
                    Toast.LENGTH_LONG
            ).show();

            pollDownload(
                    activity,
                    manager,
                    id,
                    target
            );
        } catch (Exception e) {
            downloading = false;
            Toast.makeText(
                    activity,
                    "Nu pot descărca update-ul: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private static void pollDownload(
            Activity activity,
            DownloadManager manager,
            long id,
            File target
    ) {
        new Thread(() -> {
            DownloadManager.Query query =
                    new DownloadManager.Query().setFilterById(id);

            for (int i = 0; i < 900; i++) {
                try (android.database.Cursor cursor =
                             manager.query(query)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int status = cursor.getInt(
                                cursor.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_STATUS
                                )
                        );

                        if (status
                                == DownloadManager.STATUS_SUCCESSFUL) {
                            activity.runOnUiThread(() -> {
                                downloading = false;
                                verifyAndInstall(activity, target);
                            });
                            return;
                        }

                        if (status
                                == DownloadManager.STATUS_FAILED) {
                            int reason = cursor.getInt(
                                    cursor.getColumnIndexOrThrow(
                                            DownloadManager.COLUMN_REASON
                                    )
                            );
                            throw new IllegalStateException(
                                    "DownloadManager " + reason
                            );
                        }
                    }

                    Thread.sleep(1000L);
                } catch (Exception e) {
                    activity.runOnUiThread(() -> {
                        downloading = false;
                        Toast.makeText(
                                activity,
                                "Update nereușit: " + e.getMessage(),
                                Toast.LENGTH_LONG
                        ).show();
                    });
                    return;
                }
            }

            activity.runOnUiThread(() -> {
                downloading = false;
                Toast.makeText(
                        activity,
                        "Download-ul update-ului a durat prea mult.",
                        Toast.LENGTH_LONG
                ).show();
            });
        }, "SportBestCam-UpdateDownload").start();
    }

    private static void verifyAndInstall(
            Activity activity,
            File apk
    ) {
        try {
            PackageManager pm = activity.getPackageManager();

            PackageInfo archive = packageArchiveInfo(pm, apk);
            PackageInfo current = installedPackageInfo(
                    pm,
                    activity.getPackageName()
            );

            if (archive == null) {
                throw new IllegalStateException(
                        "APK-ul descărcat nu poate fi citit."
                );
            }

            if (!activity.getPackageName().equals(
                    archive.packageName
            )) {
                throw new IllegalStateException(
                        "Package diferit: " + archive.packageName
                );
            }

            long archiveCode = packageVersionCode(archive);
            long currentCode = packageVersionCode(current);

            if (archiveCode <= currentCode) {
                throw new IllegalStateException(
                        "APK-ul nu este mai nou. "
                                + archiveCode + " <= " + currentCode
                );
            }

            String currentCert = signingDigest(current);
            String archiveCert = signingDigest(archive);

            if (currentCert.isEmpty()
                    || archiveCert.isEmpty()
                    || !currentCert.equals(archiveCert)) {
                showSignatureMismatch(
                        activity,
                        currentCert,
                        archiveCert
                );
                return;
            }

            Uri apkUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".provider",
                    apk
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(
                    apkUri,
                    "application/vnd.android.package-archive"
            );
            intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(
                    activity,
                    "APK update invalid: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private static void showSignatureMismatch(
            Activity activity,
            String installed,
            String downloaded
    ) {
        String shortInstalled = shortDigest(installed);
        String shortDownloaded = shortDigest(downloaded);

        new AlertDialog.Builder(activity)
                .setTitle("Semnătură APK diferită")
                .setMessage(
                        "Android nu poate face update peste instalarea actuală."
                                + "\n\nInstalat: " + shortInstalled
                                + "\nNou: " + shortDownloaded
                                + "\n\nAceasta identifică exact problema de signing."
                                + " Dacă APK-ul nou folosește cheia stabilă SportBestCam,"
                                + " instalarea actuală provine încă din cheia veche și"
                                + " necesită ultima reinstalare."
                )
                .setPositiveButton("OK", null)
                .show();
    }

    private static String shortDigest(String value) {
        if (value == null || value.isEmpty()) return "necunoscut";
        return value.length() <= 23
                ? value
                : value.substring(0, 23) + "...";
    }

    private static PackageInfo installedPackageInfo(
            PackageManager pm,
            String packageName
    ) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
            );
        }
        return pm.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNATURES
        );
    }

    private static PackageInfo packageArchiveInfo(
            PackageManager pm,
            File apk
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return pm.getPackageArchiveInfo(
                    apk.getAbsolutePath(),
                    PackageManager.GET_SIGNING_CERTIFICATES
            );
        }
        return pm.getPackageArchiveInfo(
                apk.getAbsolutePath(),
                PackageManager.GET_SIGNATURES
        );
    }

    private static long packageVersionCode(PackageInfo info) {
        if (info == null) return 0L;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return info.getLongVersionCode();
        }

        //noinspection deprecation
        return info.versionCode;
    }

    private static String signingDigest(PackageInfo info)
            throws Exception {
        if (info == null) return "";

        Signature[] signatures;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (info.signingInfo == null) return "";
            signatures = info.signingInfo.getApkContentsSigners();
        } else {
            //noinspection deprecation
            signatures = info.signatures;
        }

        if (signatures == null || signatures.length == 0) return "";

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(
                signatures[0].toByteArray()
        );

        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            if (out.length() > 0) out.append(':');
            out.append(
                    String.format(
                            Locale.ROOT,
                            "%02X",
                            b & 0xff
                    )
            );
        }
        return out.toString();
    }

    private static long installedVersionCode(Activity activity) {
        try {
            return packageVersionCode(
                    installedPackageInfo(
                            activity.getPackageManager(),
                            activity.getPackageName()
                    )
            );
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
