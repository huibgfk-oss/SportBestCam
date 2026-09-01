package com.fadcam.handball;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.fadcam.R;

import java.util.Locale;

/** Minimal SportBestCam HUD used by FullscreenPreviewActivity. */
public final class FullscreenHandballController {
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView homeNameView;
    private final TextView awayNameView;
    private final TextView homeScoreView;
    private final TextView awayScoreView;
    private final TextView timerView;
    private final TextView halfView;
    private final View hudBar;

    private String lastScoreboardStyle;
    private int lastTeamAColor = Integer.MIN_VALUE;
    private int lastTeamBColor = Integer.MIN_VALUE;

    private int home;
    private int away;
    private int half;
    private long elapsedMs;
    private long startedAt;
    private boolean running;
    private boolean pausedByVideo;
    private boolean videoRecordingRunning;
    private boolean matchRecording;
    private String homeName;
    private String awayName;

    public FullscreenHandballController(Activity activity) {
        context = activity;
        prefs = activity.getSharedPreferences("handball_match", Context.MODE_PRIVATE);
        homeNameView = activity.findViewById(R.id.fs_hb_home_name);
        awayNameView = activity.findViewById(R.id.fs_hb_away_name);
        homeScoreView = activity.findViewById(R.id.fs_hb_home_score);
        awayScoreView = activity.findViewById(R.id.fs_hb_away_score);
        timerView = activity.findViewById(R.id.fs_hb_timer);
        halfView = activity.findViewById(R.id.fs_hb_half);
        hudBar = activity.findViewById(R.id.fs_hb_bar);

        bindScore(homeScoreView, true);
        bindScore(awayScoreView, false);
        if (timerView != null) {
            timerView.setClickable(true);
            timerView.setFocusable(true);
            timerView.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                toggleClock();
            });
        }
        if (halfView != null) {
            halfView.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                int halves = Math.max(1, prefs.getInt("match_halves", 2));
                half = half >= halves ? 1 : half + 1;
                save();
                render();
            });
        }
        reload();
    }

    private void bindScore(TextView view, boolean isHome) {
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
        v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(80L)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(100L).start())
                .start();
    }

    public void reload() {
        home = prefs.getInt("home", 0);
        away = prefs.getInt("away", 0);
        half = prefs.getInt("half", 1);
        elapsedMs = prefs.getLong("elapsed", 0L);
        running = prefs.getBoolean("running", false);
        pausedByVideo = prefs.getBoolean("timer_paused_by_video", false);
        videoRecordingRunning = prefs.getBoolean("video_recording_running", false);
        startedAt = prefs.getLong("started_at", System.currentTimeMillis());
        homeName = SportRecordingProfile.teamA(context, prefs);
        awayName = SportRecordingProfile.teamB(context, prefs);
        matchRecording = SportRecordingProfile.isMatchRecording(prefs);
        lastScoreboardStyle = null;
        lastTeamAColor = Integer.MIN_VALUE;
        lastTeamBColor = Integer.MIN_VALUE;
        render();
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

    private void toggleClock() {
        if (!matchRecording) {
            Toast.makeText(context, context.getString(R.string.sbc_match_timer_unavailable_simple), Toast.LENGTH_SHORT).show();
            return;
        }
        if (running) {
            elapsedMs = currentMs();
            running = false;
            pausedByVideo = false;
        } else {
            if (!videoRecordingRunning) {
                Toast.makeText(context, context.getString(R.string.sbc_timer_requires_video), Toast.LENGTH_SHORT).show();
                return;
            }
            startedAt = System.currentTimeMillis();
            running = true;
            pausedByVideo = false;
        }
        save();
        render();
    }

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
            pausedByVideo = true;
        }
        save();
        render();
    }

    private long currentMs() {
        return elapsedMs + (running ? Math.max(0L, System.currentTimeMillis() - startedAt) : 0L);
    }

    private void save() {
        prefs.edit()
                .putInt("home", home)
                .putInt("away", away)
                .putInt("half", half)
                .putLong("elapsed", elapsedMs)
                .putBoolean("running", running)
                .putLong("started_at", startedAt)
                .putBoolean("timer_paused_by_video", pausedByVideo)
                .putBoolean("video_recording_running", videoRecordingRunning)
                .putBoolean("video_overlay_enabled", true)
                .apply();
    }

    private final Runnable tick = this::render;

    private void applyScoreboardStyle() {
        if (hudBar == null) return;
        String style = prefs.getString("scoreboard_style", "compact_tv");
        int colorA = prefs.getInt("team_a_color", Color.rgb(33, 150, 243));
        int colorB = prefs.getInt("team_b_color", Color.rgb(46, 125, 50));

        if (style.equals(lastScoreboardStyle)
                && colorA == lastTeamAColor
                && colorB == lastTeamBColor) return;

        lastScoreboardStyle = style;
        lastTeamAColor = colorA;
        lastTeamBColor = colorB;

        int d = Math.max(1, (int) hudBar.getResources().getDisplayMetrics().density);
        if (hudBar.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) hudBar.getLayoutParams();
            if ("corner".equals(style)) {
                lp.width = (int) (hudBar.getResources().getDisplayMetrics().widthPixels * 0.68f);
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.leftMargin = 16 * d;
                lp.rightMargin = 0;
            } else {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.leftMargin = 72 * d;
                lp.rightMargin = 72 * d;
            }
            hudBar.setLayoutParams(lp);
        }

        hudBar.setTranslationX(8f * d);

        if ("minimal".equals(style)) {
            hudBar.setBackground(rounded(Color.argb(70, 0, 0, 0), 0, Color.TRANSPARENT));
        } else if ("corner".equals(style)) {
            hudBar.setBackground(rounded(Color.argb(235, 12, 14, 17), 1, Color.argb(90, 255, 255, 255)));
        } else {
            hudBar.setBackground(rounded(Color.argb(230, 27, 30, 35), 1, Color.argb(90, 255, 255, 255)));
        }

        if (homeScoreView != null) homeScoreView.setBackground(scoreBox(colorA, "minimal".equals(style) ? 150 : 195));
        if (awayScoreView != null) awayScoreView.setBackground(scoreBox(colorB, "minimal".equals(style) ? 150 : 195));
        if (timerView != null) timerView.setBackground(
                "minimal".equals(style)
                        ? rounded(Color.TRANSPARENT, 0, Color.TRANSPARENT)
                        : rounded(Color.argb(125, 50, 54, 60), 0, Color.TRANSPARENT));
        if (halfView != null) halfView.setBackground(
                "minimal".equals(style)
                        ? rounded(Color.TRANSPARENT, 0, Color.TRANSPARENT)
                        : rounded(Color.argb(125, 50, 54, 60), 0, Color.TRANSPARENT));
    }

    private GradientDrawable rounded(int fill, int strokeDp, int strokeColor) {
        int d = Math.max(1, (int) hudBar.getResources().getDisplayMetrics().density);
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(12 * d);
        if (strokeDp > 0) g.setStroke(Math.max(1, strokeDp * d), strokeColor);
        return g;
    }

    private GradientDrawable scoreBox(int color, int alpha) {
        int d = Math.max(1, (int) hudBar.getResources().getDisplayMetrics().density);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)));
        g.setCornerRadius(10 * d);
        g.setStroke(Math.max(1, d), Color.argb(70, 255, 255, 255));
        return g;
    }

    private void render() {
        matchRecording = SportRecordingProfile.isMatchRecording(prefs);
        if (!matchRecording) {
            handler.removeCallbacks(tick);
            for (View v : new View[]{homeNameView, awayNameView, homeScoreView, awayScoreView, timerView, halfView}) {
                if (v != null) v.setVisibility(View.GONE);
            }
            if (hudBar != null) {
                if (hudBar instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) hudBar;
                    // In Simple video mode keep only the Back control. Hiding only
                    // the named score views would leave the two anonymous separator
                    // bars visible in fullscreen.
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        if (child != null) child.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
                    }
                }
                if (hudBar.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) hudBar.getLayoutParams();
                    lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                    lp.rightMargin = 0;
                    hudBar.setLayoutParams(lp);
                }
                hudBar.setTranslationX(0f);
                hudBar.setBackgroundColor(Color.TRANSPARENT);
            }
            return;
        }

        for (View v : new View[]{homeNameView, awayNameView, homeScoreView, awayScoreView, timerView, halfView}) {
            if (v != null) v.setVisibility(View.VISIBLE);
        }
        if (hudBar instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) hudBar;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child != null) child.setVisibility(View.VISIBLE);
            }
        }
        applyScoreboardStyle();
        if (homeNameView != null) { homeNameView.setText(homeName); homeNameView.setContentDescription(homeName); }
        if (awayNameView != null) { awayNameView.setText(awayName); awayNameView.setContentDescription(awayName); }
        if (homeScoreView != null) homeScoreView.setText(String.valueOf(home));
        if (awayScoreView != null) awayScoreView.setText(String.valueOf(away));
        long sec = currentMs() / 1000L;
        String timeText = String.format(Locale.ROOT, "%02d:%02d", sec / 60L, sec % 60L);
        if (!running && elapsedMs > 0L) timeText = "▶ " + timeText;
        if (timerView != null) {
            timerView.setText(timeText);
            timerView.setTextColor(running || elapsedMs == 0L ? 0xFFFFFFFF : 0xFFFFC107);
            timerView.setContentDescription(context.getString(running
                    ? R.string.sbc_timer_pause_cd
                    : R.string.sbc_timer_resume_cd));
        }
        if (halfView != null) halfView.setText(context.getString(R.string.sbc_period_short, half));
        handler.removeCallbacks(tick);
        if (running) handler.postDelayed(tick, 250L);
    }

    public void destroy() {
        handler.removeCallbacksAndMessages(null);
    }
}
