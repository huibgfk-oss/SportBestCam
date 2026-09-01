package com.fadcam;

import android.app.Application;
import android.app.ActivityManager;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.lifecycle.Lifecycle;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import androidx.core.content.ContextCompat;
import android.content.ComponentName;

public class FadCamApplication extends Application implements LifecycleObserver {
    private SportBestCamRuntimeCoordinator sportRuntimeCoordinator;
    private final BroadcastReceiver publicArchiveReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            if (Constants.ACTION_RECORDING_COMPLETE.equals(intent.getAction())) {
                // Camera, Screen, Dual and SportShot all publish this event. Give
                // muxer finalization a moment, then archive the completed private
                // media to user-owned MediaStore storage.
                com.fadcam.utils.PublicMediaStoreArchive.scheduleSync(FadCamApplication.this, 4000L);
                com.fadcam.data.BackgroundRecordsDiscovery.schedule(
                        FadCamApplication.this, 6500L, "recording_complete");
            } else if (Constants.ACTION_RECORDING_SEGMENT_COMPLETE.equals(intent.getAction())) {
                com.fadcam.utils.PublicMediaStoreArchive.scheduleSync(FadCamApplication.this, 2500L);
                com.fadcam.data.BackgroundRecordsDiscovery.schedule(
                        FadCamApplication.this, 4500L, "recording_segment_complete");
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        // SportBestCam: on a fresh install the Camera/Screen/Mic detail rail starts
        // collapsed. Existing users keep their last explicit open/closed choice.
        seedFirstRunSportUiDefaults();

        // SportBestCam runtime coordination: keep the screen awake while a recording
        // session is active and trigger additive discovery when Records becomes visible.
        sportRuntimeCoordinator = new SportBestCamRuntimeCoordinator(this);

        // Additive background discovery replaces the old startup index invalidation.
        // It only auto-adds new media from the configured/default SportBestCam folder
        // and never removes manually imported Records entries.
        com.fadcam.data.BackgroundRecordsDiscovery.schedule(this, 2500L, "app_start");

        com.fadcam.utils.BrandStorageMigration.run(this);
        registerPublicArchiveReceiver();
        // Also migrate completed private media left by previous SportBestCam/FadCam builds.
        com.fadcam.utils.PublicMediaStoreArchive.scheduleSync(this, 5000L);
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
        // Room DB open + invalidation observer registration is deferred off the
        // main thread: cold start must not block on SQLite open. The observer
        // still catches post-kill index writes (invocation is on Room's own
        // background invalidation thread either way).
        new Thread(this::registerSelfHealingScanObserver, "selfheal-observer").start();
    }

    private void invalidateRecordsIndexForExternalMediaDiscovery() {
        try {
            com.fadcam.data.VideoIndexRepository.getInstance(this).invalidateIndex();
            FLog.d("FadCamApplication",
                    "Records index invalidated for recursive public/legacy media discovery");
        } catch (Throwable t) {
            // Records can still fall back to its existing DB. Never let an index refresh
            // problem interfere with camera startup.
            FLog.w("FadCamApplication", "Unable to invalidate Records index", t);
        }
    }

    private void seedFirstRunSportUiDefaults() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);
            if (!prefs.contains(Constants.PREF_HOME_CARD_RAIL_FOLDED)) {
                prefs.edit().putBoolean(Constants.PREF_HOME_CARD_RAIL_FOLDED, true).apply();
                FLog.d("FadCamApplication", "First-run Camera/Screen/Mic rail defaulted to collapsed");
            }
        } catch (Throwable t) {
            FLog.w("FadCamApplication", "Unable to seed Sport UI defaults", t);
        }
    }

    /**
     * Native, instant self-healing trigger (issue #332): Room fires this callback
     * the moment ANY row is inserted/updated in the video index — e.g. an
     * abandoned recording being indexed after the process was killed. No polling,
     * no timers: the scan runs exactly when a new file enters the index and only
     * touches rows still marked pending (finalized=0). Single-flight coalescing
     * in the scan itself absorbs bursts.
     */
    private void registerPublicArchiveReceiver() {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Constants.ACTION_RECORDING_COMPLETE);
            filter.addAction(Constants.ACTION_RECORDING_SEGMENT_COMPLETE);
            ContextCompat.registerReceiver(this, publicArchiveReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            FLog.w("FadCamApplication", "Failed to register public media archive receiver", t);
        }
    }

    private void registerSelfHealingScanObserver() {
        try {
            final android.content.Context app = this;
            androidx.room.RoomDatabase db = com.fadcam.data.VideoIndexDatabase.getInstance(this);
            db.getInvalidationTracker().addObserver(new androidx.room.InvalidationTracker.Observer(
                    new String[]{"video_index"}) {
                @Override
                public void onInvalidated(@androidx.annotation.NonNull java.util.Set<String> tables) {
                    // Runs on Room's invalidation thread (background).
                    try {
                        com.fadcam.services.RecordingService.runSelfHealingScan(app, null);
                    } catch (Exception e) {
                        com.fadcam.FLog.w("FadCamApplication", "Self-healing scan trigger failed", e);
                    }
                }
            });
            com.fadcam.FLog.d("FadCamApplication", "Self-healing scan observer registered (video_index)");
        } catch (Exception e) {
            com.fadcam.FLog.w("FadCamApplication", "Failed to register self-healing scan observer", e);
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    public void onAppBackgrounded() {
        // Last safety pass: archive any completed private media produced by
        // editors/mini-apps that may not emit the normal recording broadcast.
        com.fadcam.utils.PublicMediaStoreArchive.scheduleSync(this, 1200L);
        // App is in background, reset AppLock session
        SharedPreferencesManager.getInstance(this).setAppLockSessionUnlocked(false);
        Intent intent = new Intent(this, com.fadcam.services.RecordingService.class);
        intent.setAction("ACTION_APP_BACKGROUND");
        startService(intent);
    }

    /**
     * Called after background discovery inserts media. Refreshes the visible Records
     * tab only; no navigation or UI is forced when Records is not on screen.
     */
    public void refreshRecordsUiIfVisible() {
        if (sportRuntimeCoordinator != null) {
            sportRuntimeCoordinator.refreshRecordsUiIfVisible();
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    public void onAppForegrounded() {
        com.fadcam.data.BackgroundRecordsDiscovery.schedule(this, 900L, "app_foreground");
        // Don't send it for TextEditorActivity, TransparentPermissionActivity, etc.
        // which are transparent/standalone and shouldn't wake up the main app

        // Get the currently focused activity
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null) {
            java.util.List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (!tasks.isEmpty()) {
                ComponentName topActivity = tasks.get(0).topActivity;
                if (topActivity != null) {
                    String activityClassName = topActivity.getClassName();

                    // Only send ACTION_APP_FOREGROUND for MainActivity (camera) or FadRecHomeFragment
                    // Skip for transparent activities like TextEditorActivity, TransparentPermissionActivity
                    boolean isRecordingRelated = activityClassName.contains("MainActivity") ||
                                               activityClassName.contains("FadRecHomeActivity") ||
                                               activityClassName.contains("RecordingActivity");
                    if (isRecordingRelated) {
                        Intent intent = new Intent(this, com.fadcam.services.RecordingService.class);
                        intent.setAction("ACTION_APP_FOREGROUND");
                        startService(intent);
                    }
                    return;
                }
            }
        }

        // Fallback: send the broadcast anyway (error case)
        Intent intent = new Intent(this, com.fadcam.services.RecordingService.class);
        intent.setAction("ACTION_APP_FOREGROUND");
        startService(intent);
    }
}
