package com.fadcam.handball;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.fadcam.R;
import com.fadcam.dualcam.DualCameraCapability;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfInt4;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local-only gesture scoring for SportBestCam.
 *
 * Build 0002.8.4 supports two explicit gesture sources:
 * - FRONT_CONCURRENT: hidden front-camera analysis while the rear camera records;
 * - REAR_HIDDEN: fallback analysis from the normal rear preview while the encoder
 *   center-crops the final video so the outer gesture zones are not recorded.
 *
 * The source is user-selectable. Rear hidden-zone cropping is enabled only when
 * REAR_HIDDEN is selected before REC starts; otherwise recording geometry is unchanged.
 *
 * Safety rules remain conservative:
 * - only the outer left/right control zones are accepted;
 * - the hand must be large/near the camera and enter from an outside/bottom edge;
 * - one raised finger means +1, two raised fingers means -1;
 * - the same gesture must remain stable for the configured hold time;
 * - one gesture fires only once and the hand must be lowered before re-arming.
 */
public final class GestureScoringEngine {
    public enum Side { HOME, AWAY }

    public interface Listener {
        void onGestureScore(Side side, int delta, float confidence);
    }

    public interface ActivityGate {
        boolean isGestureScoringActive();
    }

    public static final String KEY_ENABLED = "gesture_scoring";
    public static final String KEY_HOLD_MS = "gesture_hold_ms";
    public static final String KEY_VIBRATION = "gesture_vibration";
    public static final String KEY_SOURCE = "gesture_source";
    public static final String SOURCE_FRONT_CONCURRENT = "front_concurrent";
    public static final String SOURCE_REAR_HIDDEN = "rear_hidden";

    // Must stay in sync with GLWatermarkRenderer.REAR_GESTURE_CROP_FRACTION.
    // The detector uses a slightly smaller safe zone so a recognised hand is
    // fully inside the part removed from the encoded MP4.
    public static final float REAR_HIDDEN_CROP_FRACTION = 0.125f;
    private static final float REAR_HIDDEN_SAFE_ZONE = 0.115f;

    public static final long DEFAULT_HOLD_MS = 800L;
    private static final long SAMPLE_INTERVAL_MS = 200L;
    private static final long FRAME_MIN_INTERVAL_MS = 175L;
    private static final long BRIEF_DROPOUT_TOLERANCE_MS = 340L;
    private static final long RELEASE_TO_REARM_MS = 620L;
    private static final long CAMERA_RETRY_MS = 700L;
    private static final int MAX_ANALYSIS_EDGE = 320;
    private static final float LEFT_ZONE_END = 0.38f;
    private static final float RIGHT_ZONE_START = 0.62f;

    // Constructor anchor used by HomeFragment and FullscreenPreviewActivity.
    // FRONT_CONCURRENT ignores these pixels; REAR_HIDDEN intentionally analyses them.
    private final TextureView hostView;
    private final Context appContext;
    private final Listener listener;
    private final ActivityGate activityGate;
    private final SharedPreferences prefs;
    private final DualCameraCapability dualCameraCapability;
    private final CameraManager cameraManager;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SportBestCam-GestureScoring");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicReference<FrontFrame> latestFrame = new AtomicReference<>();
    private final Object cameraLock = new Object();

    private volatile boolean running;
    private volatile boolean destroyed;
    private volatile boolean openCvReady;
    private volatile boolean openCvInitAttempted;
    private volatile boolean captureActive;
    private volatile boolean frontCameraOpening;
    private volatile boolean frontCameraReady;
    private volatile int frontSensorOrientation;
    private volatile int analysisRotationDegrees;
    private volatile long cameraRetryAfterUptime;
    private volatile long lastFrameAcceptedUptime;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice frontCamera;
    private CameraCaptureSession frontSession;
    private ImageReader frontReader;

    private boolean unsupportedNotified;
    private boolean runtimeFailureNotified;

    private Side candidateSide;
    private int candidateFingers;
    private long candidateSince;
    private long lastPositiveSeenAt;
    private boolean armed = true;
    private long neutralSince;

