package com.fadcam.utils;

import android.content.Context;
import android.os.Environment;

import com.fadcam.Constants;
import com.fadcam.FLog;

import java.io.File;

/**
 * One-way best-effort migration from the historical FadCam storage name to SportBestCam.
 * If Android refuses a public-folder rename, the legacy folder is left untouched.
 */
public final class BrandStorageMigration {
    private static final String TAG = "BrandStorageMigration";
    private BrandStorageMigration() {}

    public static void run(Context context) {
        migrateInternal(context);
        migratePublicDownloads();
    }

    private static void migrateInternal(Context context) {
        try {
            File ext = context.getExternalFilesDir(null);
            if (ext == null) return;
            migrate(new File(ext, Constants.LEGACY_RECORDING_DIRECTORY),
                    new File(ext, Constants.RECORDING_DIRECTORY));
        } catch (Exception e) {
            FLog.w(TAG, "Internal legacy-folder migration skipped", e);
        }
    }

    @SuppressWarnings("deprecation")
    private static void migratePublicDownloads() {
        try {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            migrate(new File(downloads, Constants.LEGACY_RECORDING_DIRECTORY),
                    new File(downloads, Constants.RECORDING_DIRECTORY));
        } catch (Exception e) {
            FLog.w(TAG, "Public legacy-folder migration skipped", e);
        }
    }

    private static void migrate(File legacy, File current) {
        if (!legacy.exists() || current.exists()) return;
        boolean ok = legacy.renameTo(current);
        FLog.i(TAG, "Legacy storage rename " + legacy + " -> " + current + ": " + ok);
    }
}
