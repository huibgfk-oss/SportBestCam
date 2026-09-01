package com.fadcam.handball;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SportBestCam assisted Auto Goal detector.
 *
 * Build 0002.9.9 keeps assisted learning but makes prompts fullscreen-safe and
 * reduces false learning candidates. It replaces mandatory pre-match goal calibration with an assisted,
 * in-match learning flow:
 * 1) look for handball-goal geometry in the live rear preview;
 * 2) ask the operator whether the candidate is the left or right goal;
 * 3) look for a small moving ball candidate and ask once for confirmation;
 * 4) track the confirmed ball profile and emit a goal candidate only when the
 *    tracked trajectory crosses into a learned goal rectangle.
 *
 * The existing HandballMatchController remains the safety gate: a detected crossing
 * is still presented as a possible goal for operator confirmation. Occlusion by the
 * goalkeeper is treated conservatively; disappearance before a proven crossing does
 * not become an automatic goal.
 */
public final class GoalDetectionEngine implements SensorEventListener {
    public interface Listener { void onGoalCandidate(Side side, float confidence); }
    public enum Side { HOME, AWAY }

    private static final int W = 320;
    private static final int H = 180;
    private static final long INTERVAL_MS = 260L;
    private static final long GOAL_DISCOVERY_INTERVAL_MS = 900L;
    private static final long PROMPT_COOLDOWN_MS = 6500L;
    private static final long GOAL_COOLDOWN_MS = 5500L;
    private static final long TRACK_LOST_MS = 900L;
    private static final long OCCLUSION_DECISION_MS = 520L;

    private static final String PREFS = "handball_match";
    private static final String K_BALL_CONFIRMED = "auto_goal_ball_confirmed";
    private static final String K_BALL_H = "auto_goal_ball_h";
    private static final String K_BALL_S = "auto_goal_ball_s";
    private static final String K_BALL_V = "auto_goal_ball_v";
    private static final String K_BALL_SIZE = "auto_goal_ball_size";

    private final TextureView preview;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final SensorManager sensorManager;
    private final Context uiContext;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SportBestCam-AutoGoal");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicBoolean promptShowing = new AtomicBoolean(false);

    private volatile boolean running;
    private volatile boolean enabled;
    private volatile float gyroMag;
    private volatile boolean cameraDown;
    private volatile boolean openCvReady;
    private volatile boolean openCvAttempted;

    private Mat previousGray;
    private long lastGoalDiscoveryAt;
    private long lastPromptAt;
    private long lastCandidateAt;

    private GoalRect stableGoalCandidate;
    private int stableGoalHits;
    private MotionCandidate learningBallCandidate;
    private int learningBallHits;

    private boolean haveTrack;
    private float prevBallX;
    private float prevBallY;
    private float lastBallX;
    private float lastBallY;
    private long lastBallSeenAt;
    private Side pendingOccludedSide;
    private long pendingOccludedSince;

    public GoalDetectionEngine(TextureView preview, Listener listener) {
        this.preview = preview;
        this.listener = listener;
        this.uiContext = preview.getContext();
        Context app = preview.getContext().getApplicationContext();
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.sensorManager = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        this.enabled = prefs.getBoolean("auto_goal", false);
    }

    public void start() {
        if (running) return;
        running = true;
        registerSensors();
        main.post(loop);
    }

    public void stop() {
        running = false;
        main.removeCallbacks(loop);
        sensorManager.unregisterListener(this);
        inFlight.set(false);
        releasePreviousGray();
        resetTracking();
    }

    public void setEnabled(boolean value) {
        enabled = value;
        prefs.edit().putBoolean("auto_goal", value).apply();
        if (!value) {
            releasePreviousGray();
            resetTracking();
            stableGoalCandidate = null;
            stableGoalHits = 0;
        }
    }

    public boolean isEnabled() { return enabled; }

    /** Compatibility with the old manual calibration UI. */
    public boolean isCalibrated() { return valid("goal_lx") && valid("goal_rx"); }

