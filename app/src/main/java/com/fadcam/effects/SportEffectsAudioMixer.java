package com.fadcam.effects;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * Lightweight procedural SFX mixer.
 *
 * No external WAV/MP3 assets are required. Effects are generated as PCM and mixed
 * directly into the microphone PCM before AAC encoding.
 */
public final class SportEffectsAudioMixer {
    private static final Object LOCK = new Object();
    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    private static final ArrayList<Voice> VOICES = new ArrayList<>();
    private static final long MAX_PENDING_AGE_MS = 2500L;

    private SportEffectsAudioMixer() {}

    private static final class Pending {
        final String kind;
        final long atMs;

        Pending(String kind, long atMs) {
            this.kind = kind;
            this.atMs = atMs;
        }
    }

    private static final class Voice {
        final String kind;
        final int sampleRate;
        final int totalFrames;
        int frame;
        int noiseState;

        Voice(String kind, int sampleRate) {
            this.kind = kind == null ? "" : kind;
            this.sampleRate = Math.max(8000, sampleRate);
            this.totalFrames = Math.max(1, (int) (durationSeconds(this.kind) * this.sampleRate));
            this.noiseState = this.kind.hashCode() ^ 0x5A17BEEF;
        }

        boolean done() {
            return frame >= totalFrames;
        }
    }

    public static void enqueue(String kind, long triggerAtMs) {
        if (kind == null || kind.trim().isEmpty()) return;
        synchronized (LOCK) {
            PENDING.addLast(new Pending(kind.trim(), triggerAtMs));
            while (PENDING.size() > 12) PENDING.removeFirst();
        }
    }

