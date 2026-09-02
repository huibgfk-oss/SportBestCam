package com.fadcam.effects;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

public final class SportEffectsFrameRenderer {
    private SportEffectsFrameRenderer() {}

    public static void draw(Canvas canvas) {
        if (canvas == null) return;
        SportEffectsState.Active active = SportEffectsState.snapshot();
        if (active == null) return;

        long now = System.currentTimeMillis();
        float p = active.progress(now);
        float fade = p < 0.82f ? 1f : Math.max(0f, 1f - ((p - 0.82f) / 0.18f));

        switch (active.effect) {
            case HEARTS:
                drawRainHearts(canvas, p, fade);
                break;
            case BOOM:
                drawBoom(canvas, p, fade);
                break;
            case THUNDER:
                drawThunder(canvas, p, fade);
                break;
            case SLIDE_LEFT:
                drawSlide(canvas, p, fade, true);
                break;
            case SLIDE_RIGHT:
                drawSlide(canvas, p, fade, false);
                break;
            case GOAL_MATCH:
                drawGoal(canvas, p, fade);
                break;
            case SUBSCRIBE:
                drawSubscribe(canvas, p, fade);
                break;
            case LIKE:
                drawLike(canvas, p, fade);
                break;
            case CONFETTI:
                drawConfetti(canvas, p, fade);
                break;
            case STARS:
                drawStars(canvas, p, fade);
                break;
            case FIRE:
                drawFire(canvas, p, fade);
                break;
            default:
                break;
        }
    }

    private static void drawRainHearts(Canvas c, float p, float fade) {
        int w = c.getWidth();
        int h = c.getHeight();
        Paint paint = fill(Color.rgb(247, 52, 105), 220 * fade);
        Paint shine = fill(Color.rgb(255, 172, 196), 180 * fade);

        for (int i = 0; i < 24; i++) {
            float x = pseudo(i, 11) * w;
            float speed = 0.75f + pseudo(i, 19) * 0.85f;
            float y = ((pseudo(i, 23) + p * speed * 1.55f) % 1.18f) * h - 0.09f * h;
            float size = Math.max(12f, w * (0.018f + pseudo(i, 31) * 0.020f));
            drawHeart(c, x, y, size, paint);
            if ((i & 3) == 0) {
                c.drawCircle(x - size * 0.22f, y - size * 0.15f, size * 0.08f, shine);
            }
        }
    }

    private static void drawBoom(Canvas c, float p, float fade) {
        int w = c.getWidth();
        int h = c.getHeight();
        float cx = w * 0.5f;
        float cy = h * 0.50f;
        float pulse = easeOut(Math.min(1f, p * 2.7f));
        float radius = Math.min(w, h) * (0.12f + 0.29f * pulse);

        Paint glow = fill(Color.rgb(255, 179, 0), 110 * fade);
        Paint hot = stroke(Color.rgb(255, 76, 0), Math.max(5f, w * 0.006f), 235 * fade);
        c.drawCircle(cx, cy, radius, glow);
        c.drawCircle(cx, cy, radius * 0.78f, hot);

        for (int i = 0; i < 16; i++) {
            double a = (Math.PI * 2.0 * i) / 16.0;
            float r1 = radius * 0.82f;
            float r2 = radius * (1.18f + 0.25f * pseudo(i, 40));
            c.drawLine(
                    cx + (float) Math.cos(a) * r1,
                    cy + (float) Math.sin(a) * r1,
                    cx + (float) Math.cos(a) * r2,
                    cy + (float) Math.sin(a) * r2,
                    hot
            );
        }

        Paint text = text(Color.WHITE, Math.max(34f, w * 0.073f), 255 * fade);
        text.setFakeBoldText(true);
        text.setShadowLayer(Math.max(4f, w * 0.006f), 0, 0, Color.BLACK);
        drawCenteredText(c, "BOOM!", cx, cy - (text.ascent() + text.descent()) / 2f, text);
    }