    public GestureScoringEngine(TextureView preview, ActivityGate activityGate, Listener listener) {
        this.hostView = preview;
        this.activityGate = activityGate;
        this.listener = listener;
        this.appContext = preview.getContext().getApplicationContext();
        this.prefs = appContext.getSharedPreferences("handball_match", Context.MODE_PRIVATE);
        this.dualCameraCapability = new DualCameraCapability(appContext);
        this.cameraManager = (CameraManager) appContext.getSystemService(Context.CAMERA_SERVICE);
    }

    /** Capability check for the explicit front-camera gesture source. */
    public static boolean isFrontGestureCameraSupported(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        try {
            DualCameraCapability capability = new DualCameraCapability(context);
            return capability.isSupported()
                    && capability.isConcurrentApiConfirmed()
                    && capability.getConcurrentFrontCameraId() != null
                    && capability.getConcurrentBackCameraId() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String getSelectedSource(SharedPreferences prefs) {
        if (prefs == null) return SOURCE_FRONT_CONCURRENT;
        String source = prefs.getString(KEY_SOURCE, SOURCE_FRONT_CONCURRENT);
        return SOURCE_REAR_HIDDEN.equals(source) ? SOURCE_REAR_HIDDEN : SOURCE_FRONT_CONCURRENT;
    }

    public static boolean isRearHiddenSource(SharedPreferences prefs) {
        return SOURCE_REAR_HIDDEN.equals(getSelectedSource(prefs));
    }

    public void start() {
        if (destroyed || running) return;
        running = true;
        unsupportedNotified = false;
        runtimeFailureNotified = false;
        main.post(loop);
    }

    public void stop() {
        running = false;
        captureActive = false;
        main.removeCallbacks(loop);
        resetCandidate();
        closeFrontCamera();
        latestFrame.set(null);
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        stop();
        worker.shutdownNow();
        stopCameraThread();
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!running || destroyed) return;
            tryScheduleAnalysis();
            if (running && !destroyed) main.postDelayed(this, SAMPLE_INTERVAL_MS);
        }
    };

    private void tryScheduleAnalysis() {
        final boolean enabled = prefs.getBoolean(KEY_ENABLED, false);
        final boolean matchRecording = SportRecordingProfile.isMatchRecording(prefs);

        if (!enabled || !matchRecording) {
            captureActive = false;
            closeFrontCamera();
            noteNeutral(System.currentTimeMillis());
            return;
        }

        if (!isActivityAllowed()) {
            captureActive = false;
            closeFrontCamera();
            noteNeutral(System.currentTimeMillis());
            return;
        }

        final String source = getSelectedSource(prefs);
        if (SOURCE_REAR_HIDDEN.equals(source)) {
            captureActive = false;
            closeFrontCamera();
            scheduleRearPreviewAnalysis();
            return;
        }

        // Front-camera mode remains available for phones that officially support
        // concurrent front+rear cameras. Unsupported hardware does NOT disable the
        // master Gesture Scoring preference, so the user can select Rear hidden zone.
        if (!hasStrictConcurrentFrontSupport()) {
            captureActive = false;
            closeFrontCamera();
            notifyFrontUnsupported();
            noteNeutral(System.currentTimeMillis());
            return;
        }

        captureActive = true;
        analysisRotationDegrees = computeFrontRotationDegrees();
        ensureFrontCameraOpen();

        if (!frontCameraReady) {
            noteNeutral(System.currentTimeMillis());
            return;
        }

        if (!inFlight.compareAndSet(false, true)) return;
        FrontFrame frame = latestFrame.getAndSet(null);
        if (frame == null) {
            inFlight.set(false);
            noteNeutral(System.currentTimeMillis());
            return;
        }

        worker.execute(() -> {
            Detection detection = null;
            try {
                if (ensureOpenCv()) detection = detect(frame);
            } catch (Throwable ignored) {
            } finally {
                inFlight.set(false);
            }
            final Detection result = detection;
            main.post(() -> process(result));
        });
    }

