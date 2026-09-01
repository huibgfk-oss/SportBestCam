package com.fadcam.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.FadCamApplication;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.data.dao.VideoIndexDao;
import com.fadcam.data.entity.VideoIndexEntity;
import com.fadcam.ui.RecordsFragment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Automatic Records import for the active SportBestCam storage folder.
 *
 * Build 0002.8.7b intentionally mirrors the already proven manual flow:
 * scan the configured/default SAF tree recursively, identify rows not yet in
 * Records, persist those exact URIs as trusted Records entries, insert them
 * into Room and refresh the visible Records UI.
 *
 * No file is moved, renamed or deleted here.
 */
public final class BackgroundRecordsDiscovery {
    private static final String TAG = "BackgroundRecordsDiscovery";
    private static final String PREF_TRUSTED_RECORD_URIS = "records_manual_added_uris";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private BackgroundRecordsDiscovery() {}

    public static void schedule(@NonNull Context context, long delayMs, @NonNull String reason) {
        Context app = context.getApplicationContext();
        MAIN.postDelayed(() -> run(app, reason), Math.max(0L, delayMs));
    }

    private static void run(@NonNull Context appContext, @NonNull String reason) {
        if (!RUNNING.compareAndSet(false, true)) {
            FLog.d(TAG, "Discovery already running; skipped reason=" + reason);
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                long started = System.currentTimeMillis();
                SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(appContext);
                FastFileScanner scanner = new FastFileScanner(appContext);

                // Use the exact same discovery source as Records Options ->
                // Refresh & Recursive Search - All Folders. FastFileScanner.scanAll()
                // includes the configured Storage folder, the granted default
                // Movies/SportBestCam tree, public SportBestCam + legacy FadCam
                // locations and the compatibility trees.
                List<VideoIndexEntity> discovered = scanner.scanAll(prefs);

                VideoIndexDao dao = VideoIndexDatabase.getInstance(appContext).videoIndexDao();
                List<VideoIndexEntity> existing = dao.getAllNewestFirst();
                final int existingBeforeDedupe = existing.size();

                // Android can expose the same physical file through more than one URI
                // (for example MediaStore + SAF). Records identity is therefore:
                // exact URI OR same filename + same byte size.
                existing = dedupeExistingRows(appContext, dao, existing);
                final boolean duplicatesRemoved = existing.size() < existingBeforeDedupe;

                Set<String> existingUris = new HashSet<>();
                Set<String> existingPhysicalKeys = new HashSet<>();
                for (VideoIndexEntity e : existing) {
                    if (e == null) continue;
                    if (e.uriString != null) existingUris.add(e.uriString);
                    existingPhysicalKeys.add(physicalKey(e));
                }

                List<VideoIndexEntity> toInsert = new ArrayList<>();
                for (VideoIndexEntity candidate : discovered) {
                    if (candidate == null || candidate.uriString == null) continue;
                    String key = physicalKey(candidate);
                    if (existingUris.contains(candidate.uriString) || existingPhysicalKeys.contains(key)) continue;
                    toInsert.add(candidate);
                    existingUris.add(candidate.uriString);
                    existingPhysicalKeys.add(key);
                }

                if (!toInsert.isEmpty()) {
                    // Mirror Records Options -> Add selected/Add all. Persist the exact
                    // URIs before the DB refresh so the normal delta scan cannot purge them.
                    persistTrustedUris(appContext, toInsert);
                    dao.insertOrReplaceAll(toInsert);
                    FLog.i(TAG, "Auto-added " + toInsert.size()
                            + " media item(s) using manual Add semantics; reason=" + reason);
                    VideoIndexRepository.getInstance(appContext).startBackgroundEnrichment(null);
                }

                // Refresh also when this pass only removed old duplicate DB rows.
                if (!toInsert.isEmpty() || duplicatesRemoved) {
                    MAIN.post(() -> {
                        try {
                            RecordsFragment.requestRefresh();
                            if (appContext instanceof FadCamApplication) {
                                ((FadCamApplication) appContext).refreshRecordsUiIfVisible();
                            }
                        } catch (Throwable t) {
                            FLog.w(TAG, "Unable to refresh visible Records after dedupe/import", t);
                        }
                    });
                }

                long elapsed = System.currentTimeMillis() - started;
                FLog.i(TAG, "Configured storage discovery complete: found=" + discovered.size()
                        + ", new=" + toInsert.size() + ", reason=" + reason + ", " + elapsed + "ms");
            } catch (Throwable t) {
                FLog.w(TAG, "Configured storage discovery failed; reason=" + reason, t);
            } finally {
                RUNNING.set(false);
            }
        });
    }

    private static void persistTrustedUris(@NonNull Context context,
                                           @NonNull List<VideoIndexEntity> items) {
        SharedPreferences storagePrefs =
                context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> trusted = new HashSet<>();
        Set<String> existing = storagePrefs.getStringSet(PREF_TRUSTED_RECORD_URIS, null);
        if (existing != null) trusted.addAll(existing);
        for (VideoIndexEntity item : items) {
            if (item != null && item.uriString != null && !item.uriString.trim().isEmpty()) {
                trusted.add(item.uriString);
            }
        }
        storagePrefs.edit().putStringSet(PREF_TRUSTED_RECORD_URIS, trusted).apply();
    }

    @NonNull
    private static List<VideoIndexEntity> dedupeExistingRows(
            @NonNull Context context,
            @NonNull VideoIndexDao dao,
            @NonNull List<VideoIndexEntity> existing) {
        Map<String, VideoIndexEntity> unique = new LinkedHashMap<>();
        Set<String> duplicateUris = new HashSet<>();
        Set<String> trusted = new HashSet<>();

        SharedPreferences storagePrefs =
                context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> storedTrusted = storagePrefs.getStringSet(PREF_TRUSTED_RECORD_URIS, null);
        if (storedTrusted != null) trusted.addAll(storedTrusted);

        for (VideoIndexEntity candidate : existing) {
            if (candidate == null || candidate.uriString == null) continue;
            String key = physicalKey(candidate);
            VideoIndexEntity current = unique.get(key);
            if (current == null) {
                unique.put(key, candidate);
                continue;
            }

            VideoIndexEntity keeper = prefer(current, candidate);
            VideoIndexEntity loser = keeper == current ? candidate : current;
            VideoIndexEntity other = keeper == current ? candidate : current;

            mergeCachedMetadata(keeper, other);
            unique.put(key, keeper);
            duplicateUris.add(loser.uriString);

            // If either URI was explicitly trusted through Add/Add all, transfer that
            // trust to the URI we keep and remove the duplicate URI from the persisted set.
            if (trusted.contains(current.uriString) || trusted.contains(candidate.uriString)) {
                trusted.add(keeper.uriString);
            }
            trusted.remove(loser.uriString);
        }

        if (!duplicateUris.isEmpty()) {
            dao.deleteByUris(new ArrayList<>(duplicateUris));
            for (VideoIndexEntity keeper : unique.values()) {
                try {
                    dao.update(keeper);
                } catch (Throwable ignored) {
                    // Metadata merge is best-effort; duplicate removal is the important part.
                }
            }
            storagePrefs.edit().putStringSet(PREF_TRUSTED_RECORD_URIS, trusted).apply();
            FLog.i(TAG, "Removed " + duplicateUris.size()
                    + " duplicate Records row(s) referring to the same physical media");
        }

        return new ArrayList<>(unique.values());
    }

    @NonNull
    private static VideoIndexEntity prefer(@NonNull VideoIndexEntity a,
                                           @NonNull VideoIndexEntity b) {
        int scoreA = rowQuality(a);
        int scoreB = rowQuality(b);
        return scoreB > scoreA ? b : a;
    }

    private static int rowQuality(@NonNull VideoIndexEntity e) {
        int score = 0;
        if (e.durationResolved) score += 8;
        if (e.thumbnailPath != null && !e.thumbnailPath.trim().isEmpty()) score += 4;
        if (e.finalized == 1) score += 2;
        // Prefer the native MediaStore URI when metadata quality is otherwise equal.
        if (e.uriString != null && e.uriString.startsWith("content://media/")) score += 1;
        return score;
    }

    private static void mergeCachedMetadata(@NonNull VideoIndexEntity keeper,
                                            @NonNull VideoIndexEntity other) {
        if (!keeper.durationResolved && other.durationResolved) {
            keeper.durationMs = other.durationMs;
            keeper.durationResolved = true;
        }
        if ((keeper.thumbnailPath == null || keeper.thumbnailPath.trim().isEmpty())
                && other.thumbnailPath != null && !other.thumbnailPath.trim().isEmpty()) {
            keeper.thumbnailPath = other.thumbnailPath;
        }
        if (keeper.finalized != 1 && other.finalized == 1) {
            keeper.finalized = 1;
            keeper.retryAfter = 0L;
        }
    }

    /**
     * Stable identity for the physical media file, independent of the URI provider.
     *
     * SportBestCam filenames contain their own timestamp, so prefer that over provider
     * lastModified values (MediaStore is second-precision while SAF can expose millis).
     * For generic filenames, normalize lastModified to seconds.
     */
    @NonNull
    private static String physicalKey(@NonNull VideoIndexEntity e) {
        String name = e.displayName == null ? "" : e.displayName.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return "uri|" + (e.uriString == null ? "" : e.uriString);
        }
        // Generated SportBestCam filenames are unique. Size protects against the
        // rare case where two folders contain different files with the same name.
        // Do NOT include provider timestamps: SAF and MediaStore report them differently.
        return "file|" + name + "|" + Math.max(0L, e.fileSize);
    }
}