    private static void drawThunder(Canvas c, float p, float fade) {
        int w = c.getWidth();
        int h = c.getHeight();
        Paint rain = stroke(Color.rgb(116, 194, 255), Math.max(2f, w * 0.0022f), 150 * fade);
        Paint bolt = fill(Color.rgb(255, 235, 59), 245 * fade);

        for (int i = 0; i < 28; i++) {
            float x = pseudo(i, 51) * w;
            float y = ((pseudo(i, 58) + p * (1.5f + pseudo(i, 59))) % 1.15f) * h;
            float len = h * (0.055f + pseudo(i, 60) * 0.07f);
            c.drawLine(x, y, x - w * 0.010f, y + len, rain);
        }

        int bolts = 4;
        for (int i = 0; i < bolts; i++) {
            float x = w * (0.16f + i * 0.22f + (pseudo(i, 62) - 0.5f) * 0.09f);
            float y = h * (0.10f + pseudo(i, 63) * 0.22f);
            float scale = Math.min(w, h) * (0.10f + pseudo(i, 64) * 0.06f);
            drawBolt(c, x, y, scale, bolt);
        }

        if (((int) (p * 22f)) % 7 == 0) {
            Paint flash = fill(Color.WHITE, 62 * fade);
            c.drawRect(0, 0, w, h, flash);
        }
    }

    private static void drawSlide(Canvas c, float p, float fade, boolean leftToRight) {
        int w = c.getWidth();
        int h = c.getHeight();
        float tIn = easeOut(Math.min(1f, p / 0.20f));
        float tOut = p < 0.78f ? 0f : easeIn((p - 0.78f) / 0.22f);
        float targetX = w * 0.08f;
        float panelW = w * 0.48f;
        float x;

        if (leftToRight) {
            x = -panelW + (targetX + panelW) * tIn;
            x += w * 0.70f * tOut;
        } else {
            x = w - targetX - panelW + (panelW + targetX) * (1f - tIn);
            x -= w * 0.70f * tOut;
        }

        float top = h * 0.37f;
        float bottom = h * 0.62f;
        RectF rect = new RectF(x, top, x + panelW, bottom);

        Paint bg = fill(Color.rgb(20, 30, 45), 225 * fade);
        Paint accent = fill(Color.rgb(33, 150, 243), 245 * fade);
        c.drawRoundRect(rect, h * 0.03f, h * 0.03f, bg);
        c.drawRect(rect.left, rect.top, rect.left + Math.max(8f, panelW * 0.025f), rect.bottom, accent);

        Paint title = text(Color.WHITE, Math.max(25f, w * 0.035f), 255 * fade);
        title.setFakeBoldText(true);
        c.drawText("SPORTBESTCAM", rect.left + panelW * 0.08f, rect.centerY() - title.ascent() * 0.25f, title);
    }

    private static void drawGoal(Canvas c, float p, float fade) {
        int w = c.getWidth();
        int h = c.getHeight();
        float pop = easeOut(Math.min(1f, p / 0.18f));
        float bandH = h * 0.23f * pop;
        float top = h * 0.38f - (bandH * 0.5f);
        RectF band = new RectF(w * 0.06f, top, w * 0.94f, top + bandH);

        Paint gold = fill(Color.rgb(255, 179, 0), 230 * fade);
        Paint dark = fill(Color.rgb(15, 23, 42), 235 * fade);
        c.drawRoundRect(band, h * 0.035f, h * 0.035f, dark);
        c.drawRect(band.left, band.bottom - Math.max(7f, h * 0.012f), band.right, band.bottom, gold);

        Paint title = text(Color.WHITE, Math.max(27f, w * 0.047f), 255 * fade);
        title.setFakeBoldText(true);
        drawCenteredText(c, "GOAL OF THE MATCH!", band.centerX(), band.centerY() - (title.ascent() + title.descent()) / 2f, title);

        Paint star = fill(Color.rgb(255, 215, 64), 245 * fade);
        for (int i = 0; i < 10; i++) {
            float angle = (float) (Math.PI * 2.0 * i / 10.0 + p * 3.0);
            float r = Math.min(w, h) * (0.29f + 0.045f * (float) Math.sin(p * 10f + i));
            float x = w * 0.5f + (float) Math.cos(angle) * r;
            float y = h * 0.50f + (float) Math.sin(angle) * r * 0.58f;
            drawStar(c, x, y, Math.min(w, h) * 0.025f, star);
        }
    }