    private void scheduleRearPreviewAnalysis() {
        if (hostView == null || !hostView.isAvailable()
                || hostView.getWidth() <= 0 || hostView.getHeight() <= 0) {
            noteNeutral(System.currentTimeMillis());
            return;
        }
        if (!inFlight.compareAndSet(false, true)) return;

        Bitmap bitmap = null;
        try {
            int srcW = Math.max(1, hostView.getWidth());
            int srcH = Math.max(1, hostView.getHeight());
            float scale = Math.min(1f, MAX_ANALYSIS_EDGE / (float) Math.max(srcW, srcH));
            int targetW = Math.max(2, Math.round(srcW * scale));
            int targetH = Math.max(2, Math.round(srcH * scale));
            bitmap = hostView.getBitmap(targetW, targetH);
        } catch (Throwable ignored) {
        }

        if (bitmap == null) {
            inFlight.set(false);
            noteNeutral(System.currentTimeMillis());
            return;
        }

        final Bitmap frameBitmap = bitmap;
        worker.execute(() -> {
            Detection detection = null;
            try {
                if (ensureOpenCv()) detection = detect(frameBitmap, true);
            } catch (Throwable ignored) {
            } finally {
                try {
                    if (!frameBitmap.isRecycled()) frameBitmap.recycle();
                } catch (Throwable ignored) {
                }
                inFlight.set(false);
            }
            final Detection result = detection;
            main.post(() -> process(result));
        });
    }

