package com.fadcam;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.fadcam.data.BackgroundRecordsDiscovery;
import com.fadcam.ui.RecordsAutoAccessFragment;
import com.fadcam.ui.RecordsFragment;

import java.lang.ref.WeakReference;

/**
 * App-wide SportBestCam runtime behavior.
 *
 * Build 0002.8.7b:
 * - keeps the visible window awake while recording is active;
 * - detects the real Camera -> Records tab transition even when Records is only
 *   un-hidden and therefore does not receive another onFragmentResumed callback;
 * - on Records entry, asks once for SAF access when needed and then runs the
 *   automatic recursive discovery/import pipeline.
 */
public final class SportBestCamRuntimeCoordinator implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "SportBestCamRuntime";
    private static final long RECORDS_VISIBILITY_POLL_MS = 350L;

    private final Application app;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private volatile boolean recordingSessionActive = false;
    private boolean recordsWasVisible = false;
    private boolean recordsPollScheduled = false;

    private final FragmentManager.FragmentLifecycleCallbacks fragmentCallbacks =
            new FragmentManager.FragmentLifecycleCallbacks() {
                @Override
                public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment f) {
                    if (f instanceof RecordsFragment) {
                        handleRecordsBecameVisible("records_fragment_resumed");
                    }
                }
            };

    /**
     * MainActivity uses hide/show navigation, so onFragmentResumed alone is not a
     * reliable Records-entry signal. Polling only the cheap current-tab integer while
     * MainActivity is resumed catches the hidden -> visible transition deterministically.
     */
    private final Runnable recordsVisibilityPoll = new Runnable() {
        @Override
        public void run() {
            recordsPollScheduled = false;
            Activity activity = resumedActivity.get();
            if (activity == null || activity.isFinishing()) return;

            boolean recordsVisible = false;
            if (activity instanceof MainActivity) {
                try {
                    recordsVisible = ((MainActivity) activity).getCurrentFragmentPosition() == 1;
                } catch (Throwable t) {
                    FLog.w(TAG, "Unable to read current MainActivity tab", t);
                }
            }

            if (recordsVisible && !recordsWasVisible) {
                recordsWasVisible = true;
                handleRecordsBecameVisible("records_tab_entered");
            } else if (!recordsVisible) {
                recordsWasVisible = false;
            }

            scheduleRecordsVisibilityPoll();
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();

            if (Constants.BROADCAST_ON_RECORDING_STARTED.equals(action)
                    || Constants.BROADCAST_ON_RECORDING_RESUMED.equals(action)
                    || Constants.BROADCAST_ON_RECORDING_PAUSED.equals(action)
                    || Constants.BROADCAST_ON_SCREEN_RECORDING_STARTED.equals(action)
                    || Constants.BROADCAST_ON_SCREEN_RECORDING_RESUMED.equals(action)
                    || Constants.BROADCAST_ON_SCREEN_RECORDING_PAUSED.equals(action)
                    || Constants.BROADCAST_ON_DUAL_RECORDING_STARTED.equals(action)
                    || Constants.BROADCAST_ON_DUAL_RECORDING_RESUMED.equals(action)
                    || Constants.BROADCAST_ON_DUAL_RECORDING_PAUSED.equals(action)) {
                recordingSessionActive = true;
                applyKeepScreenOn(true);
                return;
            }

            if (Constants.BROADCAST_ON_RECORDING_STOPPED.equals(action)
                    || Constants.BROADCAST_ON_SCREEN_RECORDING_STOPPED.equals(action)
                    || Constants.BROADCAST_ON_DUAL_RECORDING_STOPPED.equals(action)) {
                mainHandler.postDelayed(() -> {
                    recordingSessionActive = readAnyRecordingActivePreference();
                    applyKeepScreenOn(recordingSessionActive);
                }, 250L);
                return;
            }

            if (Constants.ACTION_STORAGE_LOCATION_CHANGED.equals(action)) {
                BackgroundRecordsDiscovery.schedule(app, 250L, "storage_changed");
            }
        }
    };

    public SportBestCamRuntimeCoordinator(@NonNull Application application) {
        app = application;
        app.registerActivityLifecycleCallbacks(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.BROADCAST_ON_RECORDING_STARTED);
        filter.addAction(Constants.BROADCAST_ON_RECORDING_RESUMED);
        filter.addAction(Constants.BROADCAST_ON_RECORDING_PAUSED);
        filter.addAction(Constants.BROADCAST_ON_RECORDING_STOPPED);
        filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_STARTED);
        filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_RESUMED);
        filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_PAUSED);
        filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_STOPPED);
        filter.addAction(Constants.BROADCAST_ON_DUAL_RECORDING_STARTED);
        filter.addAction(Constants.BROADCAST_ON_DUAL_RECORDING_RESUMED);
        filter.addAction(Constants.BROADCAST_ON_DUAL_RECORDING_PAUSED);
        filter.addAction(Constants.BROADCAST_ON_DUAL_RECORDING_STOPPED);
        filter.addAction(Constants.ACTION_STORAGE_LOCATION_CHANGED);
        ContextCompat.registerReceiver(app, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        recordingSessionActive = readAnyRecordingActivePreference();
    }

    private void handleRecordsBecameVisible(@NonNull String reason) {
        Activity activity = resumedActivity.get();
        if (activity instanceof FragmentActivity) {
            // If access is missing, this opens ACTION_OPEN_DOCUMENT_TREE once. If the
            // permission is already persisted, nothing is shown.
            RecordsAutoAccessFragment.ensureAccessOrPrompt((FragmentActivity) activity);
        }

        // Run even if the permission picker has to appear: MediaStore/direct fallback
        // can still discover current recordings. Granting SAF access schedules another
        // pass automatically from RecordsAutoAccessFragment.
        BackgroundRecordsDiscovery.schedule(app, 80L, reason);
    }

    private void scheduleRecordsVisibilityPoll() {
        if (recordsPollScheduled) return;
        Activity activity = resumedActivity.get();
        if (activity == null || activity.isFinishing()) return;
        recordsPollScheduled = true;
        mainHandler.postDelayed(recordsVisibilityPoll, RECORDS_VISIBILITY_POLL_MS);
    }

    private boolean readAnyRecordingActivePreference() {
        try {
            android.content.SharedPreferences prefs =
                    app.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getBoolean(Constants.PREF_IS_RECORDING_IN_PROGRESS, false)
                    || prefs.getBoolean(Constants.PREF_IS_SCREEN_RECORDING_IN_PROGRESS, false);
        } catch (Throwable t) {
            FLog.w(TAG, "Unable to read recording activity preference", t);
            return recordingSessionActive;
        }
    }

    private void applyKeepScreenOn(boolean keepOn) {
        Activity activity = resumedActivity.get();
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(() -> {
            try {
                Window window = activity.getWindow();
                if (window == null) return;
                if (keepOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
                FLog.d(TAG, "FLAG_KEEP_SCREEN_ON=" + keepOn
                        + " activity=" + activity.getClass().getSimpleName());
            } catch (Throwable t) {
                FLog.w(TAG, "Unable to update keep-screen-on flag", t);
            }
        });
    }

    /** Refreshes Records immediately after an automatic Add-all completed. */
    public void refreshRecordsUiIfVisible() {
        Activity activity = resumedActivity.get();
        if (!(activity instanceof FragmentActivity) || activity.isFinishing()) return;
        activity.runOnUiThread(() -> refreshRecordsInManager(
                ((FragmentActivity) activity).getSupportFragmentManager()));
    }

    private boolean refreshRecordsInManager(@NonNull FragmentManager manager) {
        for (Fragment fragment : manager.getFragments()) {
            if (fragment == null) continue;
            if (fragment instanceof RecordsFragment && fragment.isAdded() && fragment.isVisible()) {
                ((RecordsFragment) fragment).refreshList();
                return true;
            }
            if (fragment.isAdded() && refreshRecordsInManager(fragment.getChildFragmentManager())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        if (activity instanceof FragmentActivity) {
            ((FragmentActivity) activity).getSupportFragmentManager()
                    .registerFragmentLifecycleCallbacks(fragmentCallbacks, true);
        }
        activity.getWindow().getDecorView().post(() ->
                com.fadcam.ui.SportControlsUiPolish.install(activity));
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        resumedActivity = new WeakReference<>(activity);
        com.fadcam.effects.SportBestCamUpdateManager.onActivityResumed(activity);
        com.fadcam.ui.SportControlsUiPolish.install(activity);
        recordingSessionActive = recordingSessionActive || readAnyRecordingActivePreference();
        applyKeepScreenOn(recordingSessionActive);
        recordsWasVisible = false;
        scheduleRecordsVisibilityPoll();
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        Activity current = resumedActivity.get();
        if (current == activity) {
            mainHandler.removeCallbacks(recordsVisibilityPoll);
            recordsPollScheduled = false;
            recordsWasVisible = false;
        }
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        Activity current = resumedActivity.get();
        if (current == activity) {
            mainHandler.removeCallbacks(recordsVisibilityPoll);
            recordsPollScheduled = false;
            recordsWasVisible = false;
            resumedActivity.clear();
        }
        if (activity instanceof FragmentActivity) {
            try {
                ((FragmentActivity) activity).getSupportFragmentManager()
                        .unregisterFragmentLifecycleCallbacks(fragmentCallbacks);
            } catch (Throwable ignored) {
            }
        }
    }
}