    private static void drawSubscribe(Canvas c, float p, float fade) {
        int w = c.getWidth();
        int h = c.getHeight();
        float pop = easeOut(Math.min(1f, p / 0.18f));
        float bw = w * 0.47f * pop;
        float bh = h * 0.12f;
        RectF r = new RectF(w * 0.5f - bw * 0.5f, h * 0.76f, w * 0.5f + bw * 0.5f, h * 0.76f + bh);

        Paint red = fill(Color.rgb(220, 38, 38), 238 * fade);
        c.drawRoundRect(r, bh * 0.5f, bh * 0.5f, red);

        Paint t = text(Color.WHITE, Math.max(21f, w * 0.027f), 255 * fade);
        t.setFakeBoldText(true);
        drawCenteredText(c, "SUBSCRIBE TO CHANNEL", r.centerX(), r.centerY() - (t.ascent() + t.descent()) / 2f, t);
    }

    private static void drawLike(Canvas c, float p, float fade) {
        int w = c.getWidth();
        int h = c.getHeight();
        float pop = easeOut(Math.min(1f, p / 0.16f));
        float radius = Math.min(w, h) * 0.11f * pop;
        float cx = w * 0.50f;
        float cy = h * 0.56f;

        Paint blue = fill(Color.rgb(37, 99, 235), 232 * fade);
        c.drawCircle(cx, cy, radius, blue);

        Paint hand = text(Color.WHITE, Math.max(30f, w * 0.048f), 255 * fade);
        hand.setFakeBoldText(true);
        drawCenteredText(c, "+1", cx, cy - (hand.ascent() + hand.descent()) / 2f, hand);

        Paint label = text(Color.WHITE, Math.max(22f, w * 0.030f), 255 * fade);
        label.setFakeBoldText(true);
        label.setShadowLayer(4f, 0, 2f, Color.BLACK);
        drawCenteredText(c, "GIVE A LIKE", cx, cy + radius + h * 0.08f, label);
    }

    private static void drawConfetti(Canvas c, float p, float fade) {
        int w = c.getWidth();
        int h = c.getHeight();
        int[] colors = {
                Color.rgb(244, 63, 94),
                Color.rgb(34, 197, 94),
                Color.rgb(59, 130, 246),
                Color.rgb(250, 204, 21),
                Color.rgb(168, 85, 247)
        };
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        for (int i = 0; i < 42; i++) {
            float x = pseudo(i, 73) * w;
            float y = ((pseudo(i, 74) + p * (1.0f + pseudo(i, 75))) % 1.15f) * h;
            float sz = Math.max(5f, w * (0.004f + pseudo(i, 76) * 0.008f));
            paint.setColor(colors[i % colors.length]);
            paint.setAlpha(alpha(235 * fade));
            c.save();
            c.rotate((p * 500f + i * 37f) % 360f, x, y);
            c.drawRect(x - sz, y - sz * 0.45f, x + sz, y + sz * 0.45f, paint);
            c.restore();
        }
    }

    private static void drawStars(Canvas c, float p, float fade) {
        int w = c.getWidth();
        int h = c.getHeight();
        Paint star = fill(Color.rgb(255, 213, 79), 240 * fade);

        for (int i = 0; i < 25; i++) {
            float x = pseudo(i, 81) * w;
            float y = ((pseudo(i, 82) + p * (0.65f + pseudo(i, 83) * 0.75f)) % 1.15f) * h;
            float r = Math.min(w, h) * (0.014f + pseudo(i, 84) * 0.020f);
            drawStar(c, x, y, r, star);
        }
    }

