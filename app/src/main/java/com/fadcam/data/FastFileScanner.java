package com.fadcam.data;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Utils;
import com.fadcam.data.entity.VideoIndexEntity;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Storage scanner used by Records.
 *
 * Build 0002.8.5c:
 * - scans the selected SAF folder recursively;
 * - ALSO keeps scanning the normal public/internal SportBestCam locations;
 * - scans legacy FadCam locations;
 * - scans every MediaStore external volume exposed by Android (including SD card);
 * - recognizes nested/legacy folders such as Movies/SportBestCam/Back;
 * - preserves the original media timestamp so old recordings remain chronologically old;
 * - never moves, renames or deletes media.
 */
public class FastFileScanner {
    private static final String TAG = "FastFileScanner";
    private static final int MAX_RECURSION_DEPTH = 32;
    private static final String PREF_DEFAULT_SPORTBESTCAM_TREE_URI = "records_default_sportbestcam_tree_uri";
    // Files explicitly added from a manual Records search must remain part of the
    // index even when they live outside the configured/default recording folder.
    // Otherwise the repository delta scan sees them as "missing" and removes them
    // about 1.2 seconds after they were added to Records.
    private static final String PREF_MANUAL_ADDED_URIS = "records_manual_added_uris";

    private final Context context;

    public FastFileScanner(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    @NonNull
    public List<VideoIndexEntity> scanAll(@NonNull SharedPreferencesManager prefs) {
        long start = System.currentTimeMillis();
        List<VideoIndexEntity> merged = new ArrayList<>();

        // A custom destination must not hide recordings that still exist in the
        // standard or legacy folders. Scan it first so its URI wins deduplication.
        String safUriString = prefs.getCustomStorageUri();
        if (safUriString != null && !safUriString.trim().isEmpty()) {
            Uri treeUri = Uri.parse(safUriString);
            if (hasPersistedReadPermission(treeUri)) {
                merged.addAll(scanSaf(treeUri));
            } else {
                FLog.w(TAG, "Custom storage URI exists but read permission is missing");
            }
        }

        // The default Movies/SportBestCam tree granted from Records Options is also
        // authoritative. Include it in the normal scan so media auto-added by
        // BackgroundRecordsDiscovery cannot be pruned by the later delta sync.
        String defaultTreeValue = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_DEFAULT_SPORTBESTCAM_TREE_URI, null);
        if (defaultTreeValue != null && !defaultTreeValue.trim().isEmpty()) {
            try {
                Uri defaultTree = Uri.parse(defaultTreeValue);
                boolean sameAsCustom = safUriString != null && !safUriString.trim().isEmpty()
                        && defaultTree.toString().equals(Uri.parse(safUriString).toString());
                if (!sameAsCustom && hasPersistedReadPermission(defaultTree)) {
                    merged.addAll(scanSaf(defaultTree));
                }
            } catch (Exception e) {
                FLog.w(TAG, "Default SportBestCam search tree URI is invalid", e);
            }
        }

        // Public user-owned storage, all MediaStore volumes (primary + SD where exposed).
        merged.addAll(scanPublicMediaStore());

        // Physical public-directory fallback. This finds files that exist on disk but
        // have not yet been indexed correctly by MediaStore when Android permits access.
        merged.addAll(scanPublicFilesystemFallback());

        // App-private current + legacy trees, retained for compatibility/migration.
        merged.addAll(scanInternal());

        // Keep media that the user explicitly added from a manual recursive search.
        // These files may intentionally live outside the active save folder, so they
        // must participate in scanAll() or the repository delta sync will purge them.
        merged.addAll(scanPinnedManualRecords());

        List<VideoIndexEntity> result = dedupePreferFirst(merged);
        long elapsed = System.currentTimeMillis() - start;
        FLog.i(TAG, "Recursive storage scan complete: " + result.size() + " files in " + elapsed + "ms");
        return result;
    }

