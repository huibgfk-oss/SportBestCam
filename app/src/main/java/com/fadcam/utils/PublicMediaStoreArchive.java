package com.fadcam.utils;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mirrors completed app-private SportBestCam media into public MediaStore folders.
 *
 * Default recordings used to live under Android/data/<package>/files and therefore
 * disappeared when Android uninstalled the app.  This archiver copies completed
 * media to Movies/SportBestCam (videos) or Pictures/SportBestCam (images) and only
 * deletes the app-private source after a byte-for-byte copy succeeds.
 *
 * Custom SAF storage is already user-owned and is not touched by this class.
 */
public final class PublicMediaStoreArchive {
    private static final String TAG = "PublicMediaArchive";
    private static final long MIN_COMPLETED_AGE_MS = 2200L;
    private static final ScheduledExecutorService EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sbc-public-media-archive");
                t.setDaemon(true);
                return t;
            });
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private PublicMediaStoreArchive() {}

    public static void scheduleSync(@NonNull Context context) {
        scheduleSync(context, 0L);
    }

    public static void scheduleSync(@NonNull Context context, long delayMs) {
        final Context app = context.getApplicationContext();
        EXECUTOR.schedule(() -> syncNow(app), Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
    }

    private static void syncNow(@NonNull Context context) {
        if (!RUNNING.compareAndSet(false, true)) return;
        int exported = 0;
        try {
            File external = context.getExternalFilesDir(null);
            if (external == null) return;

            List<File> roots = new ArrayList<>();
            File current = new File(external, Constants.RECORDING_DIRECTORY);
            File legacy = new File(external, Constants.LEGACY_RECORDING_DIRECTORY);
            if (current.isDirectory()) roots.add(current);
            if (legacy.isDirectory() && !legacy.equals(current)) roots.add(legacy);

            SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
            boolean videoRecordingActive = prefs != null
                    && (prefs.isRecordingInProgress() || prefs.isScreenRecordingInProgress());

            for (File root : roots) {
                exported += syncDirectory(context, root, root, videoRecordingActive);
                pruneEmptyDirectories(root, root);
            }
        } catch (Throwable t) {
            FLog.w(TAG, "Public archive sync failed safely", t);
        } finally {
            RUNNING.set(false);
        }

        if (exported > 0) {
            FLog.i(TAG, "Archived " + exported + " completed media file(s) to public storage");
            try {
                context.sendBroadcast(new Intent(Constants.ACTION_STORAGE_LOCATION_CHANGED));
            } catch (Throwable ignored) {}
        }
    }

    private static int syncDirectory(Context context, File root, File dir, boolean videoRecordingActive) {
        File[] children = dir.listFiles();
        if (children == null) return 0;
        int exported = 0;
        for (File child : children) {
            if (child == null) continue;
            if (child.isDirectory()) {
                exported += syncDirectory(context, root, child, videoRecordingActive);
            } else if (child.isFile() && isArchivableMedia(child, videoRecordingActive)) {
                Uri publicUri = exportOne(context, root, child);
                if (publicUri != null) exported++;
            }
        }
        return exported;
    }

    private static boolean isArchivableMedia(File file, boolean videoRecordingActive) {
        if (!file.exists() || !file.isFile() || file.length() <= 0L) return false;
        String n = file.getName().toLowerCase(Locale.US);
        if (n.startsWith("temp_") || n.startsWith("stream_temp_") || n.endsWith(".tmp")) return false;
        boolean video = n.endsWith(".mp4");
        boolean media = video || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".png") || n.endsWith(".webp");
        if (!media) return false;
        // Never move any MP4 while Camera/Dual/Screen recording is active. A
        // paused recording can have an old mtime even though the encoder still
        // owns the file descriptor.
        if (video && videoRecordingActive) return false;
        // Never grab a file that may still be receiving encoder writes.
        return System.currentTimeMillis() - file.lastModified() >= MIN_COMPLETED_AGE_MS;
    }

    @Nullable
    private static Uri exportOne(Context context, File privateRoot, File source) {
        String lower = source.getName().toLowerCase(Locale.US);
        boolean video = lower.endsWith(".mp4");
        String mime = video ? "video/mp4" : mimeForImage(lower);
        String relativeSubdir = relativeParent(privateRoot, source.getParentFile());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String top = video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
            String relativePath = top + "/" + Constants.RECORDING_DIRECTORY
                    + (relativeSubdir.isEmpty() ? "" : "/" + relativeSubdir) + "/";
            Uri collection = video
                    ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

            Uri existing = findExisting(context.getContentResolver(), collection,
                    source.getName(), relativePath, source.length());
            if (existing != null) {
                // A previous run completed the public copy but process death happened
                // before private cleanup. The public copy wins.
                if (!source.delete()) {
                    FLog.w(TAG, "Public copy exists but private duplicate could not be deleted: " + source);
                }
                return existing;
            }

            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, source.getName());
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            cv.put(MediaStore.MediaColumns.DATE_MODIFIED, Math.max(1L, source.lastModified() / 1000L));

            ContentResolver cr = context.getContentResolver();
            Uri uri = null;
            try {
                uri = cr.insert(collection, cv);
                if (uri == null) return null;
                long copied;
                try (InputStream in = new FileInputStream(source);
                     OutputStream out = cr.openOutputStream(uri, "w")) {
                    if (out == null) throw new IllegalStateException("MediaStore output stream is null");
                    copied = copy(in, out);
                    out.flush();
                }
                if (copied != source.length()) {
                    FLog.w(TAG, "Public copy size mismatch for " + source.getName()
                            + ": source=" + source.length() + " copied=" + copied);
                    cr.delete(uri, null, null);
                    return null;
                }
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, ready, null, null);
                if (!source.delete()) {
                    FLog.w(TAG, "Archived successfully but private source remains: " + source);
                }
                return uri;
            } catch (Throwable t) {
                FLog.w(TAG, "Failed to archive " + source.getName(), t);
                if (uri != null) {
                    try { cr.delete(uri, null, null); } catch (Throwable ignored) {}
                }
                return null;
            }
        }

        // Android 7-9 fallback. WRITE_EXTERNAL_STORAGE is already part of the
        // legacy permission model used by this project.
        File publicTop = Environment.getExternalStoragePublicDirectory(
                video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES);
        File destDir = new File(publicTop, Constants.RECORDING_DIRECTORY
                + (relativeSubdir.isEmpty() ? "" : File.separator + relativeSubdir));
        if (!destDir.exists() && !destDir.mkdirs()) return null;
        File dest = new File(destDir, source.getName());
        try {
            if (!dest.exists() || dest.length() != source.length()) {
                try (InputStream in = new FileInputStream(source);
                     OutputStream out = new FileOutputStream(dest)) {
                    long copied = copy(in, out);
                    out.flush();
                    if (copied != source.length()) {
                        //noinspection ResultOfMethodCallIgnored
                        dest.delete();
                        return null;
                    }
                }
            }
            Utils.scanFileWithMediaStore(context, dest.getAbsolutePath());
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            return Uri.fromFile(dest);
        } catch (Throwable t) {
            FLog.w(TAG, "Legacy public archive failed: " + source, t);
            return null;
        }
    }

    @Nullable
    private static Uri findExisting(ContentResolver cr, Uri collection, String displayName,
                                    String relativePath, long expectedSize) {
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.SIZE
        };
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        try (Cursor c = cr.query(collection, projection, selection,
                new String[]{displayName, relativePath}, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                long size = c.isNull(1) ? -1L : c.getLong(1);
                if (size == expectedSize) {
                    return ContentUris.withAppendedId(collection, c.getLong(0));
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String relativeParent(File root, @Nullable File parent) {
        if (parent == null) return "";
        try {
            String rootPath = root.getCanonicalPath();
            String parentPath = parent.getCanonicalPath();
            if (!parentPath.startsWith(rootPath)) return "";
            String rel = parentPath.substring(rootPath.length());
            while (rel.startsWith(File.separator)) rel = rel.substring(1);
            return rel.replace(File.separatorChar, '/');
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String mimeForImage(String lower) {
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private static long copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[1024 * 256];
        long total = 0L;
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n == 0) continue;
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    private static void pruneEmptyDirectories(File root, File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child != null && child.isDirectory()) pruneEmptyDirectories(root, child);
            }
        }
        if (!dir.equals(root)) {
            File[] left = dir.listFiles();
            if (left != null && left.length == 0) {
                //noinspection ResultOfMethodCallIgnored
                dir.delete();
            }
        }
    }
}