    private boolean hasStrictConcurrentFrontSupport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || cameraManager == null) return false;
        try {
            return dualCameraCapability.isSupported()
                    && dualCameraCapability.isConcurrentApiConfirmed()
                    && dualCameraCapability.getConcurrentFrontCameraId() != null
                    && dualCameraCapability.getConcurrentBackCameraId() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void notifyFrontUnsupported() {
        if (unsupportedNotified) return;
        unsupportedNotified = true;
        Toast.makeText(appContext,
                appContext.getString(R.string.sbc_gesture_front_unsupported),
                Toast.LENGTH_LONG).show();
    }

    private void notifyRuntimeFailureOnce() {
        if (runtimeFailureNotified || !running || destroyed) return;
        runtimeFailureNotified = true;
        main.post(() -> {
            if (!destroyed) {
                Toast.makeText(appContext,
                        appContext.getString(R.string.sbc_gesture_front_runtime_error),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private boolean isActivityAllowed() {
        if (activityGate == null) return true;
        try {
            return activityGate.isGestureScoringActive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean ensureOpenCv() {
        if (openCvInitAttempted) return openCvReady;
        synchronized (this) {
            if (!openCvInitAttempted) {
                openCvReady = OpenCVLoader.initDebug();
                openCvInitAttempted = true;
            }
        }
        return openCvReady;
    }

    private void ensureFrontCameraOpen() {
        if (!captureActive || destroyed || frontCameraReady || frontCameraOpening) return;
        if (SystemClock.uptimeMillis() < cameraRetryAfterUptime) return;
        if (cameraManager == null) return;
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            notifyRuntimeFailureOnce();
            return;
        }

        final String frontId = dualCameraCapability.getConcurrentFrontCameraId();
        if (frontId == null) {
            notifyFrontUnsupported();
            return;
        }

        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(frontId);
            Integer sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            frontSensorOrientation = sensor == null ? 0 : sensor;
            Size analysisSize = chooseAnalysisSize(characteristics);
            if (analysisSize == null) {
                scheduleCameraRetry(true);
                return;
            }

            ensureCameraThread();
            synchronized (cameraLock) {
                if (frontReader == null) {
                    frontReader = ImageReader.newInstance(
                            analysisSize.getWidth(),
                            analysisSize.getHeight(),
                            ImageFormat.YUV_420_888,
                            2);
                    frontReader.setOnImageAvailableListener(this::onFrontImageAvailable, cameraHandler);
                }
                frontCameraOpening = true;
            }

            cameraManager.openCamera(frontId, cameraStateCallback, cameraHandler);
        } catch (SecurityException e) {
            frontCameraOpening = false;
            scheduleCameraRetry(true);
        } catch (CameraAccessException | IllegalArgumentException e) {
            frontCameraOpening = false;
            scheduleCameraRetry(true);
        } catch (Throwable t) {
            frontCameraOpening = false;
            scheduleCameraRetry(true);
        }
    }

    private Size chooseAnalysisSize(CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return null;
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) return null;

        Size best = null;
        long bestScore = Long.MAX_VALUE;
        final long targetPixels = 320L * 240L;
        for (Size size : sizes) {
            if (size == null || size.getWidth() < 160 || size.getHeight() < 120) continue;
            long pixels = (long) size.getWidth() * size.getHeight();
            long score = Math.abs(pixels - targetPixels);
            // Strongly prefer small analysis streams. They reduce thermal/CPU impact and
            // are more likely to fit the device's guaranteed concurrent-camera budget.
            if (pixels > 640L * 480L) score += (pixels - 640L * 480L) * 4L;
            if (best == null || score < bestScore) {
                best = size;
                bestScore = score;
            }
        }
        if (best != null) return best;

        // Last resort: use the smallest advertised YUV stream.
        best = sizes[0];
        for (Size size : sizes) {
            if ((long) size.getWidth() * size.getHeight()
                    < (long) best.getWidth() * best.getHeight()) {
                best = size;
            }
        }
        return best;
    }

    private void ensureCameraThread() {
        synchronized (cameraLock) {
            if (cameraThread != null && cameraThread.isAlive() && cameraHandler != null) return;
            cameraThread = new HandlerThread("SportBestCam-GestureFrontCamera");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            synchronized (cameraLock) {
                if (!running || destroyed || !captureActive) {
                    frontCameraOpening = false;
                    camera.close();
                    return;
                }
                frontCamera = camera;
                frontCameraOpening = false;
            }
            createFrontCaptureSession(camera);
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            synchronized (cameraLock) {
                if (frontCamera == camera) frontCamera = null;
                frontCameraOpening = false;
                frontCameraReady = false;
            }
            scheduleCameraRetry(false);
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            synchronized (cameraLock) {
                if (frontCamera == camera) frontCamera = null;
                frontCameraOpening = false;
                frontCameraReady = false;
            }
            scheduleCameraRetry(true);
        }
    };

    private void createFrontCaptureSession(CameraDevice camera) {
        final ImageReader reader;
        synchronized (cameraLock) {
            reader = frontReader;
        }
        if (reader == null || destroyed || !captureActive) {
            closeFrontCamera();
            return;
        }

        try {
            CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(reader.getSurface());
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            camera.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (destroyed || !running || !captureActive) {
                                session.close();
                                return;
                            }
                            synchronized (cameraLock) {
                                frontSession = session;
                            }
                            try {
                                session.setRepeatingRequest(builder.build(), null, cameraHandler);
                                frontCameraReady = true;
                                runtimeFailureNotified = false;
                            } catch (CameraAccessException | IllegalStateException e) {
                                frontCameraReady = false;
                                scheduleCameraRetry(true);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            session.close();
                            frontCameraReady = false;
                            scheduleCameraRetry(true);
                        }
                    }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            frontCameraReady = false;
            scheduleCameraRetry(true);
        }
    }

    private void onFrontImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || !running || destroyed || !captureActive || !frontCameraReady) return;

            long now = SystemClock.uptimeMillis();
            if (now - lastFrameAcceptedUptime < FRAME_MIN_INTERVAL_MS) return;
            lastFrameAcceptedUptime = now;

            byte[] nv21 = yuv420888ToNv21(image);
            if (nv21 == null) return;
            latestFrame.set(new FrontFrame(
                    nv21,
                    image.getWidth(),
                    image.getHeight(),
                    analysisRotationDegrees));
        } catch (Throwable ignored) {
        } finally {
            if (image != null) image.close();
        }
    }

    private byte[] yuv420888ToNv21(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) return null;
        int width = image.getWidth();
        int height = image.getHeight();
        if ((width & 1) != 0 || (height & 1) != 0) return null;

        byte[] out = new byte[width * height * 3 / 2];
        ByteBuffer y = planes[0].getBuffer();
        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int offset = 0;
        for (int row = 0; row < height; row++) {
            int rowBase = row * yRowStride;
            for (int col = 0; col < width; col++) {
                out[offset++] = y.get(rowBase + col * yPixelStride);
            }
        }

        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();
        int chromaH = height / 2;
        int chromaW = width / 2;
        for (int row = 0; row < chromaH; row++) {
            int uBase = row * uRowStride;
            int vBase = row * vRowStride;
            for (int col = 0; col < chromaW; col++) {
                out[offset++] = v.get(vBase + col * vPixelStride);
                out[offset++] = u.get(uBase + col * uPixelStride);
            }
        }
        return out;
    }

    private void scheduleCameraRetry(boolean notify) {
        cameraRetryAfterUptime = SystemClock.uptimeMillis() + CAMERA_RETRY_MS;
        frontCameraOpening = false;
        frontCameraReady = false;
        closeFrontCamera();
        if (notify) notifyRuntimeFailureOnce();
    }

    private void closeFrontCamera() {
        synchronized (cameraLock) {
            frontCameraReady = false;
            frontCameraOpening = false;
            try {
                if (frontSession != null) frontSession.close();
            } catch (Throwable ignored) {
            }
            frontSession = null;
            try {
                if (frontCamera != null) frontCamera.close();
            } catch (Throwable ignored) {
            }
            frontCamera = null;
            try {
                if (frontReader != null) frontReader.close();
            } catch (Throwable ignored) {
            }
            frontReader = null;
            latestFrame.set(null);
        }
    }

    private void stopCameraThread() {
        synchronized (cameraLock) {
            if (cameraThread != null) {
                try {
                    cameraThread.quitSafely();
                } catch (Throwable ignored) {
                }
            }
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private int computeFrontRotationDegrees() {
        int displayDegrees = 0;
        try {
            Display display = hostView == null ? null : hostView.getDisplay();
            int rotation = display == null ? Surface.ROTATION_0 : display.getRotation();
            if (rotation == Surface.ROTATION_90) displayDegrees = 90;
            else if (rotation == Surface.ROTATION_180) displayDegrees = 180;
            else if (rotation == Surface.ROTATION_270) displayDegrees = 270;
        } catch (Throwable ignored) {
        }
        // Front-camera relative rotation follows Android's front-lens convention.
        return (frontSensorOrientation + displayDegrees) % 360;
    }

    private void process(Detection detection) {
        if (!running || destroyed) return;
        long now = System.currentTimeMillis();
        if (detection == null || detection.fingers < 1 || detection.fingers > 2) {
            noteNeutral(now);
            return;
        }

        neutralSince = 0L;
        lastPositiveSeenAt = now;

        if (!armed) return;
        if (candidateSide != detection.side || candidateFingers != detection.fingers) {
            candidateSide = detection.side;
            candidateFingers = detection.fingers;
            candidateSince = now;
            return;
        }

        long hold = Math.max(500L, Math.min(1500L,
                prefs.getLong(KEY_HOLD_MS, DEFAULT_HOLD_MS)));
        if (now - candidateSince < hold) return;
        int delta = detection.fingers == 1 ? 1 : -1;
        armed = false;
        resetCandidate();
        if (listener != null) listener.onGestureScore(detection.side, delta, detection.confidence);
    }

    private void noteNeutral(long now) {
        if (armed) {
            if (candidateSide != null
                    && lastPositiveSeenAt > 0L
                    && now - lastPositiveSeenAt <= BRIEF_DROPOUT_TOLERANCE_MS) {
                return;
            }
            resetCandidate();
            return;
        }
        resetCandidate();
        if (neutralSince == 0L) neutralSince = now;
        if (now - neutralSince >= RELEASE_TO_REARM_MS
                && now - lastPositiveSeenAt >= RELEASE_TO_REARM_MS) {
            armed = true;
            neutralSince = 0L;
        }
    }

    private void resetCandidate() {
        candidateSide = null;
        candidateFingers = 0;
        candidateSince = 0L;
    }

    private Detection detect(FrontFrame frame) {
        Mat yuv = new Mat(frame.height + frame.height / 2, frame.width, CvType.CV_8UC1);
        Mat rgba = new Mat();
        try {
            yuv.put(0, 0, frame.nv21);
            Imgproc.cvtColor(yuv, rgba, Imgproc.COLOR_YUV2RGBA_NV21);

            if (frame.rotationDegrees != 0) {
                Mat rotated = new Mat();
                if (frame.rotationDegrees == 90) {
                    Core.rotate(rgba, rotated, Core.ROTATE_90_CLOCKWISE);
                } else if (frame.rotationDegrees == 180) {
                    Core.rotate(rgba, rotated, Core.ROTATE_180);
                } else if (frame.rotationDegrees == 270) {
                    Core.rotate(rgba, rotated, Core.ROTATE_90_COUNTERCLOCKWISE);
                } else {
                    rgba.copyTo(rotated);
                }
                rgba.release();
                rgba = rotated;
            }

            // Selfie-style mirror: physical left stays the HOME control zone.
            Core.flip(rgba, rgba, 1);
            return detectRgba(rgba, false);
        } finally {
            yuv.release();
            rgba.release();
        }
    }

    private Detection detect(Bitmap bitmap, boolean rearHidden) {
        Mat rgba = new Mat();
        try {
            Utils.bitmapToMat(bitmap, rgba);
            // TextureView bitmap is already oriented exactly as the operator sees it.
            // Do not mirror: left edge of the preview remains HOME.
            return detectRgba(rgba, rearHidden);
        } finally {
            rgba.release();
        }
    }

    private Detection detectRgba(Mat inputRgba, boolean rearHidden) {
        Mat rgba = new Mat();
        Mat rgb = new Mat();
        Mat ycrcb = new Mat();
        Mat mask = new Mat();
        Mat hierarchy = new Mat();
        Mat kernel = new Mat();
        try {
            inputRgba.copyTo(rgba);
            int maxEdge = Math.max(rgba.cols(), rgba.rows());
            if (maxEdge > MAX_ANALYSIS_EDGE) {
                double scale = MAX_ANALYSIS_EDGE / (double) maxEdge;
                Mat resized = new Mat();
                Imgproc.resize(rgba, resized,
                        new org.opencv.core.Size(
                                Math.max(2, Math.round(rgba.cols() * scale)),
                                Math.max(2, Math.round(rgba.rows() * scale))));
                rgba.release();
                rgba = resized;
            }

            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
            Imgproc.cvtColor(rgb, ycrcb, Imgproc.COLOR_RGB2YCrCb);

            Core.inRange(ycrcb, new Scalar(0, 122, 65), new Scalar(255, 188, 150), mask);
            kernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE, new org.opencv.core.Size(5, 5));
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel);
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.GaussianBlur(mask, mask, new org.opencv.core.Size(5, 5), 0);
            Imgproc.threshold(mask, mask, 120, 255, Imgproc.THRESH_BINARY);

            List<MatOfPoint> contours = new ArrayList<>();
            Mat contourInput = mask.clone();
            try {
                Imgproc.findContours(contourInput, contours, hierarchy,
                        Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            } finally {
                contourInput.release();
            }
            if (contours.isEmpty()) return null;
            contours.sort((a, b) -> Double.compare(
                    Imgproc.contourArea(b), Imgproc.contourArea(a)));
            final int width = mask.cols();
            final int height = mask.rows();
            final double frameArea = width * (double) height;

            Detection best = null;
            double bestScore = 0d;
            for (MatOfPoint contour : contours) {
                double area = Imgproc.contourArea(contour);
                if (area < frameArea * 0.0105d) continue;
                Rect rect = Imgproc.boundingRect(contour);
                if (rect.width < width * 0.055f || rect.height < height * 0.18f) continue;
                if (rect.width <= 0 || rect.height <= 0) continue;

                Side side;
                if (rearHidden) {
                    float leftSafePx = width * REAR_HIDDEN_SAFE_ZONE;
                    float rightSafePx = width * (1f - REAR_HIDDEN_SAFE_ZONE);
                    if (rect.x + rect.width <= leftSafePx) {
                        side = Side.HOME;
                    } else if (rect.x >= rightSafePx) {
                        side = Side.AWAY;
                    } else {
                        continue;
                    }
                } else {
                    Moments moments = Imgproc.moments(contour);
                    if (Math.abs(moments.m00) < 1e-5) continue;
                    float cx = (float) ((moments.m10 / moments.m00) / width);
                    if (cx <= LEFT_ZONE_END) side = Side.HOME;
                    else if (cx >= RIGHT_ZONE_START) side = Side.AWAY;
                    else continue;
                }

                boolean touchesOperatorEdge = rect.y + rect.height >= height * 0.84f
                        || (side == Side.HOME && rect.x <= width * 0.055f)
                        || (side == Side.AWAY && rect.x + rect.width >= width * 0.945f);
                if (!touchesOperatorEdge) continue;
                double extent = area / Math.max(1d, rect.area());
                if (extent < 0.18d) continue;

                FingerEstimate fingers = estimateFingers(contour, rect, width, height);
                if (fingers.count < 1 || fingers.count > 2) continue;
                double closeness = Math.min(1d, area / (frameArea * 0.075d));
                double score = (fingers.confidence * 0.72d) + (closeness * 0.28d);
                if (score > bestScore) {
                    bestScore = score;
                    best = new Detection(side, fingers.count,
                            (float) Math.max(0.55d, Math.min(0.98d, score)));
                }
            }
            return best;
        } finally {
            rgba.release();
            rgb.release();
            ycrcb.release();
            mask.release();
            hierarchy.release();
            kernel.release();
        }
    }

    private FingerEstimate estimateFingers(MatOfPoint contour, Rect rect, int frameW, int frameH) {
        Mat handMask = Mat.zeros(frameH, frameW, CvType.CV_8UC1);
        try {
            Imgproc.drawContours(handMask, Arrays.asList(contour), 0, new Scalar(255), -1);
            int yStart = rect.y + Math.max(1, (int) (rect.height * 0.06f));
            int yEnd = rect.y + Math.max(2, (int) (rect.height * 0.54f));
            int step = Math.max(1, rect.height / 28);
            int oneRows = 0;
            int twoRows = 0;
            int multiRows = 0;
            int sampledRows = 0;
            byte[] row = new byte[Math.max(1, rect.width)];
            for (int y = yStart; y < yEnd && y < frameH; y += step) {
                if (rect.x < 0 || rect.x + rect.width > frameW) continue;
                handMask.get(y, rect.x, row);
                RunSummary runs = summarizeRuns(row, rect.width);
                if (runs.count == 0) continue;
                sampledRows++;
                if (runs.count == 1 && runs.totalWidth <= rect.width * 0.44f) oneRows++;
                else if (runs.count == 2
                        && runs.totalWidth <= rect.width * 0.64f
                        && runs.maxRunWidth <= rect.width * 0.35f
                        && runs.largestGap >= Math.max(2, rect.width * 0.055f)) twoRows++;
                else if (runs.count >= 3) multiRows++;
            }
            if (sampledRows < 4) return FingerEstimate.NONE;
            int defects = countDeepFingerDefects(contour, rect);

            if (twoRows >= Math.max(3, sampledRows / 5)
                    && multiRows <= 2
                    && defects >= 1
                    && defects <= 2) {
                float consistency = Math.min(1f,
                        twoRows / (float) Math.max(4, sampledRows / 2));
                return new FingerEstimate(2, 0.68f + 0.25f * consistency);
            }

            if (oneRows >= Math.max(4, sampledRows / 3)
                    && twoRows <= 2
                    && multiRows <= 1
                    && defects <= 1
                    && rect.height >= rect.width * 1.02f) {
                float consistency = Math.min(1f,
                        oneRows / (float) Math.max(5, sampledRows / 2));
                return new FingerEstimate(1, 0.66f + 0.25f * consistency);
            }
            return FingerEstimate.NONE;
        } finally {
            handMask.release();
        }
    }

    private RunSummary summarizeRuns(byte[] row, int width) {
        int minRun = Math.max(2, (int) (width * 0.035f));
        int count = 0;
        int total = 0;
        int maxRun = 0;
        int largestGap = 0;
        int previousEnd = -1;
        int i = 0;
        while (i < width) {
            while (i < width && (row[i] & 0xFF) == 0) i++;
            int start = i;
            while (i < width && (row[i] & 0xFF) != 0) i++;
            int end = i;
            int len = end - start;
            if (len >= minRun) {
                if (previousEnd >= 0) largestGap = Math.max(largestGap, start - previousEnd);
                previousEnd = end;
                count++;
                total += len;
                maxRun = Math.max(maxRun, len);
            }
        }
        return new RunSummary(count, total, maxRun, largestGap);
    }

    private int countDeepFingerDefects(MatOfPoint contour, Rect rect) {
        Point[] points = contour.toArray();
        if (points.length < 6) return 0;
        MatOfInt hull = new MatOfInt();
        MatOfInt4 defects = new MatOfInt4();
        try {
            Imgproc.convexHull(contour, hull, false);
            if (hull.rows() < 4) return 0;
            Imgproc.convexityDefects(contour, hull, defects);
            int[] data = defects.toArray();
            int count = 0;
            double centerY = rect.y + rect.height * 0.62d;
            for (int i = 0; i + 3 < data.length; i += 4) {
                Point start = points[data[i]];
                Point end = points[data[i + 1]];
                Point far = points[data[i + 2]];
                double depth = data[i + 3] / 256.0d;
                if (depth < rect.height * 0.055d) continue;
                if (start.y > centerY || end.y > centerY) continue;
                double angle = angleDegrees(start, far, end);
                if (angle <= 100d) count++;
            }
            return count;
        } catch (Throwable ignored) {
            return 0;
        } finally {
            hull.release();
            defects.release();
        }
    }

    private static double angleDegrees(Point a, Point vertex, Point b) {
        double ax = a.x - vertex.x;
        double ay = a.y - vertex.y;
        double bx = b.x - vertex.x;
        double by = b.y - vertex.y;
        double denom = Math.sqrt(ax * ax + ay * ay) * Math.sqrt(bx * bx + by * by);
        if (denom < 1e-5) return 180d;
        double cos = Math.max(-1d, Math.min(1d, (ax * bx + ay * by) / denom));
        return Math.toDegrees(Math.acos(cos));
    }

    private static final class FrontFrame {
        final byte[] nv21;
        final int width;
        final int height;
        final int rotationDegrees;

        FrontFrame(byte[] nv21, int width, int height, int rotationDegrees) {
            this.nv21 = nv21;
            this.width = width;
            this.height = height;
            this.rotationDegrees = rotationDegrees;
        }
    }

    private static final class Detection {
        final Side side;
        final int fingers;
        final float confidence;

        Detection(Side side, int fingers, float confidence) {
            this.side = side;
            this.fingers = fingers;
            this.confidence = confidence;
        }
    }

    private static final class FingerEstimate {
        static final FingerEstimate NONE = new FingerEstimate(0, 0f);
        final int count;
        final float confidence;

        FingerEstimate(int count, float confidence) {
            this.count = count;
            this.confidence = confidence;
        }
    }

    private static final class RunSummary {
        final int count;
        final int totalWidth;
        final int maxRunWidth;
        final int largestGap;

        RunSummary(int count, int totalWidth, int maxRunWidth, int largestGap) {
            this.count = count;
            this.totalWidth = totalWidth;
            this.maxRunWidth = maxRunWidth;
            this.largestGap = largestGap;
        }
    }
}