    /**
     * Background discovery source.
     *
     * The active custom storage folder (Settings -> Storage) is authoritative.
     * When no custom folder is configured, use the persisted SAF permission for
     * Movies/SportBestCam if the user granted it. Otherwise fall back to the
     * public default SportBestCam folders through MediaStore/direct traversal.
     *
     * This intentionally does NOT scan arbitrary diagnostic folders or legacy
     * FadCam trees: background discovery must only auto-add media from the
     * configured/default SportBestCam destination.
     */
    @NonNull
    public List<VideoIndexEntity> scanConfiguredStorage(@NonNull SharedPreferencesManager prefs) {
        long start = System.currentTimeMillis();

        String customUriString = prefs.getCustomStorageUri();
        if (customUriString != null && !customUriString.trim().isEmpty()) {
            try {
                Uri customUri = Uri.parse(customUriString);
                if (hasPersistedReadPermission(customUri)) {
                    List<VideoIndexEntity> custom = dedupePreferFirst(scanSaf(customUri));
                    FLog.i(TAG, "Configured custom storage discovery: " + custom.size()
                            + " files in " + (System.currentTimeMillis() - start) + "ms");
                    return custom;
                }
            } catch (Exception e) {
                FLog.w(TAG, "Configured custom storage URI is invalid", e);
            }
        }

        String defaultTreeValue = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_DEFAULT_SPORTBESTCAM_TREE_URI, null);
        if (defaultTreeValue != null && !defaultTreeValue.trim().isEmpty()) {
            try {
                Uri defaultTree = Uri.parse(defaultTreeValue);
                if (hasPersistedReadPermission(defaultTree)) {
                    List<VideoIndexEntity> granted = dedupePreferFirst(scanSaf(defaultTree));
                    FLog.i(TAG, "Default SportBestCam SAF discovery: " + granted.size()
                            + " files in " + (System.currentTimeMillis() - start) + "ms");
                    return granted;
                }
            } catch (Exception e) {
                FLog.w(TAG, "Default SportBestCam SAF URI is invalid", e);
            }
        }

