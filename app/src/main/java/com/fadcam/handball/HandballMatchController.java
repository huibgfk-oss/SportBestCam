package com.fadcam.handball;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.fadcam.R;

import java.util.Locale;

/** Match state + SportBestCam sport HUD. */
public final class HandballMatchController {
    public interface VideoStateProvider {
        boolean isVideoRecording();
        boolean isVideoPaused();
    }

    private final View root;
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler clock = new Handler(Looper.getMainLooper());
    private final GoalDetectionEngine detector;
    private final VideoStateProvider videoStateProvider;

    private int home;
    private int away;
    private int half;
    private long elapsedMs;
    private long startedAt;
    private boolean running;
    private String homeName;
    private String awayName;
    private String homeShort;
    private String awayShort;
    private boolean pausedByVideo;
    private boolean videoRecordingRunning;
    private boolean matchRecording;
    private String matchType;
    private int matchHalves;
    private int halfMinutes;
    private AlertDialog goalPromptDialog;

    private TextView scoreCombined;
    private TextView homeScoreView;
    private TextView awayScoreView;
    private TextView timer;
    private TextView halfView;
    private TextView homeNameView;
    private TextView awayNameView;
    private TextView status;
    private View hudOverlay;
    private Button timerToggle;

    private String lastScoreboardStyle;
    private int lastTeamAColor = Integer.MIN_VALUE;
    private int lastTeamBColor = Integer.MIN_VALUE;
    private Switch autoSwitch;
    private GoalCalibrationOverlay calibrationOverlay;
    private GoalDetectionEngine.Side calibratingSide;

    public HandballMatchController(View root, GoalDetectionEngine detector) {
        this(root, detector, null);
    }

    public HandballMatchController(View root, GoalDetectionEngine detector, VideoStateProvider videoStateProvider) {
        this.root = root;
        this.detector = detector;
        this.videoStateProvider = videoStateProvider;
        this.context = root.getContext();
        this.prefs = context.getSharedPreferences("handball_match", Context.MODE_PRIVATE);
        load();
        bind();
        render();
        detector.setEnabled(matchRecording && prefs.getBoolean("auto_goal", false));
        detector.start();
    }

    private void load() {
        home = prefs.getInt("home", 0);
        away = prefs.getInt("away", 0);
        half = prefs.getInt("half", 1);
        elapsedMs = prefs.getLong("elapsed", 0L);
        running = prefs.getBoolean("running", false);
        startedAt = prefs.getLong("started_at", System.currentTimeMillis());
        homeName = SportRecordingProfile.teamA(context, prefs);
        awayName = SportRecordingProfile.teamB(context, prefs);
        homeShort = shortName("", homeName);
        awayShort = shortName("", awayName);
        pausedByVideo = prefs.getBoolean("timer_paused_by_video", false);
        videoRecordingRunning = prefs.getBoolean("video_recording_running", false);
        matchRecording = SportRecordingProfile.isMatchRecording(prefs);
        matchType = SportRecordingProfile.matchDescription(context, prefs);
        matchHalves = Math.max(1, prefs.getInt("match_halves", 2));
        halfMinutes = Math.max(1, prefs.getInt("half_minutes", 30));
    }

