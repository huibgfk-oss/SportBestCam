package com.fadcam.effects;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.FloatBuffer;

/**
 * Dedicated encoder compositor for SportBestCam Live FX.
 *
 * Preview effects are rendered by SportEffectsOverlayView.
 * This class composites the same effect state directly on the encoder EGL surface.
 */
public final class SportEffectsGlEncoderBridge {
    private static final long UPDATE_INTERVAL_MS = 90L;
    private static final long RELEASE_AFTER_IDLE_MS = 1800L;
    private static final int MAX_TEXTURE_LONG_SIDE = 1280;

    private static int textureId = 0;
    private static Bitmap bitmap;
    private static int bitmapWidth = 0;
    private static int bitmapHeight = 0;
    private static long lastTextureUpdateMs = 0L;
    private static long lastActiveMs = 0L;

    private SportEffectsGlEncoderBridge() {}

    public static void drawIfActive(
            int program,
            int positionHandle,
            int texCoordHandle,
            int samplerHandle,
            FloatBuffer fullRectBuffer,
            FloatBuffer texCoordBuffer,
            int outputWidth,
            int outputHeight
    ) {
        if (program == 0
                || fullRectBuffer == null
                || texCoordBuffer == null
                || outputWidth <= 0
                || outputHeight <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean active = SportEffectsState.isVisualActive();

        if (!active) {
            if (lastActiveMs > 0L
                    && now - lastActiveMs >= RELEASE_AFTER_IDLE_MS) {
                releaseGlResources();
                lastActiveMs = 0L;
            }
            return;
        }

        lastActiveMs = now;

        try {
            ensureTexture();
            if (textureId == 0) return;

            int[] size = chooseTextureSize(outputWidth, outputHeight);
            int targetWidth = size[0];
            int targetHeight = size[1];

            if (bitmap == null
                    || bitmapWidth != targetWidth
                    || bitmapHeight != targetHeight) {
                if (bitmap != null) bitmap.recycle();
                bitmap = Bitmap.createBitmap(
                        targetWidth,
                        targetHeight,
                        Bitmap.Config.ARGB_8888
                );
                bitmapWidth = targetWidth;
                bitmapHeight = targetHeight;
                lastTextureUpdateMs = 0L;
            }

            if (now - lastTextureUpdateMs >= UPDATE_INTERVAL_MS) {
                updateTexture();
                lastTextureUpdateMs = now;
            }

            drawTexture(
                    program,
                    positionHandle,
                    texCoordHandle,
                    samplerHandle,
                    fullRectBuffer,
                    texCoordBuffer,
                    outputWidth,
                    outputHeight
            );
        } catch (Throwable ignored) {
            // A visual effect must never interrupt camera recording.
        }
    }

    private static void ensureTexture() {
        if (textureId != 0 && GLES20.glIsTexture(textureId)) return;

        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        textureId = ids[0];
        if (textureId == 0) return;

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        lastTextureUpdateMs = 0L;
    }

    private static int[] chooseTextureSize(int width, int height) {
        int longSide = Math.max(width, height);

        if (longSide <= MAX_TEXTURE_LONG_SIDE) {
            return new int[] {
                    Math.max(360, width),
                    Math.max(360, height)
            };
        }

        float scale = MAX_TEXTURE_LONG_SIDE / (float) longSide;
        int w = Math.max(360, Math.round(width * scale));
        int h = Math.max(360, Math.round(height * scale));

        if ((w & 1) != 0) w++;
        if ((h & 1) != 0) h++;

        return new int[] { w, h };
    }

    private static void updateTexture() {
        if (bitmap == null || textureId == 0) return;

        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(
                Color.TRANSPARENT,
                android.graphics.PorterDuff.Mode.CLEAR
        );

        canvas.save();
        canvas.translate(0f, bitmapHeight);
        canvas.scale(1f, -1f);
        SportEffectsFrameRenderer.draw(canvas);
        canvas.restore();

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLUtils.texImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                bitmap,
                0
        );
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private static void drawTexture(
            int program,
            int positionHandle,
            int texCoordHandle,
            int samplerHandle,
            FloatBuffer fullRectBuffer,
            FloatBuffer texCoordBuffer,
            int outputWidth,
            int outputHeight
    ) {
        int[] previousViewport = new int[4];
        int[] previousProgram = new int[1];

        GLES20.glGetIntegerv(
                GLES20.GL_VIEWPORT,
                previousViewport,
                0
        );
        GLES20.glGetIntegerv(
                GLES20.GL_CURRENT_PROGRAM,
                previousProgram,
                0
        );

        GLES20.glViewport(0, 0, outputWidth, outputHeight);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(
                GLES20.GL_SRC_ALPHA,
                GLES20.GL_ONE_MINUS_SRC_ALPHA
        );

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(
                GLES20.GL_TEXTURE_2D,
                textureId
        );

        fullRectBuffer.position(0);
        texCoordBuffer.position(0);

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(
                positionHandle, 2, GLES20.GL_FLOAT, false, 0, fullRectBuffer
        );

        GLES20.glEnableVertexAttribArray(texCoordHandle);
        GLES20.glVertexAttribPointer(
                texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer
        );

        GLES20.glUniform1i(samplerHandle, 0);
        GLES20.glDrawArrays(
                GLES20.GL_TRIANGLE_STRIP,
                0,
                4
        );

        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(texCoordHandle);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glUseProgram(previousProgram[0]);
        GLES20.glDisable(GLES20.GL_BLEND);

        GLES20.glViewport(
                previousViewport[0],
                previousViewport[1],
                previousViewport[2],
                previousViewport[3]
        );
    }

    private static void releaseGlResources() {
        try {
            if (textureId != 0 && GLES20.glIsTexture(textureId)) {
                GLES20.glDeleteTextures(
                        1,
                        new int[] { textureId },
                        0
                );
            }
        } catch (Throwable ignored) {
        }

        textureId = 0;
        lastTextureUpdateMs = 0L;

        if (bitmap != null) {
            bitmap.recycle();
            bitmap = null;
        }

        bitmapWidth = 0;
        bitmapHeight = 0;
    }
}