        List<VideoIndexEntity> merged = new ArrayList<>();
        merged.addAll(scanCurrentSportBestCamMediaStore());
        merged.addAll(scanCurrentPublicFilesystemFallback());
        List<VideoIndexEntity> result = dedupePreferFirst(merged);
        FLog.i(TAG, "Default SportBestCam background discovery: " + result.size()
                + " files in " + (System.currentTimeMillis() - start) + "ms");
        return result;
    }

    /**
     * Explicit SAF tree scan used by Records Options diagnostics.
     * This bypasses storage-mode heuristics and recursively traverses exactly the
     * folder selected by the user via ACTION_OPEN_DOCUMENT_TREE.
     */
    @NonNull
    public List<VideoIndexEntity> scanSelectedTree(@NonNull Uri treeUri) {
        long start = System.currentTimeMillis();
        List<VideoIndexEntity> result = dedupePreferFirst(scanSaf(treeUri));
        long elapsed = System.currentTimeMillis() - start;
        FLog.i(TAG, "Selected SAF tree scan complete: " + result.size() + " files in " + elapsed + "ms; uri=" + treeUri);
        return result;
    }

    /**
     * Rehydrates media explicitly added through Records Options -> Add/Add all.
     *
     * Manual search roots are deliberately NOT part of background auto-import. Once
     * the user explicitly adds a particular file, however, that exact URI becomes a
     * trusted Records item and must survive normal delta scans.
     */
    @NonNull
    private List<VideoIndexEntity> scanPinnedManualRecords() {
        List<VideoIndexEntity> out = new ArrayList<>();
        Set<String> pinned = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(PREF_MANUAL_ADDED_URIS, null);
        if (pinned == null || pinned.isEmpty()) return out;

        for (String value : new HashSet<>(pinned)) {
            if (value == null || value.trim().isEmpty()) continue;
            try {
                Uri uri = Uri.parse(value);
                DocumentFile file = DocumentFile.fromSingleUri(context, uri);
                if (file == null || !file.exists() || !file.isFile()) continue;

                String name = file.getName();
                String mediaType = inferMediaType(name);
                if (mediaType == null || (name != null && name.startsWith("temp_"))) continue;

                VideoIndexEntity entity = buildEntity(
                        uri,
                        name,
                        file.length(),
                        file.lastModified(),
                        Uri.decode(uri.toString()),
                        mediaType);
                if (entity != null) out.add(entity);
            } catch (Throwable t) {
                // Do not let one revoked/broken URI invalidate the rest of Records.
                FLog.w(TAG, "Unable to rehydrate manually added Records URI: " + value, t);
            }
        }

        if (!out.isEmpty()) {
            FLog.i(TAG, "Pinned manual Records scan: " + out.size() + " accessible files");
        }
        return out;
    }

    private boolean hasPersistedReadPermission(@NonNull Uri uri) {
        try {
            for (android.content.UriPermission p : context.getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(uri) && p.isReadPermission()) return true;
            }
        } catch (Exception e) {
            FLog.w(TAG, "Unable to inspect persisted SAF permissions", e);
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // MediaStore: all external volumes + current/legacy paths
    // -------------------------------------------------------------------------

    @NonNull
    private List<VideoIndexEntity> scanCurrentSportBestCamMediaStore() {
        List<VideoIndexEntity> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return out;
        }

        Set<String> volumes = new HashSet<>();
        try {
            volumes.addAll(MediaStore.getExternalVolumeNames(context));
        } catch (Throwable t) {
            FLog.w(TAG, "Could not enumerate MediaStore volumes for configured discovery", t);
        }
        if (volumes.isEmpty()) volumes.add(MediaStore.VOLUME_EXTERNAL_PRIMARY);

        String videoPrefix = Environment.DIRECTORY_MOVIES + "/" + Constants.RECORDING_DIRECTORY + "/";
        String imagePrefix = Environment.DIRECTORY_PICTURES + "/" + Constants.RECORDING_DIRECTORY + "/";

        for (String volume : volumes) {
            scanCurrentMediaStoreCollection(out,
                    MediaStore.Video.Media.getContentUri(volume), "VIDEO", videoPrefix);
            scanCurrentMediaStoreCollection(out,
                    MediaStore.Images.Media.getContentUri(volume), "IMAGE", imagePrefix);
        }
        return dedupePreferFirst(out);
    }

    private void scanCurrentMediaStoreCollection(@NonNull List<VideoIndexEntity> out,
                                                 @NonNull Uri collection,
                                                 @NonNull String mediaType,
                                                 @NonNull String relativePrefix) {
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.RELATIVE_PATH
        };
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
        try (Cursor c = context.getContentResolver().query(
                collection, projection, selection, new String[]{relativePrefix + "%"},
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (c == null) return;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                long size = c.isNull(2) ? 0L : c.getLong(2);
                long modified = c.isNull(3) ? 0L : c.getLong(3) * 1000L;
                String relativePath = c.getString(4);
                if (!isSupportedMediaName(name)) continue;
                Uri uri = ContentUris.withAppendedId(collection, id);
                VideoIndexEntity entity = buildEntity(
                        uri, name, size, modified, relativePath, mediaType);
                if (entity != null) out.add(entity);
            }
        } catch (Exception e) {
            FLog.w(TAG, "Configured MediaStore discovery failed for " + relativePrefix, e);
        }
    }

    @NonNull
    private List<VideoIndexEntity> scanPublicMediaStore() {
        List<VideoIndexEntity> out = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Set<String> volumes = new HashSet<>();
            try {
                volumes.addAll(MediaStore.getExternalVolumeNames(context));
            } catch (Throwable t) {
                FLog.w(TAG, "Could not enumerate MediaStore volumes; using primary", t);
            }
            if (volumes.isEmpty()) volumes.add(MediaStore.VOLUME_EXTERNAL_PRIMARY);

            for (String volume : volumes) {
                try {
                    scanMediaStoreCollection(out, MediaStore.Video.Media.getContentUri(volume), "VIDEO");
                } catch (Throwable t) {
                    FLog.w(TAG, "Video MediaStore scan failed for volume " + volume, t);
                }
                try {
                    scanMediaStoreCollection(out, MediaStore.Images.Media.getContentUri(volume), "IMAGE");
                } catch (Throwable t) {
                    FLog.w(TAG, "Image MediaStore scan failed for volume " + volume, t);
                }
            }
        } else {
            scanLegacyMediaStoreCollection(out, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "VIDEO");
            scanLegacyMediaStoreCollection(out, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "IMAGE");
        }

        return dedupePreferFirst(out);
    }

    private void scanMediaStoreCollection(@NonNull List<VideoIndexEntity> out,
                                          @NonNull Uri collection,
                                          @NonNull String mediaType) {
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.RELATIVE_PATH
        };

        String moviesCurrent = Environment.DIRECTORY_MOVIES + "/" + Constants.RECORDING_DIRECTORY + "/";
        String moviesLegacy = Environment.DIRECTORY_MOVIES + "/" + Constants.LEGACY_RECORDING_DIRECTORY + "/";
        String picturesCurrent = Environment.DIRECTORY_PICTURES + "/" + Constants.RECORDING_DIRECTORY + "/";
        String picturesLegacy = Environment.DIRECTORY_PICTURES + "/" + Constants.LEGACY_RECORDING_DIRECTORY + "/";

        // Path matching is recursive because RELATIVE_PATH LIKE 'root/%' also matches
        // Back/, Front/, Dual/, and any deeper legacy directory.
        String selection = "(" + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? OR "
                + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? OR "
                + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? OR "
                + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? OR "
                + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? OR "
                + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? OR "
                + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? OR "
                + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? OR "
                + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? OR "
                + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? OR "
                + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?)";

        String[] args = {
                moviesCurrent + "%",
                moviesLegacy + "%",
                picturesCurrent + "%",
                picturesLegacy + "%",
                Constants.RECORDING_DIRECTORY + "_%",
                Constants.LEGACY_RECORDING_DIRECTORY + "_%",
                "DualCam_%",
                Constants.RECORDING_FILE_PREFIX_FADREC + "%",
                "Faditor_%",
                "Stream_%",
                Constants.RECORDING_FILE_PREFIX_FADSHOT + "%"
        };

        try (Cursor c = context.getContentResolver().query(collection, projection, selection, args,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (c == null) return;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                long size = c.isNull(2) ? 0L : c.getLong(2);
                long modified = c.isNull(3) ? 0L : c.getLong(3) * 1000L;
                String relativePath = c.getString(4);

                if (!isSupportedMediaName(name)) continue;
                Uri uri = ContentUris.withAppendedId(collection, id);
                VideoIndexEntity e = buildEntity(uri, name, size, modified, relativePath, mediaType);
                if (e != null) out.add(e);
            }
        } catch (Exception e) {
            FLog.w(TAG, "MediaStore recursive scan failed for " + collection, e);
        }
    }

    /** Android 9 and lower do not expose RELATIVE_PATH. */
    private void scanLegacyMediaStoreCollection(@NonNull List<VideoIndexEntity> out,
                                                @NonNull Uri collection,
                                                @NonNull String mediaType) {
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DATA
        };
        try (Cursor c = context.getContentResolver().query(collection, projection, null, null,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (c == null) return;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                long size = c.isNull(2) ? 0L : c.getLong(2);
                long modified = c.isNull(3) ? 0L : c.getLong(3) * 1000L;
                String absolute = c.getString(4);
                if (!isSupportedMediaName(name) || !isSportBestCamOrLegacyPath(absolute, name)) continue;
                Uri uri = ContentUris.withAppendedId(collection, id);
                VideoIndexEntity e = buildEntity(uri, name, size, modified, absolute, mediaType);
                if (e != null) out.add(e);
            }
        } catch (Exception e) {
            FLog.w(TAG, "Legacy MediaStore scan failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Public physical folders: recursive fallback for unindexed files
    // -------------------------------------------------------------------------

    @NonNull
    private List<VideoIndexEntity> scanCurrentPublicFilesystemFallback() {
        List<VideoIndexEntity> out = new ArrayList<>();
        try {
            File movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
            File pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            scanPublicTree(out, new File(movies, Constants.RECORDING_DIRECTORY), "");
            scanPublicTree(out, new File(pictures, Constants.RECORDING_DIRECTORY), "");
        } catch (Throwable t) {
            FLog.d(TAG, "Configured public filesystem fallback unavailable: " + t.getMessage());
        }
        return out;
    }

    @NonNull
    private List<VideoIndexEntity> scanPublicFilesystemFallback() {
        List<VideoIndexEntity> out = new ArrayList<>();
        try {
            File movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
            File pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);

            scanPublicTree(out, new File(movies, Constants.RECORDING_DIRECTORY), "");
            scanPublicTree(out, new File(movies, Constants.LEGACY_RECORDING_DIRECTORY), "");
            scanPublicTree(out, new File(pictures, Constants.RECORDING_DIRECTORY), "");
            scanPublicTree(out, new File(pictures, Constants.LEGACY_RECORDING_DIRECTORY), "");
        } catch (Throwable t) {
            // Scoped storage may intentionally deny direct traversal. MediaStore/SAF
            // scanning above remains the supported path in that case.
            FLog.d(TAG, "Public filesystem fallback unavailable: " + t.getMessage());
        }
        return out;
    }

    private void scanPublicTree(@NonNull List<VideoIndexEntity> out,
                                @Nullable File node,
                                @NonNull String pathHint) {
        scanPublicTree(out, node, pathHint, 0);
    }

    private void scanPublicTree(@NonNull List<VideoIndexEntity> out,
                                @Nullable File node,
                                @NonNull String pathHint,
                                int depth) {
        if (node == null || depth > MAX_RECURSION_DEPTH || !node.exists()) return;
        if (node.isFile()) {
            if (!isSupportedMediaName(node.getName())) return;
            String mediaType = inferMediaType(node.getName());
            String path = pathHint + "/" + node.getParent();
            VideoIndexEntity e = buildEntity(Uri.fromFile(node), node.getName(), node.length(),
                    node.lastModified(), path, mediaType);
            if (e != null) out.add(e);
            return;
        }

        File[] children = node.listFiles();
        if (children == null) return;
        String nextHint = pathHint + "/" + node.getName();
        for (File child : children) {
            if (child != null) scanPublicTree(out, child, nextHint, depth + 1);
        }
    }

    // -------------------------------------------------------------------------
    // App-private current + legacy trees
    // -------------------------------------------------------------------------

    @NonNull
    private List<VideoIndexEntity> scanInternal() {
        List<VideoIndexEntity> out = new ArrayList<>();
        File root = context.getExternalFilesDir(null);
        if (root == null) return out;

        scanPublicTree(out, new File(root, Constants.RECORDING_DIRECTORY), "internal");
        scanPublicTree(out, new File(root, Constants.LEGACY_RECORDING_DIRECTORY), "internal");
        return out;
    }

    // -------------------------------------------------------------------------
    // SAF selected folder: true recursive traversal
    // -------------------------------------------------------------------------

    @NonNull
    private List<VideoIndexEntity> scanSaf(@NonNull Uri treeUri) {
        List<VideoIndexEntity> out = new ArrayList<>();
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
            if (root == null || !root.exists()) return out;
            scanSafRecursive(out, root, root.getName() == null ? "" : root.getName(), 0);
        } catch (Throwable t) {
            FLog.w(TAG, "Recursive SAF scan failed", t);
        }
        return out;
    }

    private void scanSafRecursive(@NonNull List<VideoIndexEntity> out,
                                  @NonNull DocumentFile node,
                                  @NonNull String pathHint,
                                  int depth) {
        if (depth > MAX_RECURSION_DEPTH) return;
        if (node.isFile()) {
            String name = node.getName();
            if (!isSupportedMediaName(name)) return;
            String mediaType = inferMediaType(name);
            VideoIndexEntity e = buildEntity(node.getUri(), name, node.length(), node.lastModified(),
                    pathHint, mediaType);
            if (e != null) out.add(e);
            return;
        }
        if (!node.isDirectory()) return;

        DocumentFile[] children;
        try {
            children = node.listFiles();
        } catch (Throwable t) {
            FLog.w(TAG, "Unable to list SAF directory " + node.getUri(), t);
            return;
        }
        for (DocumentFile child : children) {
            if (child == null) continue;
            String childName = child.getName() == null ? "" : child.getName();
            String childPath = pathHint + "/" + childName;
            scanSafRecursive(out, child, childPath, depth + 1);
        }
    }

    // -------------------------------------------------------------------------
    // Entity/category helpers
    // -------------------------------------------------------------------------

    @Nullable
    private VideoIndexEntity buildEntity(@NonNull Uri uri,
                                         @Nullable String name,
                                         long size,
                                         long modified,
                                         @Nullable String path,
                                         @Nullable String mediaType) {
        if (name == null || mediaType == null || name.startsWith("temp_")) return null;

        VideoIndexEntity e = new VideoIndexEntity();
        e.uriString = uri.toString();
        e.displayName = name;
        e.fileSize = Math.max(0L, size);

        long fromName = Utils.parseTimestampFromFilename(name);
        e.lastModified = modified > 0L ? modified : (fromName > 0L ? fromName : System.currentTimeMillis());
        e.indexedAt = System.currentTimeMillis();
        e.mediaType = mediaType;
        e.isTemporary = false;
        e.durationResolved = false;

        String category = inferCategory(path, name);
        e.category = category;
        e.shotSubtype = "SHOT".equals(category) ? inferShotSubtype(path, name) : "UNKNOWN";
        e.cameraSubtype = "CAMERA".equals(category) ? inferCameraSubtype(path, name) : "UNKNOWN";
        e.faditorSubtype = "FADITOR".equals(category) ? inferFaditorSubtype(path, name) : "UNKNOWN";
        return e;
    }

    @Nullable
    private String inferMediaType(@Nullable String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase(Locale.ROOT);
        String expectedExt = "." + Constants.RECORDING_FILE_EXTENSION.toLowerCase(Locale.ROOT);
        if (lower.endsWith(expectedExt) || lower.endsWith(".mp4") || lower.endsWith(".mkv")
                || lower.endsWith(".webm") || lower.endsWith(".mov")) return "VIDEO";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp")) return "IMAGE";
        return null;
    }

    private boolean isSupportedMediaName(@Nullable String name) {
        return inferMediaType(name) != null && (name == null || !name.startsWith("temp_"));
    }

    private boolean isSportBestCamOrLegacyPath(@Nullable String path, @Nullable String name) {
        String p = safeLower(path);
        String n = safeLower(name);
        String current = Constants.RECORDING_DIRECTORY.toLowerCase(Locale.ROOT);
        String legacy = Constants.LEGACY_RECORDING_DIRECTORY.toLowerCase(Locale.ROOT);
        return p.contains("/" + current + "/") || p.contains("/" + legacy + "/")
                || n.startsWith(current.toLowerCase(Locale.ROOT) + "_")
                || n.startsWith(legacy.toLowerCase(Locale.ROOT) + "_")
                || n.startsWith("dualcam_") || n.startsWith("fadrec_")
                || n.startsWith("faditor_") || n.startsWith("stream_")
                || n.startsWith("fadshot_");
    }

    @NonNull
    private String inferCategory(@Nullable String path, @Nullable String name) {
        String p = "/" + safeLower(path) + "/";
        String n = safeLower(name);

        if (containsFolder(p, Constants.RECORDING_SUBDIR_SCREEN) || n.startsWith("fadrec_")) return "SCREEN";
        if (containsFolder(p, Constants.RECORDING_SUBDIR_FADITOR) || n.startsWith("faditor_")) return "FADITOR";
        if (containsFolder(p, Constants.RECORDING_SUBDIR_STREAM) || n.startsWith("stream_")) return "STREAM";
        if (containsFolder(p, Constants.RECORDING_SUBDIR_SHOT) || n.startsWith("fadshot_")) return "SHOT";
        if (containsFolder(p, Constants.RECORDING_SUBDIR_MINIAPPS)) return "MINIAPPS";

        // Current structure can be Camera/Back, but several older/public builds
        // stored recordings directly in SportBestCam/Back, /Front or /Dual.
        if (containsFolder(p, Constants.RECORDING_SUBDIR_CAMERA)
                || containsFolder(p, Constants.RECORDING_SUBDIR_DUAL)
                || containsFolder(p, Constants.RECORDING_SUBDIR_CAMERA_BACK)
                || containsFolder(p, Constants.RECORDING_SUBDIR_CAMERA_FRONT)
                || containsFolder(p, Constants.RECORDING_SUBDIR_CAMERA_DUAL)
                || n.startsWith(Constants.RECORDING_DIRECTORY.toLowerCase(Locale.ROOT) + "_")
                || n.startsWith(Constants.LEGACY_RECORDING_DIRECTORY.toLowerCase(Locale.ROOT) + "_")
                || n.startsWith("dualcam_")) return "CAMERA";

        return "UNKNOWN";
    }

    @NonNull
    private String inferCameraSubtype(@Nullable String path, @Nullable String name) {
        String p = "/" + safeLower(path) + "/";
        String n = safeLower(name);
        if (containsFolder(p, Constants.RECORDING_SUBDIR_CAMERA_DUAL)
                || containsFolder(p, Constants.RECORDING_SUBDIR_DUAL)
                || n.startsWith("dualcam_")) return "DUAL";
        if (containsFolder(p, Constants.RECORDING_SUBDIR_CAMERA_FRONT)) return "FRONT";
        if (containsFolder(p, Constants.RECORDING_SUBDIR_CAMERA_BACK)) return "BACK";
        return "BACK";
    }

    @NonNull
    private String inferShotSubtype(@Nullable String path, @Nullable String name) {
        String p = "/" + safeLower(path) + "/";
        String n = safeLower(name);
        if (containsFolder(p, Constants.RECORDING_SUBDIR_SHOT_SELFIE) || n.startsWith("fadshot_selfie_")) return "SELFIE";
        if (containsFolder(p, Constants.RECORDING_SUBDIR_SHOT_FADREC) || n.startsWith("fadshot_fadrec_")) return "FADREC";
        if (containsFolder(p, Constants.RECORDING_SUBDIR_SHOT_BACK) || n.startsWith("fadshot_back_")) return "BACK";
        return "BACK";
    }

    @NonNull
    private String inferFaditorSubtype(@Nullable String path, @Nullable String name) {
        String p = "/" + safeLower(path) + "/";
        String n = safeLower(name);
        if (containsFolder(p, Constants.RECORDING_SUBDIR_FADITOR_CONVERTED)
                || n.startsWith(Constants.RECORDING_FILE_PREFIX_FADITOR_STANDARD.toLowerCase(Locale.ROOT))) return "CONVERTED";
        if (containsFolder(p, Constants.RECORDING_SUBDIR_FADITOR_MERGE)
                || n.startsWith(Constants.RECORDING_FILE_PREFIX_FADITOR_MERGE.toLowerCase(Locale.ROOT))) return "MERGE";
        return "OTHER";
    }

    private boolean containsFolder(@NonNull String normalizedPath, @Nullable String folder) {
        if (folder == null || folder.isEmpty()) return false;
        String f = folder.toLowerCase(Locale.ROOT);
        return normalizedPath.contains("/" + f + "/");
    }

    @NonNull
    private String safeLower(@Nullable String value) {
        return value == null ? "" : value.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    @NonNull
    private List<VideoIndexEntity> dedupePreferFirst(@NonNull List<VideoIndexEntity> source) {
        Map<String, VideoIndexEntity> unique = new LinkedHashMap<>();
        for (VideoIndexEntity e : source) {
            if (e == null || e.uriString == null) continue;

            // Deduplicate the same physical media discovered through SAF, MediaStore
            // and file fallback while preserving two genuinely different files.
            String name = e.displayName == null ? "" : e.displayName.toLowerCase(Locale.ROOT);
            String physicalKey = name + "|" + e.fileSize;
            if (!unique.containsKey(physicalKey)) unique.put(physicalKey, e);
        }
        return new ArrayList<>(unique.values());
    }
}