    private void bind() {
        scoreCombined = root.findViewById(R.id.hb_score);
        homeScoreView = root.findViewById(R.id.hb_home_score);
        awayScoreView = root.findViewById(R.id.hb_away_score);
        timer = root.findViewById(R.id.hb_timer);
        halfView = root.findViewById(R.id.hb_half);
        timerToggle = root.findViewById(R.id.hb_timer_toggle);
        homeNameView = root.findViewById(R.id.hb_home_name);
        awayNameView = root.findViewById(R.id.hb_away_name);
        status = root.findViewById(R.id.hb_detector_status);
        hudOverlay = root.findViewById(R.id.hb_overlay);
        autoSwitch = root.findViewById(R.id.hb_auto_goal);
        calibrationOverlay = root.findViewById(R.id.hb_calibration_overlay);

        bindLegacyScoreButton(R.id.hb_home_plus, true, 1);
        bindLegacyScoreButton(R.id.hb_home_minus, true, -1);
        bindLegacyScoreButton(R.id.hb_away_plus, false, 1);
        bindLegacyScoreButton(R.id.hb_away_minus, false, -1);

        bindScoreGestures(homeScoreView, true);
        bindScoreGestures(awayScoreView, false);

        if (timerToggle != null) timerToggle.setOnClickListener(v -> toggleClock());
        if (timer != null) {
            timer.setClickable(true);
            timer.setFocusable(true);
            timer.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                toggleClock();
            });
        }
        if (halfView != null) {
            halfView.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                half = half >= Math.max(1, matchHalves) ? 1 : half + 1;
                save();
                render();
            });
        }

        if (homeNameView != null) homeNameView.setOnClickListener(v -> editTeams());
        if (awayNameView != null) awayNameView.setOnClickListener(v -> editTeams());

        View teams = root.findViewById(R.id.hb_teams);
        if (teams != null) teams.setOnClickListener(v -> editTeams());
        View reset = root.findViewById(R.id.hb_reset);
        if (reset != null) reset.setOnClickListener(v -> confirmReset());
        View settings = root.findViewById(R.id.hb_settings);
        if (settings != null) settings.setOnClickListener(v -> showMatchSetup());

        if (autoSwitch != null) {
            autoSwitch.setChecked(matchRecording && prefs.getBoolean("auto_goal", false));
            autoSwitch.setOnCheckedChangeListener((button, checked) -> {
                // Simple-video mode temporarily hides/disables Auto Goal, but must not
                // destroy the user's saved Auto Goal preference for the next match.
                if (matchRecording) {
                    prefs.edit().putBoolean("auto_goal", checked).apply();
                }
                detector.setEnabled(matchRecording && checked);
                updateDetectorStatus();
            });
        }

        View calLeft = root.findViewById(R.id.hb_cal_left);
        View calRight = root.findViewById(R.id.hb_cal_right);
        if (calLeft != null) calLeft.setOnClickListener(v -> beginCalibration(GoalDetectionEngine.Side.HOME));
        if (calRight != null) calRight.setOnClickListener(v -> beginCalibration(GoalDetectionEngine.Side.AWAY));

        if (calibrationOverlay != null) {
            calibrationOverlay.setOnTouchListener((v, e) -> {
                if (calibratingSide == null) return false;
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    float x = e.getX() / Math.max(1f, v.getWidth());
                    float y = e.getY() / Math.max(1f, v.getHeight());
                    detector.setGoalCenter(calibratingSide, x, y);
                    calibratingSide = null;
                    calibrationOverlay.setVisibility(View.GONE);
                    refreshCalibration();
                    Toast.makeText(context, context.getString(R.string.sbc_goal_calibrated), Toast.LENGTH_SHORT).show();
                    return true;
                }
                return true;
            });
        }
        refreshCalibration();
    }

    private void bindLegacyScoreButton(int id, boolean isHome, int delta) {
        View v = root.findViewById(id);
        if (v != null) v.setOnClickListener(x -> change(isHome, delta));
    }

    private void bindScoreGestures(TextView view, boolean isHome) {
        if (view == null) return;
        view.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            change(isHome, 1);
            pulse(v);
        });
        view.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            change(isHome, -1);
            pulse(v);
            return true;
        });
    }

    private void pulse(View v) {
        v.animate().cancel();
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(80L)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(100L).start())
                .start();
    }

    private void beginCalibration(GoalDetectionEngine.Side side) {
        calibratingSide = side;
        if (calibrationOverlay != null) calibrationOverlay.setVisibility(View.VISIBLE);
        Toast.makeText(context,
                context.getString(side == GoalDetectionEngine.Side.HOME
                        ? R.string.sbc_touch_goal_left
                        : R.string.sbc_touch_goal_right),
                Toast.LENGTH_LONG).show();
    }

    private void refreshCalibration() {
        float[] g = detector.getGoals();
        if (calibrationOverlay != null) calibrationOverlay.setGoals(g[0], g[1], g[2], g[3]);
        updateDetectorStatus();
    }

    private String detectorStatusText() {
        if (!matchRecording) return context.getString(R.string.sbc_auto_goal_simple_mode);
        boolean enabled = autoSwitch != null
                ? autoSwitch.isChecked()
                : prefs.getBoolean("auto_goal", false);
        if (!enabled) return context.getString(R.string.sbc_auto_goal_off_status);
        if (prefs.getBoolean("goal_prompt_snoozed", false)) return context.getString(R.string.sbc_auto_goal_snoozed_status);
        if (!detector.isCalibrated()) return context.getString(R.string.sbc_auto_goal_calibrate_status);
        return context.getString(R.string.sbc_auto_goal_filtered_status);
    }

    private void updateDetectorStatus() {
        if (status != null) status.setText(detectorStatusText());
    }

    public void onGoalCandidate(GoalDetectionEngine.Side suggested, float confidence) {
        if (!matchRecording) return;
        if (!prefs.getBoolean("auto_goal", false)) return;
        if (prefs.getBoolean("goal_prompt_snoozed", false)) return;
        if (goalPromptDialog != null && goalPromptDialog.isShowing()) return;

        final int d = Math.max(1, (int) context.getResources().getDisplayMetrics().density);
        String pct = Math.round(confidence * 100) + "%";
        String hint = suggested == GoalDetectionEngine.Side.HOME ? homeName : awayName;

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(12 * d, 8 * d, 12 * d, 8 * d);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 18, 18, 20));
        bg.setCornerRadius(14 * d);
        bg.setStroke(Math.max(1, d), Color.argb(120, 255, 255, 255));
        box.setBackground(bg);

        TextView title = new TextView(context);
        title.setText(context.getString(R.string.sbc_possible_goal, pct, hint));
        title.setTextColor(Color.WHITE);
        title.setTextSize(14f);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        box.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 34 * d));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        Button homeGoal = setupButton(homeShort + " +1", Color.rgb(46, 125, 50));
        Button awayGoal = setupButton(awayShort + " +1", Color.rgb(46, 125, 50));
        Button noGoal = setupButton(context.getString(R.string.sbc_no), Color.rgb(69, 90, 100));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, 40 * d, 1f);
        ap.setMargins(2 * d, 2 * d, 2 * d, 2 * d);
        actions.addView(homeGoal, ap);
        actions.addView(noGoal, ap);
        actions.addView(awayGoal, ap);
        box.addView(actions);

        LinearLayout options = new LinearLayout(context);
        options.setOrientation(LinearLayout.HORIZONTAL);
        options.setGravity(Gravity.CENTER);
        Button snooze = setupButton(context.getString(R.string.sbc_dont_ask_match), Color.rgb(93, 64, 55));
        Button disable = setupButton(context.getString(R.string.sbc_auto_goal_off_button), Color.rgb(120, 35, 35));
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(0, 36 * d, 1f);
        op.setMargins(2 * d, 2 * d, 2 * d, 2 * d);
        options.addView(snooze, op);
        options.addView(disable, op);
        box.addView(options);

        goalPromptDialog = new AlertDialog.Builder(context).setView(box).create();
        goalPromptDialog.setOnDismissListener(dlg -> goalPromptDialog = null);

        homeGoal.setOnClickListener(v -> { change(true, 1); if (goalPromptDialog != null) goalPromptDialog.dismiss(); });
        awayGoal.setOnClickListener(v -> { change(false, 1); if (goalPromptDialog != null) goalPromptDialog.dismiss(); });
        noGoal.setOnClickListener(v -> { if (goalPromptDialog != null) goalPromptDialog.dismiss(); });
        snooze.setOnClickListener(v -> {
            prefs.edit().putBoolean("goal_prompt_snoozed", true).apply();
            detector.setEnabled(false);
            updateDetectorStatus();
            if (goalPromptDialog != null) goalPromptDialog.dismiss();
        });
        disable.setOnClickListener(v -> {
            prefs.edit().putBoolean("auto_goal", false).putBoolean("goal_prompt_snoozed", false).apply();
            detector.setEnabled(false);
            if (autoSwitch != null) autoSwitch.setChecked(false);
            updateDetectorStatus();
            if (goalPromptDialog != null) goalPromptDialog.dismiss();
        });

        goalPromptDialog.show();
        Window window = goalPromptDialog.getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.92f);
            lp.y = 22 * d;
            window.setAttributes(lp);
        }

        // Non-blocking by design: ignore the candidate if the operator is busy.
        clock.postDelayed(() -> {
            AlertDialog dlog = goalPromptDialog;
            if (dlog != null && dlog.isShowing()) dlog.dismiss();
        }, 4500L);
    }

    private Button setupButton(String text, int color) {
        Button b = new Button(context);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12f);
        b.setAllCaps(false);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        b.setMinHeight(0);
        return b;
    }

    private void showMatchSetup() {
        final int d = Math.max(1, (int) context.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(16 * d, 12 * d, 16 * d, 8 * d);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.addView(box);

        TextView title = new TextView(context);
        title.setText(context.getString(R.string.sbc_match_settings_title));
        title.setTextColor(Color.rgb(25, 25, 25));
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        box.addView(title);

        Switch mode = new Switch(context);
        mode.setText(context.getString(R.string.sbc_match_recording));
        mode.setTextColor(Color.rgb(35, 35, 35));
        mode.setChecked(matchRecording);
        mode.setPadding(8 * d, 4 * d, 8 * d, 4 * d);
        // The selected recording profile determines the filename and burned-in
        // match overlay when a recording file is created. Lock profile changes
        // during an active recording so one file cannot become half match / half
        // simple or have a filename that no longer describes its contents.
        boolean profileLockedByRecording = false;
        if (videoStateProvider != null) {
            try {
                profileLockedByRecording = videoStateProvider.isVideoRecording();
            } catch (Exception ignored) {
            }
        }
        mode.setEnabled(!profileLockedByRecording);
        mode.setAlpha(profileLockedByRecording ? 0.45f : 1f);
        final boolean recordingLocked = profileLockedByRecording;
        box.addView(mode, rowLp(d));

        TextView modeInfo = new TextView(context);
        modeInfo.setText(matchRecording
                ? context.getString(R.string.sbc_match_recording_info)
                : context.getString(R.string.sbc_simple_video_info));
        modeInfo.setTextColor(Color.GRAY);
        modeInfo.setTextSize(12f);
        modeInfo.setPadding(4 * d, 0, 4 * d, 8 * d);
        box.addView(modeInfo);

        TextView summary = new TextView(context);
        summary.setText(matchRecording
                ? homeName + "  " + home + " : " + away + "  " + awayName + "\n" + matchType
                : context.getString(R.string.sbc_simple_video));
        summary.setTextColor(Color.DKGRAY);
        summary.setTextSize(14f);
        summary.setPadding(0, 6 * d, 0, 8 * d);
        box.addView(summary);

        Button teams = setupButton(context.getString(R.string.sbc_teams), Color.rgb(55, 71, 79));
        box.addView(teams, rowLp(d));

        Button scoreboardStyle = setupButton(context.getString(R.string.sbc_scoreboard_style), Color.rgb(57, 73, 171));
        box.addView(scoreboardStyle, rowLp(d));

        Button matchSummary = setupButton(context.getString(R.string.sbc_match_summary), Color.rgb(0, 121, 107));
        box.addView(matchSummary, rowLp(d));

        Switch auto = new Switch(context);
        auto.setText(context.getString(R.string.sbc_auto_goal));
        auto.setTextColor(Color.rgb(35, 35, 35));
        auto.setChecked(matchRecording && prefs.getBoolean("auto_goal", false));
        auto.setPadding(8 * d, 3 * d, 8 * d, 3 * d);
        box.addView(auto, rowLp(d));

        Switch gesture = new Switch(context);
        gesture.setText(context.getString(R.string.sbc_gesture_scoring));
        gesture.setTextColor(Color.rgb(35, 35, 35));
        gesture.setChecked(prefs.getBoolean(GestureScoringEngine.KEY_ENABLED, false));
        gesture.setPadding(8 * d, 3 * d, 8 * d, 3 * d);
        box.addView(gesture, rowLp(d));

        TextView gestureInfo = new TextView(context);
        gestureInfo.setText(context.getString(R.string.sbc_gesture_scoring_info));
        gestureInfo.setTextColor(Color.GRAY);
        gestureInfo.setTextSize(11.5f);
        gestureInfo.setPadding(8 * d, 0, 8 * d, 5 * d);
        box.addView(gestureInfo);
        Button gestureSource = setupButton("", Color.rgb(69, 90, 100));
        box.addView(gestureSource, rowLp(d));
        TextView gestureSourceInfo = new TextView(context);
        gestureSourceInfo.setTextColor(Color.GRAY);
        gestureSourceInfo.setTextSize(11.5f);
        gestureSourceInfo.setPadding(8 * d, 0, 8 * d, 5 * d);
        box.addView(gestureSourceInfo);
        updateGestureSourceUi(gestureSource, gestureSourceInfo);

        long gestureHoldMs = prefs.getLong(
                GestureScoringEngine.KEY_HOLD_MS, GestureScoringEngine.DEFAULT_HOLD_MS);
        Button gestureHold = setupButton(
                context.getString(R.string.sbc_gesture_hold_time,
                        String.format(Locale.US, "%.1f", gestureHoldMs / 1000f)),
                Color.rgb(69, 90, 100));
        box.addView(gestureHold, rowLp(d));

        Switch gestureVibration = new Switch(context);
        gestureVibration.setText(context.getString(R.string.sbc_gesture_vibration));
        gestureVibration.setTextColor(Color.rgb(35, 35, 35));
        gestureVibration.setChecked(
                prefs.getBoolean(GestureScoringEngine.KEY_VIBRATION, true));
        gestureVibration.setPadding(8 * d, 3 * d, 8 * d, 3 * d);
        box.addView(gestureVibration, rowLp(d));

        LinearLayout calRow = new LinearLayout(context);
        calRow.setOrientation(LinearLayout.HORIZONTAL);
        Button calL = setupButton(context.getString(R.string.sbc_calibrate_left), Color.rgb(84, 110, 122));
        Button calR = setupButton(context.getString(R.string.sbc_calibrate_right), Color.rgb(84, 110, 122));
        LinearLayout.LayoutParams halfLp = new LinearLayout.LayoutParams(0, 44 * d, 1f);
        halfLp.setMargins(0, 3 * d, 4 * d, 3 * d);
        calRow.addView(calL, halfLp);
        LinearLayout.LayoutParams halfLp2 = new LinearLayout.LayoutParams(0, 44 * d, 1f);
        halfLp2.setMargins(4 * d, 3 * d, 0, 3 * d);
        calRow.addView(calR, halfLp2);
        box.addView(calRow);

        TextView detectorInfo = new TextView(context);
        detectorInfo.setText(detectorStatusText());
        detectorInfo.setTextColor(Color.GRAY);
        detectorInfo.setTextSize(12f);
        detectorInfo.setPadding(4 * d, 4 * d, 4 * d, 8 * d);
        box.addView(detectorInfo);

        Button newMatch = setupButton(context.getString(R.string.sbc_new_match_guided), Color.rgb(109, 76, 65));
        box.addView(newMatch, rowLp(d));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(scroll)
                .setNegativeButton(context.getString(R.string.sbc_close), null)
                .create();

        Runnable updateEnabledState = () -> {
            boolean enabled = matchRecording;
            for (View v : new View[]{
                    teams, scoreboardStyle, matchSummary, auto, gesture,
                    gestureInfo, gestureHold, gestureVibration, calL, calR}) {
                v.setEnabled(enabled);
                v.setAlpha(enabled ? 1f : 0.42f);
            }
            boolean gestureGeometryEditable = enabled && !recordingLocked;
            gesture.setEnabled(gestureGeometryEditable);
            gesture.setAlpha(gestureGeometryEditable ? 1f : 0.42f);
            gestureSource.setEnabled(gestureGeometryEditable);
            gestureSource.setAlpha(gestureGeometryEditable ? 1f : 0.42f);
            gestureSourceInfo.setEnabled(enabled);
            gestureSourceInfo.setAlpha(enabled ? 1f : 0.42f);
            modeInfo.setText(enabled
                    ? context.getString(R.string.sbc_match_recording_info)
                    : context.getString(R.string.sbc_simple_video_info));
            summary.setText(enabled
                    ? homeName + "  " + home + " : " + away + "  " + awayName + "\n" + matchType
                    : context.getString(R.string.sbc_simple_video));
            detectorInfo.setText(detectorStatusText());
        };
        updateEnabledState.run();

        mode.setOnCheckedChangeListener((button, checked) -> {
            if (!checked && running) {
                elapsedMs = currentMs();
                running = false;
                pausedByVideo = false;
            }
            matchRecording = checked;
            prefs.edit().putString(SportRecordingProfile.KEY_RECORDING_MODE,
                    checked ? SportRecordingProfile.MODE_MATCH : SportRecordingProfile.MODE_SIMPLE)
                    .putBoolean("running", running)
                    .putLong("elapsed", elapsedMs)
                    .apply();
            detector.setEnabled(checked && prefs.getBoolean("auto_goal", false));
            if (autoSwitch != null) autoSwitch.setChecked(checked && prefs.getBoolean("auto_goal", false));
            render();
            updateEnabledState.run();
        });

        teams.setOnClickListener(v -> { dialog.dismiss(); editTeams(); });
        scoreboardStyle.setOnClickListener(v -> { dialog.dismiss(); showScoreboardStyleDialog(); });
        matchSummary.setOnClickListener(v -> { dialog.dismiss(); showMatchSummaryDialog(); });
        auto.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("auto_goal", checked)
                    .putBoolean("goal_prompt_snoozed", false).apply();
            if (autoSwitch != null) autoSwitch.setChecked(matchRecording && checked);
            detector.setEnabled(matchRecording && checked);
            detectorInfo.setText(detectorStatusText());
        });
        gesture.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(GestureScoringEngine.KEY_ENABLED, checked).apply());
        gestureSource.setOnClickListener(v ->
                showGestureSourceDialog(gestureSource, gestureSourceInfo));
        gestureVibration.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(GestureScoringEngine.KEY_VIBRATION, checked).apply());
        gestureHold.setOnClickListener(v -> showGestureHoldDialog(gestureHold));
        calL.setOnClickListener(v -> { dialog.dismiss(); beginCalibration(GoalDetectionEngine.Side.HOME); });
        calR.setOnClickListener(v -> { dialog.dismiss(); beginCalibration(GoalDetectionEngine.Side.AWAY); });
        newMatch.setOnClickListener(v -> { dialog.dismiss(); showNewMatchDialog(); });
        dialog.show();
        resizeDialog(dialog, 0.78f, 0.92f);
    }


    private void updateGestureSourceUi(Button target, TextView info) {
        boolean rearHidden = GestureScoringEngine.isRearHiddenSource(prefs);
        int labelId = rearHidden
                ? R.string.sbc_gesture_source_rear_hidden
                : R.string.sbc_gesture_source_front;
        int infoId = rearHidden
                ? R.string.sbc_gesture_source_rear_info
                : R.string.sbc_gesture_source_front_info;
        target.setText(context.getString(R.string.sbc_gesture_source_label,
                context.getString(labelId)));
        info.setText(context.getString(infoId));
    }

    private void showGestureSourceDialog(Button target, TextView info) {
        final String[] labels = {
                context.getString(R.string.sbc_gesture_source_front),
                context.getString(R.string.sbc_gesture_source_rear_hidden)
        };
        final boolean rearHidden = GestureScoringEngine.isRearHiddenSource(prefs);
        new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.sbc_gesture_source_title))
                .setSingleChoiceItems(labels, rearHidden ? 1 : 0, (dialog, which) -> {
                    String source = which == 1
                            ? GestureScoringEngine.SOURCE_REAR_HIDDEN
                            : GestureScoringEngine.SOURCE_FRONT_CONCURRENT;
                    prefs.edit().putString(GestureScoringEngine.KEY_SOURCE, source).apply();
                    updateGestureSourceUi(target, info);
                    if (which == 0
                            && !GestureScoringEngine.isFrontGestureCameraSupported(context)) {
                        Toast.makeText(context,
                                context.getString(R.string.sbc_gesture_front_unsupported),
                                Toast.LENGTH_LONG).show();
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(context.getString(R.string.sbc_close), null)
                .show();
    }
    private void showGestureHoldDialog(Button target) {
        final long[] values = {600L, 800L, 1000L, 1200L};
        final String[] labels = new String[values.length];
        long current = prefs.getLong(
                GestureScoringEngine.KEY_HOLD_MS, GestureScoringEngine.DEFAULT_HOLD_MS);
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = context.getString(R.string.sbc_gesture_hold_value,
                    String.format(Locale.US, "%.1f", values[i] / 1000f));
            if (Math.abs(values[i] - current) < Math.abs(values[checked] - current)) checked = i;
        }
        final int initiallyChecked = checked;
        new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.sbc_gesture_hold_title))
                .setSingleChoiceItems(labels, initiallyChecked, (dialog, which) -> {
                    long selected = values[Math.max(0, Math.min(values.length - 1, which))];
                    prefs.edit().putLong(GestureScoringEngine.KEY_HOLD_MS, selected).apply();
                    target.setText(context.getString(R.string.sbc_gesture_hold_time,
                            String.format(Locale.US, "%.1f", selected / 1000f)));
                    dialog.dismiss();
                })
                .setNegativeButton(context.getString(R.string.sbc_close), null)
                .show();
    }

    private LinearLayout.LayoutParams rowLp(int d) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 44 * d);
        lp.setMargins(0, 3 * d, 0, 3 * d);
        return lp;
    }

    private EditText teamField(String hint, String value) {
        EditText e = new EditText(context);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextColor(Color.rgb(24, 24, 24));
        e.setHintTextColor(Color.rgb(105, 105, 105));
        e.setTextSize(15f);
        int d = Math.max(1, (int) context.getResources().getDisplayMetrics().density);
        e.setPadding(12 * d, 0, 12 * d, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(10 * d);
        bg.setStroke(Math.max(1, d), Color.rgb(180, 185, 190));
        e.setBackground(bg);
        e.setBackgroundTintList(null);
        return e;
    }

    private void editTeams() {
        final int d = Math.max(1, (int) context.getResources().getDisplayMetrics().density);
        final boolean landscape = context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(16 * d, 10 * d, 16 * d, 6 * d);

        TextView title = new TextView(context);
        title.setText(context.getString(R.string.sbc_teams_title));
        title.setTextColor(Color.rgb(25, 25, 25));
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(2 * d, 0, 0, 4 * d);
        box.addView(title);

        TextView info = new TextView(context);
        info.setText(context.getString(R.string.sbc_teams_info));
        info.setTextColor(Color.rgb(80, 80, 80));
        info.setTextSize(12.5f);
        info.setPadding(2 * d, 0, 2 * d, 8 * d);
        box.addView(info);

        EditText teamA = teamField(context.getString(R.string.sbc_team_a_name), homeName);
        EditText teamB = teamField(context.getString(R.string.sbc_team_b_name), awayName);

        if (landscape) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);

            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, 48 * d, 1f);
            left.setMargins(0, 3 * d, 6 * d, 3 * d);
            teamA.setLayoutParams(left);

            LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, 48 * d, 1f);
            right.setMargins(6 * d, 3 * d, 0, 3 * d);
            teamB.setLayoutParams(right);

            row.addView(teamA);
            row.addView(teamB);
            box.addView(row);
        } else {
            for (EditText e : new EditText[]{teamA, teamB}) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 48 * d);
                lp.setMargins(0, 4 * d, 0, 4 * d);
                e.setLayoutParams(lp);
                box.addView(e);
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(box)
                .setPositiveButton(context.getString(R.string.sbc_save), (di, w) -> {
                    homeName = clean(teamA.getText().toString(), context.getString(R.string.sbc_team_a));
                    awayName = clean(teamB.getText().toString(), context.getString(R.string.sbc_team_b));

                    // Automatically derive the compact labels used by goal prompts
                    // and any constrained HUD areas.
                    homeShort = shortName("", homeName);
                    awayShort = shortName("", awayName);

                    save();
                    render();
                })
                .setNegativeButton(context.getString(R.string.sbc_cancel), null)
                .create();

        dialog.show();
        resizeDialog(dialog, 0.58f, 0.88f);
    }

    private void resizeDialog(AlertDialog dialog, float landscapeFraction, float portraitFraction) {
        Window window = dialog.getWindow();
        if (window == null) return;
        boolean landscape = context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int screen = context.getResources().getDisplayMetrics().widthPixels;
        window.setLayout((int) (screen * (landscape ? landscapeFraction : portraitFraction)),
                WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private static String clean(String s, String fallback) {
        s = s == null ? "" : s.trim();
        return s.isEmpty() ? fallback : s;
    }

    private static String shortName(String s, String full) {
        s = s == null ? "" : s.trim();
        if (s.isEmpty()) s = full;
        s = s.toUpperCase(Locale.ROOT);
        return s.length() > 18 ? s.substring(0, 18) : s;
    }

    private void change(boolean isHome, int delta) {
        if (isHome) home = Math.max(0, home + delta);
        else away = Math.max(0, away + delta);
        save();
        render();
    }

    public void onGestureScore(GestureScoringEngine.Side side, int delta, float confidence) {
        if (!matchRecording || !prefs.getBoolean(GestureScoringEngine.KEY_ENABLED, false)) return;
        boolean isHome = side == GestureScoringEngine.Side.HOME;
        int before = isHome ? home : away;
        int after = Math.max(0, before + delta);
        if (after == before) return;

        change(isHome, delta);
        View scoreView = isHome ? homeScoreView : awayScoreView;
        if (scoreView != null) {
            if (prefs.getBoolean(GestureScoringEngine.KEY_VIBRATION, true)) {
                scoreView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            pulse(scoreView);
        }
        String team = isHome ? homeName : awayName;
        Toast.makeText(context,
                context.getString(R.string.sbc_gesture_applied, team, delta > 0 ? "+1" : "-1"),
                Toast.LENGTH_SHORT).show();
    }

    private boolean isVideoActuallyRecording() {
        if (videoStateProvider != null) {
            try {
                return videoStateProvider.isVideoRecording() && !videoStateProvider.isVideoPaused();
            } catch (Exception ignored) {
            }
        }
        return videoRecordingRunning;
    }

    private void toggleClock() {
        if (!matchRecording) {
            Toast.makeText(context, context.getString(R.string.sbc_match_timer_unavailable_simple), Toast.LENGTH_SHORT).show();
            return;
        }
        if (running) {
            elapsedMs = currentMs();
            running = false;
            pausedByVideo = false; // explicit operator pause
        } else {
            videoRecordingRunning = isVideoActuallyRecording();
            if (!videoRecordingRunning) {
                Toast.makeText(context,
                        context.getString(R.string.sbc_timer_requires_video),
                        Toast.LENGTH_SHORT).show();
                save();
                render();
                return;
            }
            startedAt = System.currentTimeMillis();
            running = true;
            pausedByVideo = false;
            clock.post(tick);
        }
        save();
        render();
    }

    /** Recording and match time are synchronised, but a manual timer pause stays manual. */
    public void onVideoRecordingStartedOrResumed() {
        videoRecordingRunning = true;
        if (pausedByVideo && !running) {
            startedAt = System.currentTimeMillis();
            running = true;
            pausedByVideo = false;
        }
        save();
        render();
    }

    public void onVideoRecordingPausedOrStopped(boolean stopped) {
        videoRecordingRunning = false;
        if (running) {
            elapsedMs = currentMs();
            running = false;
            // Only a real PAUSE may auto-resume the timer. STOP requires an explicit timer restart.
            pausedByVideo = !stopped;
        } else if (stopped) {
            pausedByVideo = false;
        }
        save();
        render();
    }

    private final Runnable tick = this::render;

    private long currentMs() {
        return elapsedMs + (running ? Math.max(0, System.currentTimeMillis() - startedAt) : 0L);
    }

    private void applyScoreboardStyle() {
        if (hudOverlay == null) return;

        String style = prefs.getString("scoreboard_style", "compact_tv");
        int colorA = prefs.getInt("team_a_color", Color.rgb(33, 150, 243));
        int colorB = prefs.getInt("team_b_color", Color.rgb(46, 125, 50));

        if (style.equals(lastScoreboardStyle)
                && colorA == lastTeamAColor
                && colorB == lastTeamBColor) {
            return;
        }
        lastScoreboardStyle = style;
        lastTeamAColor = colorA;
        lastTeamBColor = colorB;

        int dd = Math.max(1, (int) context.getResources().getDisplayMetrics().density);

        if (hudOverlay.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) hudOverlay.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.TOP | ("corner".equals(style)
                    ? Gravity.START
                    : Gravity.CENTER_HORIZONTAL);
            lp.leftMargin = "corner".equals(style) ? 8 * dd : 0;
            lp.rightMargin = 0;
            hudOverlay.setLayoutParams(lp);
        }

        boolean landscapeHud = context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        // Compensate for the Android navigation inset on the right: without this,
        // the scoreboard looks visually shifted to the left.
        hudOverlay.setTranslationX((landscapeHud ? 14f : 5f) * dd);

        if ("minimal".equals(style)) {
            hudOverlay.setBackground(roundedHud(Color.argb(70, 0, 0, 0), 0, Color.TRANSPARENT));
        } else if ("corner".equals(style)) {
            hudOverlay.setBackground(roundedHud(Color.argb(235, 12, 14, 17), 1, Color.argb(90, 255, 255, 255)));
        } else {
            hudOverlay.setBackground(roundedHud(Color.argb(230, 27, 30, 35), 1, Color.argb(90, 255, 255, 255)));
        }

        if (homeScoreView != null) {
            homeScoreView.setBackground(scoreBox(colorA, "minimal".equals(style) ? 150 : 195));
        }
        if (awayScoreView != null) {
            awayScoreView.setBackground(scoreBox(colorB, "minimal".equals(style) ? 150 : 195));
        }

        GradientDrawable neutral = "minimal".equals(style)
                ? roundedHud(Color.TRANSPARENT, 0, Color.TRANSPARENT)
                : roundedHud(Color.argb(125, 50, 54, 60), 0, Color.TRANSPARENT);
        if (timer != null) timer.setBackground(neutral);
        if (halfView != null) halfView.setBackground(
                "minimal".equals(style)
                        ? roundedHud(Color.TRANSPARENT, 0, Color.TRANSPARENT)
                        : roundedHud(Color.argb(125, 50, 54, 60), 0, Color.TRANSPARENT));
    }

    private GradientDrawable roundedHud(int fill, int strokeDp, int strokeColor) {
        int dd = Math.max(1, (int) context.getResources().getDisplayMetrics().density);
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(12 * dd);
        if (strokeDp > 0) g.setStroke(Math.max(1, strokeDp * dd), strokeColor);
        return g;
    }

    private GradientDrawable scoreBox(int color, int alpha) {
        int dd = Math.max(1, (int) context.getResources().getDisplayMetrics().density);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)));
        g.setCornerRadius(10 * dd);
        g.setStroke(Math.max(1, dd), Color.argb(70, 255, 255, 255));
        return g;
    }

    private void render() {
        matchRecording = SportRecordingProfile.isMatchRecording(prefs);
        matchType = SportRecordingProfile.matchDescription(context, prefs);

        if (!matchRecording) {
            clock.removeCallbacks(tick);
            detector.setEnabled(false);
            if (hudOverlay != null) hudOverlay.setVisibility(View.GONE);
            if (timerToggle != null) timerToggle.setVisibility(View.GONE);
            updateDetectorStatus();
            return;
        }

        if (hudOverlay != null) hudOverlay.setVisibility(View.VISIBLE);
        if (timerToggle != null) timerToggle.setVisibility(View.VISIBLE);
        detector.setEnabled(matchRecording && prefs.getBoolean("auto_goal", false));
        applyScoreboardStyle();
        if (scoreCombined != null) scoreCombined.setText(home + " : " + away);
        if (homeScoreView != null) homeScoreView.setText(String.valueOf(home));
        if (awayScoreView != null) awayScoreView.setText(String.valueOf(away));
        long sec = currentMs() / 1000L;
        String timeText = String.format(Locale.ROOT, "%02d:%02d", sec / 60L, sec % 60L);
        if (!running && elapsedMs > 0L) timeText = "▶ " + timeText;
        if (timer != null) {
            timer.setText(timeText);
            timer.setTextColor(running || elapsedMs == 0L ? Color.WHITE : Color.rgb(255, 193, 7));
            timer.setContentDescription(context.getString(running
                    ? R.string.sbc_timer_pause_cd
                    : R.string.sbc_timer_resume_cd));
        }
        if (halfView != null) halfView.setText(context.getString(R.string.sbc_period_short, half));
        if (timerToggle != null) {
            String label = context.getString(running
                    ? R.string.sbc_timer_pause
                    : (elapsedMs > 0L ? R.string.sbc_timer_resume : R.string.sbc_timer_start));
            timerToggle.setText(label);
            timerToggle.setContentDescription(context.getString(running
                    ? R.string.sbc_timer_pause_cd
                    : (elapsedMs > 0L ? R.string.sbc_timer_resume_cd : R.string.sbc_timer_start_cd)));
        }
        if (homeNameView != null) { homeNameView.setText(homeName); homeNameView.setContentDescription(homeName); }
        if (awayNameView != null) { awayNameView.setText(awayName); awayNameView.setContentDescription(awayName); }
        if (running) {
            clock.removeCallbacks(tick);
            clock.postDelayed(tick, 250L);
        }
        updateDetectorStatus();
    }

    private void save() {
        prefs.edit()
                .putInt("home", home)
                .putInt("away", away)
                .putInt("half", half)
                .putLong("elapsed", elapsedMs)
                .putBoolean("running", running)
                .putLong("started_at", startedAt)
                .putString("home_name", homeName)
                .putString("away_name", awayName)
                .putString("home_short", homeShort)
                .putString("away_short", awayShort)
                .putBoolean("timer_paused_by_video", pausedByVideo)
                .putBoolean("video_recording_running", videoRecordingRunning)
                .putString("match_type", matchType)
                .putInt("match_halves", matchHalves)
                .putInt("half_minutes", halfMinutes)
                .putBoolean("video_overlay_enabled", true)
                .apply();
    }

    private TextView dialogLabel(String text, int d) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextColor(Color.rgb(40, 40, 40));
        v.setTextSize(13f);
        v.setPadding(2 * d, 6 * d, 2 * d, 3 * d);
        return v;
    }

    private void showNewMatchDialog() {
        if (videoStateProvider != null) {
            try {
                if (videoStateProvider.isVideoRecording()) {
                    Toast.makeText(context,
                            context.getString(R.string.sbc_stop_recording_before_new_match),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        new SportSetupWizard(context, () -> {
            clock.removeCallbacks(tick);
            load();
            detector.setEnabled(matchRecording && prefs.getBoolean("auto_goal", false));
            if (autoSwitch != null) autoSwitch.setChecked(matchRecording && prefs.getBoolean("auto_goal", false));
            lastScoreboardStyle = null;
            lastTeamAColor = Integer.MIN_VALUE;
            lastTeamBColor = Integer.MIN_VALUE;
            render();
            Toast.makeText(context, context.getString(matchRecording ? R.string.sbc_new_match_configured : R.string.sbc_simple_mode_configured), Toast.LENGTH_SHORT).show();
        }).show();
    }

    private void showScoreboardStyleDialog() {
        final String[] labels = {
                context.getString(R.string.sbc_style_compact_tv),
                context.getString(R.string.sbc_style_corner),
                context.getString(R.string.sbc_style_minimal)
        };
        final String[] values = {"compact_tv", "corner", "minimal"};
        String current = prefs.getString("scoreboard_style", "compact_tv");
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) checked = i;
        final int initial = checked;

        new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.sbc_scoreboard_style))
                .setSingleChoiceItems(labels, checked, null)
                .setPositiveButton(context.getString(R.string.sbc_save), (dialog, which) -> {
                    AlertDialog ad = (AlertDialog) dialog;
                    int pos = ad.getListView().getCheckedItemPosition();
                    if (pos < 0) pos = initial;
                    prefs.edit().putString("scoreboard_style", values[pos]).apply();
                    lastScoreboardStyle = null;
                    render();
                })
                .setNegativeButton(context.getString(R.string.sbc_cancel), null)
                .show();
    }

    private void showMatchSummaryDialog() {
        final int dd = Math.max(1, (int) context.getResources().getDisplayMetrics().density);
        long total = currentMs();
        long sec = total / 1000L;

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(16 * dd, 12 * dd, 16 * dd, 10 * dd);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(14 * dd);
        box.setBackground(bg);

        TextView title = dialogLabel(context.getString(R.string.sbc_summary_title), dd);
        title.setTextSize(20f);
        title.setTypeface(null, Typeface.BOLD);
        box.addView(title);

        TextView sport = dialogLabel(matchType, dd);
        sport.setTextColor(Color.rgb(25, 118, 210));
        sport.setTextSize(14f);
        box.addView(sport);

        TextView result = dialogLabel(
                homeName + "   " + home + " : " + away + "   " + awayName,
                dd);
        result.setTextSize(18f);
        result.setTypeface(null, Typeface.BOLD);
        box.addView(result);

        TextView time = dialogLabel(
                context.getString(R.string.sbc_summary_time,
                        sec / 60L, sec % 60L, half, Math.max(1, matchHalves)),
                dd);
        box.addView(time);

        TextView style = dialogLabel(
                context.getString(R.string.sbc_scoreboard_value, scoreboardStyleLabel(
                        prefs.getString("scoreboard_style", "compact_tv"))),
                dd);
        style.setTextColor(Color.GRAY);
        box.addView(style);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(box)
                .setPositiveButton(context.getString(R.string.sbc_close), null)
                .setNeutralButton(context.getString(R.string.sbc_new_match), (di, w) -> showNewMatchDialog())
                .create();
        dialog.show();
        resizeDialog(dialog, 0.58f, 0.90f);
    }

    private String scoreboardStyleLabel(String value) {
        if ("corner".equals(value)) return context.getString(R.string.sbc_style_corner);
        if ("minimal".equals(value)) return context.getString(R.string.sbc_style_minimal);
        return context.getString(R.string.sbc_style_compact_tv);
    }

    private void confirmReset() {
        showNewMatchDialog();
    }

    public void reloadFromPrefs() {
        load();
        lastScoreboardStyle = null;
        lastTeamAColor = Integer.MIN_VALUE;
        lastTeamBColor = Integer.MIN_VALUE;
        render();
    }

    public void destroy() {
        if (goalPromptDialog != null && goalPromptDialog.isShowing()) goalPromptDialog.dismiss();
        if (running) {
            elapsedMs = currentMs();
            startedAt = System.currentTimeMillis();
        }
        save();
        clock.removeCallbacksAndMessages(null);
        detector.stop();
    }
}
