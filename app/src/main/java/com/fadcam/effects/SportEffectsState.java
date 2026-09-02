package com.fadcam.effects;

/** Process-wide Live FX state shared by preview and encoder. */
public final class SportEffectsState {
    public enum Effect {
        HEARTS("Raining Hearts", true, "SPARKLE", 4500L),
        BOOM("BOOM Explosion", true, "BOOM", 2800L),
        THUNDER("Raining Thunder", true, "THUNDER", 4600L),
        GOAL_MATCH("Goal of the Match", true, "GOAL_HORN", 5200L),
        VICTORY_BURST("Victory Burst", true, "STADIUM_CHEER", 5200L),
        MVP("MVP Moment", true, "APPLAUSE", 5000L),
        CONFETTI("Confetti Storm", true, "APPLAUSE", 4700L),
        STARS("Raining Stars", true, "SPARKLE", 4500L),
        FIRE("Fire Line", true, "STADIUM_CHEER", 4200L),
        LASERS("Laser Show", true, "LASER", 4200L),
        NEON_PULSE("Neon Pulse", true, "SYNTH_HIT", 4000L),
        SPOTLIGHT("Spotlight", true, "DRUM_ROLL", 4500L),
        SPEED_LINES("Speed Lines", true, "SWOOSH", 3300L),
        RAINBOW("Rainbow Sweep", true, "CHIME", 4300L),
        SNOW("Snow / Ice", true, "SPARKLE", 4500L),
        BUBBLES("Bubbles", true, "CHIME", 4500L),
        SMOKE("Smoke Entrance", true, "SWOOSH", 4500L),
        CROWN("Champion Crown", true, "STADIUM_CHEER", 4500L),
        CAMERA_FLASH("Camera Flash", true, "SHUTTER", 1800L),
        RED_CARD("Red Card", true, "WHISTLE", 3400L),
        YELLOW_CARD("Yellow Card", true, "WHISTLE", 3400L),
        SCORE_POP("GOAL! Score Pop", true, "AIR_HORN", 3600L),
        RIBBONS("Victory Ribbons", true, "APPLAUSE", 4600L),

        SLIDE_LEFT("Slide Left", true, "SWOOSH", 3000L),
        SLIDE_RIGHT("Slide Right", true, "SWOOSH", 3000L),
        SUBSCRIBE("Subscribe to Channel", true, "CHIME", 4400L),
        LIKE("Give a Like", true, "CHIME", 3600L),

        GOAL_HORN("Goal Horn", false, "GOAL_HORN", 0L),
        AIR_HORN("Air Horn", false, "AIR_HORN", 0L),
        APPLAUSE("Applause", false, "APPLAUSE", 0L),
        WHISTLE("Referee Whistle", false, "WHISTLE", 0L),
        STADIUM_CHEER("Stadium Cheer", false, "STADIUM_CHEER", 0L),
        THUNDER_SOUND("Thunder Sound", false, "THUNDER", 0L),
        DRUM_ROLL("Drum Roll", false, "DRUM_ROLL", 0L),
        SIREN("Siren", false, "SIREN", 0L),
        SHUTTER("Camera Shutter", false, "SHUTTER", 0L),
        SWOOSH("Swoosh", false, "SWOOSH", 0L);

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
            activeVisual = new Active(
                    effect,
                    now,
                    Math.max(800L, effect.durationMs)
            );
        }

        if (effect.audioKind != null && !effect.audioKind.isEmpty()) {
            SportEffectsAudioMixer.trigger(effect.audioKind, now);
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

    static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