    /**
     * Mix generated effects into little-endian PCM16.
     * This is intentionally called after realtime microphone mute, so the user may
     * mute ambient audio while still recording a clean SFX track.
     */
    public static void mixIntoPcm16(
            byte[] pcm,
            int byteCount,
            int sampleRate,
            int channelCount
    ) {
        if (pcm == null || byteCount <= 1) return;
        int channels = Math.max(1, channelCount);
        int bytesPerFrame = channels * 2;
        int frames = Math.min(byteCount, pcm.length) / bytesPerFrame;
        if (frames <= 0) return;

        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            while (!PENDING.isEmpty()) {
                Pending p = PENDING.removeFirst();
                if (now - p.atMs <= MAX_PENDING_AGE_MS) {
                    VOICES.add(new Voice(p.kind, sampleRate));
                }
            }

            if (VOICES.isEmpty()) return;

            for (int frameIndex = 0; frameIndex < frames; frameIndex++) {
                double fx = 0.0;

                Iterator<Voice> it = VOICES.iterator();
                while (it.hasNext()) {
                    Voice voice = it.next();
                    if (voice.done()) {
                        it.remove();
                        continue;
                    }
                    fx += sampleVoice(voice);
                    voice.frame++;
                    if (voice.done()) it.remove();
                }

                if (fx > 1.0) fx = 1.0;
                if (fx < -1.0) fx = -1.0;

                int fxSample = (int) Math.round(fx * 32767.0 * 0.60);
                int base = frameIndex * bytesPerFrame;

                for (int ch = 0; ch < channels; ch++) {
                    int idx = base + (ch * 2);
                    int original = (short) (((pcm[idx + 1] & 0xff) << 8) | (pcm[idx] & 0xff));
                    int mixed = original + fxSample;
                    if (mixed > 32767) mixed = 32767;
                    if (mixed < -32768) mixed = -32768;
                    pcm[idx] = (byte) (mixed & 0xff);
                    pcm[idx + 1] = (byte) ((mixed >> 8) & 0xff);
                }

                if (VOICES.isEmpty() && PENDING.isEmpty()) {
                    // Remaining PCM stays untouched.
                    break;
                }
            }
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            PENDING.clear();
            VOICES.clear();
        }
    }

    private static double durationSeconds(String kind) {
        if ("BOOM".equals(kind)) return 1.35;
        if ("THUNDER".equals(kind)) return 2.40;
        if ("GOAL_HORN".equals(kind)) return 2.10;
        if ("APPLAUSE".equals(kind)) return 2.20;
        if ("WHISTLE".equals(kind)) return 1.00;
        if ("STADIUM_CHEER".equals(kind)) return 2.70;
        if ("SPARKLE".equals(kind)) return 0.75;
        if ("CHIME".equals(kind)) return 0.85;
        return 1.0;
    }

    private static double sampleVoice(Voice v) {
        double t = v.frame / (double) v.sampleRate;
        double duration = v.totalFrames / (double) v.sampleRate;
        double x = duration <= 0.0 ? 1.0 : Math.min(1.0, t / duration);

        if ("BOOM".equals(v.kind)) {
            double env = Math.exp(-4.8 * x);
            double freq = 72.0 - (35.0 * x);
            double rumble = Math.sin(2.0 * Math.PI * freq * t);
            double noise = nextNoise(v) * Math.exp(-7.0 * x);
            return (rumble * 0.78 + noise * 0.32) * env;
        }

        if ("THUNDER".equals(v.kind)) {
            double env = Math.pow(1.0 - x, 1.5);
            double rumble = Math.sin(2.0 * Math.PI * (42.0 + 12.0 * Math.sin(t * 4.0)) * t);
            double crack = nextNoise(v) * Math.exp(-10.0 * x);
            return (0.50 * rumble + 0.70 * crack) * env;
        }

        if ("GOAL_HORN".equals(v.kind)) {
            double attack = Math.min(1.0, t / 0.08);
            double release = Math.min(1.0, Math.max(0.0, (duration - t) / 0.25));
            double vibrato = 1.0 + (0.006 * Math.sin(2.0 * Math.PI * 5.2 * t));
            double horn =
                    0.56 * Math.sin(2.0 * Math.PI * 392.0 * vibrato * t) +
                    0.33 * Math.sin(2.0 * Math.PI * 523.25 * vibrato * t) +
                    0.18 * Math.sin(2.0 * Math.PI * 784.0 * t);
            return horn * attack * release * 0.78;
        }

        if ("WHISTLE".equals(v.kind)) {
            double env = Math.sin(Math.PI * Math.min(1.0, x));
            double warble = 2100.0 + (160.0 * Math.sin(2.0 * Math.PI * 8.0 * t));
            return (
                    0.70 * Math.sin(2.0 * Math.PI * warble * t) +
                    0.22 * Math.sin(2.0 * Math.PI * warble * 1.5 * t)
            ) * env;
        }

        if ("APPLAUSE".equals(v.kind)) {
            double env = Math.pow(1.0 - x, 0.35);
            double pulse = 0.35 + 0.65 * Math.abs(Math.sin(2.0 * Math.PI * 7.4 * t));
            return nextNoise(v) * env * pulse * 0.62;
        }

        if ("STADIUM_CHEER".equals(v.kind)) {
            double fadeIn = Math.min(1.0, t / 0.18);
            double fadeOut = Math.min(1.0, Math.max(0.0, (duration - t) / 0.35));
            double crowd = nextNoise(v) * 0.35;
            double chant =
                    0.24 * Math.sin(2.0 * Math.PI * 185.0 * t) +
                    0.18 * Math.sin(2.0 * Math.PI * 247.0 * t);
            return (crowd + chant) * fadeIn * fadeOut;
        }

        if ("SPARKLE".equals(v.kind)) {
            double env = Math.exp(-4.0 * x);
            return (
                    0.60 * Math.sin(2.0 * Math.PI * 988.0 * t) +
                    0.35 * Math.sin(2.0 * Math.PI * 1480.0 * t)
            ) * env * 0.55;
        }

        if ("CHIME".equals(v.kind)) {
            double env = Math.exp(-3.7 * x);
            return (
                    0.58 * Math.sin(2.0 * Math.PI * 660.0 * t) +
                    0.42 * Math.sin(2.0 * Math.PI * 990.0 * t) +
                    0.20 * Math.sin(2.0 * Math.PI * 1320.0 * t)
            ) * env * 0.55;
        }

        return 0.0;
    }

    private static double nextNoise(Voice v) {
        v.noiseState = v.noiseState * 1664525 + 1013904223;
        int bits = (v.noiseState >>> 8) & 0x00ffffff;
        return (bits / 8388607.5) - 1.0;
    }
}