    /** Manual calibration remains a fallback and now also creates a usable goal rectangle. */
    public void setGoalCenter(Side side, float x, float y) {
        String p = side == Side.HOME ? "goal_l" : "goal_r";
        float cx = clamp(x);
        float cy = clamp(y);
        float halfW = 0.105f;
        float halfH = 0.19f;
        prefs.edit()
                .putFloat(p + "x", cx)
                .putFloat(p + "y", cy)
                .putFloat(p + "_x1", clamp01(cx - halfW))
                .putFloat(p + "_y1", clamp01(cy - halfH))
                .putFloat(p + "_x2", clamp01(cx + halfW))
                .putFloat(p + "_y2", clamp01(cy + halfH))
                .apply();
    }

    public float[] getGoals() {
        return new float[]{
                prefs.getFloat("goal_lx", -1), prefs.getFloat("goal_ly", -1),
                prefs.getFloat("goal_rx", -1), prefs.getFloat("goal_ry", -1)
        };
    }

    private boolean valid(String k) { return prefs.getFloat(k, -1) >= 0; }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            tryScheduleFrame();
            if (running) main.postDelayed(this, INTERVAL_MS);
        }
    };

    private void tryScheduleFrame() {
        if (!enabled || cameraDown || gyroMag > 1.55f || preview == null || !preview.isAvailable()) return;
        if (preview.getWidth() <= 0 || preview.getHeight() <= 0) return;
        if (!inFlight.compareAndSet(false, true)) return;

        Bitmap bitmap = null;
        try {
            bitmap = preview.getBitmap(W, H);
        } catch (Throwable ignored) { }
        if (bitmap == null) {
            inFlight.set(false);
            return;
        }

        final Bitmap frame = bitmap;
        worker.execute(() -> {
            try {
                analyze(frame);
            } catch (Throwable ignored) {
            } finally {
                try { if (!frame.isRecycled()) frame.recycle(); } catch (Throwable ignored) { }
                inFlight.set(false);
            }
        });
    }

    private void analyze(Bitmap bitmap) {
        if (!ensureOpenCv() || !running || !enabled) return;

        Mat rgba = new Mat();
        Mat gray = new Mat();
        try {
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.GaussianBlur(gray, gray, new org.opencv.core.Size(5, 5), 0);

            long now = SystemClock.uptimeMillis();
            if (now - lastGoalDiscoveryAt >= GOAL_DISCOVERY_INTERVAL_MS && !bothGoalRectsKnown()) {
                lastGoalDiscoveryAt = now;
                GoalRect candidate = detectGoalGeometry(gray);
                processGoalLearning(candidate, bitmap, now);
            }

            if (previousGray != null && !previousGray.empty()) {
                boolean ballConfirmed = prefs.getBoolean(K_BALL_CONFIRMED, false);
                boolean haveGoal = goalRect(Side.HOME) != null || goalRect(Side.AWAY) != null;
                if (ballConfirmed || haveGoal) {
                    MotionCandidate ball = detectBallCandidate(rgba, gray, previousGray, ballConfirmed);
                    if (!ballConfirmed) {
                        processBallLearning(ball, bitmap, now);
                    } else {
                        processBallTracking(ball, now);
                    }
                }
            }

            releasePreviousGray();
            previousGray = gray.clone();
        } finally {
            rgba.release();
            gray.release();
        }
    }

    // -----------------------------------------------------------------------------------------
    // Goal learning
    // -----------------------------------------------------------------------------------------

    private GoalRect detectGoalGeometry(Mat gray) {
        Mat edges = new Mat();
        Mat lines = new Mat();
        try {
            Imgproc.Canny(gray, edges, 55, 165);
            Imgproc.HoughLinesP(edges, lines, 1, Math.PI / 180.0, 32, H * 0.16, 10);

            List<Line> vertical = new ArrayList<>();
            List<Line> horizontal = new ArrayList<>();
            for (int i = 0; i < lines.rows(); i++) {
                double[] v = lines.get(i, 0);
                if (v == null || v.length < 4) continue;
                Line l = new Line(v[0], v[1], v[2], v[3]);
                if (l.isVertical()) vertical.add(l);
                else if (l.isHorizontal()) horizontal.add(l);
            }

            GoalRect best = null;
            double bestScore = 0;
            for (int i = 0; i < vertical.size(); i++) {
                Line a = vertical.get(i);
                for (int j = i + 1; j < vertical.size(); j++) {
                    Line b = vertical.get(j);
                    double ax = a.midX(), bx = b.midX();
                    double left = Math.min(ax, bx), right = Math.max(ax, bx);
                    double sep = right - left;
                    if (sep < W * 0.10 || sep > W * 0.30) continue;
                    double center = (left + right) * 0.5;
                    // Handball footage is usually lateral: a goal should live mostly
                    // in the left or right half, not around center court.
                    if (center > W * 0.35 && center < W * 0.65) continue;
                    double top = Math.max(0, Math.min(a.minY(), b.minY()));
                    double bottom = Math.min(H - 1, Math.max(a.maxY(), b.maxY()));
                    double goalHeight = bottom - top;
                    if (goalHeight < H * 0.20 || goalHeight > H * 0.78) continue;

                    Line crossbar = null;
                    double crossScore = 0;
                    for (Line h : horizontal) {
                        if (h.maxX() < left - 8 || h.minX() > right + 8) continue;
                        double overlap = Math.min(h.maxX(), right) - Math.max(h.minX(), left);
                        if (overlap < sep * 0.50 || h.length() < sep * 0.55) continue;
                        double dy = Math.abs(h.midY() - top);
                        if (dy > H * 0.09) continue;
                        double score = h.length() - dy * 1.8;
                        if (score > crossScore) { crossScore = score; crossbar = h; }
                    }
                    if (crossbar == null) continue;

                    double score = a.length() + b.length() + crossbar.length() + sep * 0.7;
                    if (score > bestScore) {
                        bestScore = score;
                        int x1 = clampInt((int) Math.round(left - 4), 0, W - 2);
                        int x2 = clampInt((int) Math.round(right + 4), x1 + 1, W - 1);
                        int y1 = clampInt((int) Math.round(Math.min(top, crossbar.midY()) - 3), 0, H - 2);
                        int y2 = clampInt((int) Math.round(bottom + 4), y1 + 1, H - 1);
                        best = new GoalRect(null, x1, y1, x2, y2);
                    }
                }
            }
            return best;
        } finally {
            edges.release();
            lines.release();
        }
    }

    private void processGoalLearning(GoalRect candidate, Bitmap bitmap, long now) {
        if (candidate == null || matchesKnownGoal(candidate) || promptShowing.get()
                || now - lastPromptAt < PROMPT_COOLDOWN_MS) {
            if (candidate == null || matchesKnownGoal(candidate)) {
                stableGoalCandidate = null;
                stableGoalHits = 0;
            }
            return;
        }
        if (stableGoalCandidate != null && stableGoalCandidate.similar(candidate)) {
            stableGoalHits++;
            stableGoalCandidate = candidate;
        } else {
            stableGoalCandidate = candidate;
            stableGoalHits = 1;
        }
        if (stableGoalHits < 4) return;
        stableGoalHits = 0;
        stableGoalCandidate = null;
        lastPromptAt = now;
        Bitmap crop = safeCrop(bitmap, candidate.toRect(), 170);
        showGoalPrompt(candidate, crop);
    }

    private void showGoalPrompt(GoalRect candidate, Bitmap crop) {
        if (!promptShowing.compareAndSet(false, true)) {
            recycle(crop);
            return;
        }
        main.post(() -> {
            if (!running || !enabled) {
                promptShowing.set(false);
                recycle(crop);
                return;
            }
            try {
                Activity activity = findActivity(uiContext);
                if (!isUsable(activity)) {
                    promptShowing.set(false);
                    recycle(crop);
                    return;
                }
                AlertDialog.Builder b = new AlertDialog.Builder(activity)
                        .setTitle(tr("Goal detected", "Poartă detectată"))
                        .setMessage(tr("Which goal is this?", "Care poartă este aceasta?"));
                if (crop != null) {
                    ImageView image = new ImageView(activity);
                    image.setAdjustViewBounds(true);
                    image.setImageBitmap(crop);
                    image.setPadding(18, 8, 18, 8);
                    b.setView(image);
                }
                b.setPositiveButton(tr("LEFT", "STÂNGA"), (d, w) -> saveGoalRect(Side.HOME, candidate))
                        .setNeutralButton(tr("RIGHT", "DREAPTA"), (d, w) -> saveGoalRect(Side.AWAY, candidate))
                        .setNegativeButton(tr("Not a goal", "Nu este poartă"), null);
                AlertDialog dialog = b.create();
                dialog.setCanceledOnTouchOutside(false);
                dialog.setOnDismissListener(d -> {
                    promptShowing.set(false);
                    recycle(crop);
                });
                showAboveSportUi(dialog, activity);
            } catch (Throwable t) {
                promptShowing.set(false);
                recycle(crop);
            }
        });
    }

    private void saveGoalRect(Side side, GoalRect r) {
        String p = side == Side.HOME ? "goal_l" : "goal_r";
        float cx = ((r.x1 + r.x2) * 0.5f) / W;
        float cy = ((r.y1 + r.y2) * 0.5f) / H;
        prefs.edit()
                .putFloat(p + "x", clamp(cx))
                .putFloat(p + "y", clamp(cy))
                .putFloat(p + "_x1", r.x1 / (float) W)
                .putFloat(p + "_y1", r.y1 / (float) H)
                .putFloat(p + "_x2", r.x2 / (float) W)
                .putFloat(p + "_y2", r.y2 / (float) H)
                .apply();
        Toast.makeText(uiContext,
                side == Side.HOME ? tr("Left goal learned", "Poarta stângă memorată")
                        : tr("Right goal learned", "Poarta dreaptă memorată"),
                Toast.LENGTH_SHORT).show();
    }

    // -----------------------------------------------------------------------------------------
    // Ball learning + tracking
    // -----------------------------------------------------------------------------------------

    private MotionCandidate detectBallCandidate(Mat rgba, Mat gray, Mat previous, boolean profileConfirmed) {
        Mat diff = new Mat();
        Mat mask = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            Core.absdiff(gray, previous, diff);
            Imgproc.threshold(diff, mask, 24, 255, Imgproc.THRESH_BINARY);
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN,
                    Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new org.opencv.core.Size(3, 3)));
            Imgproc.dilate(mask, mask,
                    Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new org.opencv.core.Size(3, 3)));
            Imgproc.findContours(mask, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            MotionCandidate best = null;
            double bestScore = -1;
            float expectedSize = prefs.getFloat(K_BALL_SIZE, 0f);
            float expectedH = prefs.getFloat(K_BALL_H, 0f);
            float expectedS = prefs.getFloat(K_BALL_S, 0f);
            float expectedV = prefs.getFloat(K_BALL_V, 0f);

            Mat hsv = new Mat();
            Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGBA2RGB);
            Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV);
            try {
                for (MatOfPoint contour : contours) {
                    double area = Imgproc.contourArea(contour);
                    if (area < 6 || area > 160) continue;
                    Rect r = Imgproc.boundingRect(contour);
                    if (r.width < 2 || r.height < 2 || r.width > 18 || r.height > 18) continue;
                    double aspect = r.width / (double) Math.max(1, r.height);
                    if (aspect < 0.55 || aspect > 1.82) continue;
                    double fill = area / Math.max(1.0, r.width * (double) r.height);
                    if (fill < 0.32) continue;
                    double cx = r.x + r.width * 0.5;
                    double cy = r.y + r.height * 0.5;
                    if (insideKnownGoal((float) cx, (float) cy) && !profileConfirmed) continue;

                    Scalar mean = Core.mean(hsv.submat(r));
                    float h = (float) mean.val[0];
                    float s = (float) mean.val[1];
                    float v = (float) mean.val[2];
                    float normSize = (float) Math.sqrt(area) / Math.max(W, H);

                    double score = fill * 0.8 + Math.min(1.0, area / 45.0) * 0.25;
                    if (profileConfirmed) {
                        double sizePenalty = expectedSize > 0 ? Math.abs(normSize - expectedSize) / Math.max(.01, expectedSize) : 0;
                        double hue = hueDistance(h, expectedH) / 90.0;
                        double sat = Math.abs(s - expectedS) / 255.0;
                        double val = Math.abs(v - expectedV) / 255.0;
                        double colorPenalty = hue * 0.55 + sat * 0.25 + val * 0.20;
                        score += Math.max(-1.2, 1.4 - sizePenalty * 0.9 - colorPenalty * 1.1);
                        if (haveTrack) {
                            float vx = lastBallX - prevBallX;
                            float vy = lastBallY - prevBallY;
                            float px = lastBallX + vx;
                            float py = lastBallY + vy;
                            double dist = Math.hypot(cx - px, cy - py);
                            score += Math.max(-0.8, 1.1 - dist / 55.0);
                        }
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        best = new MotionCandidate(r, (float) cx, (float) cy, h, s, v, normSize, (float) score);
                    }
                }
            } finally {
                hsv.release();
            }
            if (profileConfirmed && bestScore < 0.40) return null;
            if (!profileConfirmed && bestScore < 0.42) return null;
            return best;
        } finally {
            diff.release();
            mask.release();
            for (MatOfPoint c : contours) c.release();
        }
    }

    private void processBallLearning(MotionCandidate candidate, Bitmap bitmap, long now) {
        if (candidate == null || promptShowing.get() || now - lastPromptAt < PROMPT_COOLDOWN_MS) {
            if (candidate == null) { learningBallCandidate = null; learningBallHits = 0; }
            return;
        }
        if (learningBallCandidate != null) {
            double movement = Math.hypot(candidate.cx - learningBallCandidate.cx,
                    candidate.cy - learningBallCandidate.cy);
            if (movement >= 1.0 && movement < 38.0) learningBallHits++;
            else learningBallHits = 1;
        } else {
            learningBallHits = 1;
        }
        learningBallCandidate = candidate;
        if (learningBallHits < 3) return;
        learningBallHits = 0;
        lastPromptAt = now;
        Bitmap crop = safeCrop(bitmap, expand(candidate.rect, 18), 180);
        showBallPrompt(candidate, crop);
    }

    private void showBallPrompt(MotionCandidate candidate, Bitmap crop) {
        if (!promptShowing.compareAndSet(false, true)) {
            recycle(crop);
            return;
        }
        main.post(() -> {
            if (!running || !enabled) {
                promptShowing.set(false);
                recycle(crop);
                return;
            }
            try {
                Activity activity = findActivity(uiContext);
                if (!isUsable(activity)) {
                    promptShowing.set(false);
                    recycle(crop);
                    return;
                }
                AlertDialog.Builder b = new AlertDialog.Builder(activity)
                        .setTitle(tr("Ball detected", "Minge detectată"))
                        .setMessage(tr("Is the highlighted object the match ball?", "Obiectul detectat este mingea de joc?"));
                if (crop != null) {
                    ImageView image = new ImageView(activity);
                    image.setAdjustViewBounds(true);
                    image.setImageBitmap(crop);
                    image.setPadding(18, 8, 18, 8);
                    b.setView(image);
                }
                b.setPositiveButton(tr("YES", "DA"), (d, w) -> confirmBall(candidate))
                        .setNegativeButton(tr("NO", "NU"), null);
                AlertDialog dialog = b.create();
                dialog.setCanceledOnTouchOutside(false);
                dialog.setOnDismissListener(d -> {
                    promptShowing.set(false);
                    recycle(crop);
                });
                showAboveSportUi(dialog, activity);
            } catch (Throwable t) {
                promptShowing.set(false);
                recycle(crop);
            }
        });
    }

    private void confirmBall(MotionCandidate c) {
        prefs.edit()
                .putBoolean(K_BALL_CONFIRMED, true)
                .putFloat(K_BALL_H, c.h)
                .putFloat(K_BALL_S, c.s)
                .putFloat(K_BALL_V, c.v)
                .putFloat(K_BALL_SIZE, c.normSize)
                .apply();
        resetTracking();
        Toast.makeText(uiContext, tr("Ball tracking ready", "Urmărirea mingii este pregătită"), Toast.LENGTH_SHORT).show();
    }

    private void processBallTracking(MotionCandidate ball, long now) {
        if (ball != null) {
            if (pendingOccludedSide != null) {
                GoalRect pending = goalRect(pendingOccludedSide);
                if (pending != null && pending.contains(ball.cx, ball.cy)) {
                    fire(pendingOccludedSide, 0.91f, now);
                }
                pendingOccludedSide = null;
                pendingOccludedSince = 0L;
            }

            if (!haveTrack || now - lastBallSeenAt > TRACK_LOST_MS) {
                prevBallX = ball.cx;
                prevBallY = ball.cy;
                lastBallX = ball.cx;
                lastBallY = ball.cy;
                haveTrack = true;
            } else {
                prevBallX = lastBallX;
                prevBallY = lastBallY;
                lastBallX = ball.cx;
                lastBallY = ball.cy;
                checkClearCrossing(prevBallX, prevBallY, lastBallX, lastBallY, now);
            }
            lastBallSeenAt = now;
            return;
        }

        if (!haveTrack) return;
        long missingFor = now - lastBallSeenAt;
        if (missingFor > TRACK_LOST_MS) {
            resetTracking();
            return;
        }

        // Goalkeeper/occlusion handling: only arm an ambiguous candidate if the
        // current velocity projects the already-tracked ball INSIDE a learned goal.
        // Simply disappearing near a goalkeeper is not enough.
        if (pendingOccludedSide == null && missingFor > 220) {
            float vx = lastBallX - prevBallX;
            float vy = lastBallY - prevBallY;
            float projectedX = lastBallX + vx * 1.35f;
            float projectedY = lastBallY + vy * 1.35f;
            Side side = projectedInsideGoal(projectedX, projectedY);
            if (side != null && !goalRect(side).contains(prevBallX, prevBallY)) {
                pendingOccludedSide = side;
                pendingOccludedSince = now;
            }
        } else if (pendingOccludedSide != null && now - pendingOccludedSince >= OCCLUSION_DECISION_MS) {
            // Ambiguous goalkeeper occlusion: emit only a medium-confidence candidate.
            // The existing controller asks the operator; it never silently changes score.
            fire(pendingOccludedSide, 0.70f, now);
            pendingOccludedSide = null;
            pendingOccludedSince = 0L;
            resetTracking();
        }
    }

    private void checkClearCrossing(float x0, float y0, float x1, float y1, long now) {
        for (Side side : Side.values()) {
            GoalRect g = goalRect(side);
            if (g == null) continue;
            boolean wasInside = g.contains(x0, y0);
            boolean nowInside = g.contains(x1, y1);
            if (!wasInside && nowInside && segmentEntersRect(x0, y0, x1, y1, g)) {
                fire(side, 0.96f, now);
                resetTracking();
                return;
            }
        }
    }

    private void fire(Side side, float confidence, long now) {
        if (side == null || listener == null || now - lastCandidateAt < GOAL_COOLDOWN_MS) return;
        lastCandidateAt = now;
        main.post(() -> {
            if (running && enabled) listener.onGoalCandidate(side, confidence);
        });
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private boolean ensureOpenCv() {
        if (openCvAttempted) return openCvReady;
        synchronized (this) {
            if (!openCvAttempted) {
                openCvReady = OpenCVLoader.initDebug();
                openCvAttempted = true;
            }
        }
        return openCvReady;
    }

    private boolean bothGoalRectsKnown() { return goalRect(Side.HOME) != null && goalRect(Side.AWAY) != null; }

    private GoalRect goalRect(Side side) {
        String p = side == Side.HOME ? "goal_l" : "goal_r";
        float x1 = prefs.getFloat(p + "_x1", -1);
        float y1 = prefs.getFloat(p + "_y1", -1);
        float x2 = prefs.getFloat(p + "_x2", -1);
        float y2 = prefs.getFloat(p + "_y2", -1);
        if (x1 >= 0 && y1 >= 0 && x2 > x1 && y2 > y1) {
            return new GoalRect(side,
                    clampInt(Math.round(x1 * W), 0, W - 2),
                    clampInt(Math.round(y1 * H), 0, H - 2),
                    clampInt(Math.round(x2 * W), 1, W - 1),
                    clampInt(Math.round(y2 * H), 1, H - 1));
        }
        float cx = prefs.getFloat(p + "x", -1);
        float cy = prefs.getFloat(p + "y", -1);
        if (cx < 0 || cy < 0) return null;
        int px = Math.round(cx * W), py = Math.round(cy * H);
        return new GoalRect(side,
                clampInt(px - Math.round(W * .105f), 0, W - 2),
                clampInt(py - Math.round(H * .19f), 0, H - 2),
                clampInt(px + Math.round(W * .105f), 1, W - 1),
                clampInt(py + Math.round(H * .19f), 1, H - 1));
    }

    private boolean insideKnownGoal(float x, float y) {
        GoalRect l = goalRect(Side.HOME), r = goalRect(Side.AWAY);
        return (l != null && l.contains(x, y)) || (r != null && r.contains(x, y));
    }

    /** Do not repeatedly ask about a goal that the operator has already identified. */
    private boolean matchesKnownGoal(GoalRect candidate) {
        if (candidate == null) return false;
        GoalRect l = goalRect(Side.HOME);
        GoalRect r = goalRect(Side.AWAY);
        return (l != null && candidate.similar(l)) || (r != null && candidate.similar(r));
    }

    /** Resolve the real Activity even when the TextureView uses a themed ContextWrapper. */
    private static Activity findActivity(Context context) {
        Context current = context;
        for (int i = 0; i < 12 && current != null; i++) {
            if (current instanceof Activity) return (Activity) current;
            if (!(current instanceof ContextWrapper)) break;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) break;
            current = base;
        }
        return null;
    }

    private static boolean isUsable(Activity activity) {
        if (activity == null || activity.isFinishing()) return false;
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed();
    }

    /**
     * Sport Mode runs immersive/fullscreen and contains TextureView/overlay layers.
     * A dialog built from the preview Context can therefore fail with a bad/no window token.
     * Build it from the host Activity and explicitly keep its window focusable/touchable.
     */
    private static void showAboveSportUi(AlertDialog dialog, Activity activity) {
        if (dialog == null || !isUsable(activity)) {
            throw new IllegalStateException("Sport Mode dialog has no usable Activity");
        }
        dialog.show();
        Window window = dialog.getWindow();
        if (window == null) return;
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setDimAmount(0.58f);
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        // Keep the dialog in the same immersive state as Sport Mode while ensuring
        // it remains the top, focusable application window.
        try {
            View activityDecor = activity.getWindow().getDecorView();
            window.getDecorView().setSystemUiVisibility(activityDecor.getSystemUiVisibility());
        } catch (Throwable ignored) { }
    }

    private Side projectedInsideGoal(float x, float y) {
        GoalRect l = goalRect(Side.HOME), r = goalRect(Side.AWAY);
        if (l != null && l.contains(x, y)) return Side.HOME;
        if (r != null && r.contains(x, y)) return Side.AWAY;
        return null;
    }

    private static boolean segmentEntersRect(float x0, float y0, float x1, float y1, GoalRect r) {
        if (r.contains(x1, y1)) {
            // Require meaningful motion so a stationary false-positive near the goal
            // cannot repeatedly trigger a crossing.
            return Math.hypot(x1 - x0, y1 - y0) >= 2.0;
        }
        return false;
    }

    private static double hueDistance(float a, float b) {
        double d = Math.abs(a - b);
        return Math.min(d, 180.0 - d);
    }

    private static Rect expand(Rect r, int px) {
        int x = Math.max(0, r.x - px);
        int y = Math.max(0, r.y - px);
        int right = Math.min(W, r.x + r.width + px);
        int bottom = Math.min(H, r.y + r.height + px);
        return new Rect(x, y, Math.max(1, right - x), Math.max(1, bottom - y));
    }

    private static Bitmap safeCrop(Bitmap source, Rect r, int target) {
        if (source == null || source.isRecycled() || r == null) return null;
        try {
            int x = clampInt(r.x, 0, source.getWidth() - 1);
            int y = clampInt(r.y, 0, source.getHeight() - 1);
            int w = clampInt(r.width, 1, source.getWidth() - x);
            int h = clampInt(r.height, 1, source.getHeight() - y);
            Bitmap crop = Bitmap.createBitmap(source, x, y, w, h);
            int outW = target;
            int outH = Math.max(1, Math.round(target * (h / (float) w)));
            Bitmap scaled = Bitmap.createScaledBitmap(crop, outW, outH, true);
            if (scaled != crop) crop.recycle();
            return scaled;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void recycle(Bitmap b) {
        try { if (b != null && !b.isRecycled()) b.recycle(); } catch (Throwable ignored) { }
    }

    private void releasePreviousGray() {
        if (previousGray != null) {
            previousGray.release();
            previousGray = null;
        }
    }

    private void resetTracking() {
        haveTrack = false;
        prevBallX = prevBallY = lastBallX = lastBallY = 0f;
        lastBallSeenAt = 0L;
        pendingOccludedSide = null;
        pendingOccludedSince = 0L;
    }

    private String tr(String en, String ro) {
        try {
            Locale locale = uiContext.getResources().getConfiguration().getLocales().get(0);
            if (locale != null && "ro".equalsIgnoreCase(locale.getLanguage())) return ro;
        } catch (Throwable ignored) { }
        return en;
    }

    private void registerSensors() {
        Sensor g = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor a = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (g != null) sensorManager.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME);
        if (a != null) sensorManager.registerListener(this, a, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            gyroMag = (float) Math.sqrt(e.values[0] * e.values[0]
                    + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
        } else if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float z = Math.abs(e.values[2]);
            float xy = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1]);
            cameraDown = z > 7.7f && xy < 6.2f;
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private static float clamp(float v) { return Math.max(.05f, Math.min(.95f, v)); }
    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static int clampInt(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static final class Line {
        final double x1, y1, x2, y2;
        Line(double x1, double y1, double x2, double y2) { this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; }
        double dx() { return x2 - x1; }
        double dy() { return y2 - y1; }
        double length() { return Math.hypot(dx(), dy()); }
        double midX() { return (x1 + x2) * .5; }
        double midY() { return (y1 + y2) * .5; }
        double minX() { return Math.min(x1, x2); }
        double maxX() { return Math.max(x1, x2); }
        double minY() { return Math.min(y1, y2); }
        double maxY() { return Math.max(y1, y2); }
        boolean isVertical() { return Math.abs(dx()) <= Math.max(3.0, Math.abs(dy()) * .24) && length() >= H * .14; }
        boolean isHorizontal() { return Math.abs(dy()) <= Math.max(3.0, Math.abs(dx()) * .24) && length() >= W * .08; }
    }

    private static final class GoalRect {
        final Side side;
        final int x1, y1, x2, y2;
        GoalRect(Side side, int x1, int y1, int x2, int y2) {
            this.side = side; this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
        }
        boolean contains(float x, float y) { return x >= x1 && x <= x2 && y >= y1 && y <= y2; }
        Rect toRect() { return new Rect(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1)); }
        boolean similar(GoalRect o) {
            if (o == null) return false;
            double c1x = (x1 + x2) * .5, c1y = (y1 + y2) * .5;
            double c2x = (o.x1 + o.x2) * .5, c2y = (o.y1 + o.y2) * .5;
            double d = Math.hypot(c1x - c2x, c1y - c2y);
            double w = Math.max(1, x2 - x1), h = Math.max(1, y2 - y1);
            double ow = Math.max(1, o.x2 - o.x1), oh = Math.max(1, o.y2 - o.y1);
            return d < 24 && Math.abs(w - ow) < 34 && Math.abs(h - oh) < 38;
        }
    }

    private static final class MotionCandidate {
        final Rect rect;
        final float cx, cy, h, s, v, normSize, score;
        MotionCandidate(Rect rect, float cx, float cy, float h, float s, float v, float normSize, float score) {
            this.rect = rect; this.cx = cx; this.cy = cy; this.h = h; this.s = s; this.v = v;
            this.normSize = normSize; this.score = score;
        }
    }
}
