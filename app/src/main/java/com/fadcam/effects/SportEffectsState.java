package com.fadcam.effects;

import android.content.Context;

/**
 * Process-wide live FX state.
 *
 * The fullscreen preview and the GL encoder run in the same application process,
 * so a small volatile state is enough to keep preview and recorded overlay in sync.
 */
public final class SportEffectsState {
    public static final String DF_OVERLAY_PREFIX = "__DF_OVERLAY__:";
    private static final String WM_SPLIT = "||wm||";
    private static final long FRAME_STEP_MS = 160L;

    public enum Effect {
        HEARTS("Raining Hearts", true, "SPARKLE", 4200L),
        BOOM("BOOM", true, "BOOM", 2600L),
        THUNDER("Raining Thunder", true, "THUNDER", 4200L),
        SLIDE_LEFT("Slide Left", true, null, 2800L),
        SLIDE_RIGHT("Slide Right", true, null, 2800L),
        GOAL_MATCH("Goal of the Match", true, "GOAL_HORN", 4800L),
        SUBSCRIBE("Subscribe to Channel", true, "CHIME", 4200L),
        LIKE("Give a Like", true, "CHIME", 3400L),
        CONFETTI("Confetti", true, "APPLAUSE", 4200L),
        STARS("Raining Stars", true, "SPARKLE", 4200L),
        FIRE("Fire", true, "STADIUM_CHEER", 3800L),

        GOAL_HORN("Goal Horn", false, "GOAL_HORN", 0L),
        APPLAUSE("Applause", false, "APPLAUSE", 0L),
        WHISTLE("Referee Whistle", false, "WHISTLE", 0L),
        STADIUM_CHEER("Stadium Cheer", false, "STADIUM_CHEER", 0L),
        THUNDER_SOUND("Thunder Sound", false, "THUNDER", 0L);

        public final String label;
        public final boolean visual;
        public final String audioKind;
        public final long durationMs;

        Effect(String label, boolean visual, String audioKind, long durationMs) {
            this.label = label;
            this.visual = visual;
            this.audioKind = audioKind;
            this.durationMs = durationMs;
        }
    }

    public static final class Active {
        public final Effect effect;
        public final long startedAtMs;
        public final long durationMs;

        Active(Effect effect, long startedAtMs, long durationMs) {
            this.effect = effect;
            this.startedAtMs = startedAtMs;
            this.durationMs = durationMs;
        }

        public float progress(long nowMs) {
            if (durationMs <= 0L) return 1f;
            return clamp01((nowMs - startedAtMs) / (float) durationMs);
        }
    }

    private static volatile Active activeVisual;

    private SportEffectsState() {}

    public static void trigger(Effect effect) {
        if (effect == null) return;
        long now = System.currentTimeMillis();

        if (effect.visual) {
            activeVisual = new Active(effect, now, Math.max(800L, effect.durationMs));
        }

        if (effect.audioKind != null && !effect.audioKind.isEmpty()) {
            SportEffectsAudioMixer.enqueue(effect.audioKind, now);
        }
    }

    public static void clearVisual() {
        activeVisual = null;
    }

    public static Active snapshot() {
        Active active = activeVisual;
        if (active == null) return null;

        long now = System.currentTimeMillis();
        if (now - active.startedAtMs >= active.durationMs) {
            if (activeVisual == active) activeVisual = null;
            return null;
        }
        return active;
    }

    public static boolean isVisualActive() {
        return snapshot() != null;
    }

    /**
     * Adds a harmless FX token to the existing full-screen overlay payload.
     * GLWatermarkRenderer ignores this token as a digital-forensics box, but its
     * changing frame id forces the overlay texture to refresh while an FX is active.
     *
     * Existing Digital Forensics entries are preserved byte-for-byte.
     */
    public static String wrapWatermark(Context context, String baseText) {
        String base = baseText == null ? "" : baseText;
        Active active = snapshot();
        if (active == null) return base;

        long now = System.currentTimeMillis();
        long step = Math.max(0L, (now - active.startedAtMs) / FRAME_STEP_MS);
        String fxToken = "FX:" + active.effect.name() + ":" + step;

        if (base.startsWith(DF_OVERLAY_PREFIX)) {
            String rest = base.substring(DF_OVERLAY_PREFIX.length());
            int split = rest.indexOf(WM_SPLIT);
            if (split >= 0) {
                String existingPayload = rest.substring(0, split);
                String watermark = rest.substring(split + WM_SPLIT.length());
                String merged = existingPayload == null || existingPayload.trim().isEmpty()
                        ? fxToken
                        : existingPayload + ";" + fxToken;
                return DF_OVERLAY_PREFIX + merged + WM_SPLIT + watermark;
            }

            String merged = rest == null || rest.trim().isEmpty()
                    ? fxToken
                    : rest + ";" + fxToken;
            return DF_OVERLAY_PREFIX + merged;
        }

        return DF_OVERLAY_PREFIX + fxToken + WM_SPLIT + base;
    }

    static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