    private static void drawFire(Canvas c, float p, float fade) {
        int w = c.getWidth();
        int h = c.getHeight();
        Paint outer = fill(Color.rgb(255, 87, 34), 195 * fade);
        Paint inner = fill(Color.rgb(255, 193, 7), 220 * fade);

        for (int i = 0; i < 18; i++) {
            float x = (i + 0.5f) * w / 18f;
            float wobble = (float) Math.sin(p * 26f + i * 1.7f);
            float flameH = h * (0.11f + pseudo(i, 91) * 0.11f) * (0.85f + 0.15f * wobble);
            float baseY = h * 1.02f;
            Path f = new Path();
            f.moveTo(x - w * 0.025f, baseY);
            f.quadTo(x - w * 0.012f, baseY - flameH * 0.58f, x + wobble * w * 0.008f, baseY - flameH);
            f.quadTo(x + w * 0.022f, baseY - flameH * 0.50f, x + w * 0.030f, baseY);
            f.close();
            c.drawPath(f, outer);
            c.drawCircle(x, baseY - flameH * 0.30f, Math.max(6f, w * 0.011f), inner);
        }
    }

    private static void drawHeart(Canvas c, float cx, float cy, float s, Paint paint) {
        Path p = new Path();
        p.moveTo(cx, cy + s * 0.38f);
        p.cubicTo(cx - s * 0.78f, cy - s * 0.05f, cx - s * 0.48f, cy - s * 0.62f, cx, cy - s * 0.25f);
        p.cubicTo(cx + s * 0.48f, cy - s * 0.62f, cx + s * 0.78f, cy - s * 0.05f, cx, cy + s * 0.38f);
        p.close();
        c.drawPath(p, paint);
    }

    private static void drawBolt(Canvas c, float x, float y, float s, Paint paint) {
        Path p = new Path();
        p.moveTo(x + s * 0.10f, y);
        p.lineTo(x - s * 0.25f, y + s * 0.48f);
        p.lineTo(x - s * 0.02f, y + s * 0.48f);
        p.lineTo(x - s * 0.22f, y + s);
        p.lineTo(x + s * 0.35f, y + s * 0.38f);
        p.lineTo(x + s * 0.09f, y + s * 0.38f);
        p.close();
        c.drawPath(p, paint);
    }

    private static void drawStar(Canvas c, float cx, float cy, float r, Paint paint) {
        Path path = new Path();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2.0 + i * Math.PI / 5.0;
            float rr = (i % 2 == 0) ? r : r * 0.45f;
            float x = cx + (float) Math.cos(a) * rr;
            float y = cy + (float) Math.sin(a) * rr;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        c.drawPath(path, paint);
    }

    private static Paint fill(int color, float alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setAlpha(alpha(alpha));
        return p;
    }

    private static Paint stroke(int color, float width, float alpha) {
        Paint p = fill(color, alpha);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        return p;
    }

    private static Paint text(int color, float size, float alpha) {
        Paint p = fill(color, alpha);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                android.graphics.Typeface.BOLD
        ));
        return p;
    }

    private static void drawCenteredText(Canvas c, String text, float cx, float baseline, Paint paint) {
        c.drawText(text, cx - paint.measureText(text) * 0.5f, baseline, paint);
    }

    private static int alpha(float a) {
        return Math.max(0, Math.min(255, Math.round(a)));
    }

    private static float pseudo(int i, int salt) {
        long x = (i + 1L) * 1103515245L + (salt * 12345L);
        x ^= (x >>> 16);
        return (x & 0xffffL) / 65535f;
    }

    private static float easeOut(float t) {
        t = SportEffectsState.clamp01(t);
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }

    private static float easeIn(float t) {
        t = SportEffectsState.clamp01(t);
        return t * t * t;
    }
}
