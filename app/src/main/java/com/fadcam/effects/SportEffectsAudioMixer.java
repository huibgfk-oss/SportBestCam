package com.fadcam.effects;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * Procedural PCM16 SFX mixer.
 *
 * SFX are inserted digitally before AAC encoding. While an SFX voice is active,
 * ambient microphone PCM is ducked slightly so the effect remains clearly audible.
 */
public final class SportEffectsAudioMixer {
    private static final Object LOCK = new Object();
    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    private static final ArrayList<Voice> VOICES = new ArrayList<>();
    private static final long MAX_PENDING_AGE_MS = 3500L;

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
            this.totalFrames = Math.max(
                    1,
                    (int) (durationSeconds(this.kind) * this.sampleRate)
            );
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
            while (PENDING.size() > 16) PENDING.removeFirst();
        }
    }

    /**
     * Trigger one SFX in both destinations:
     * 1) digital PCM queue for the recorded AAC track;
     * 2) local AudioTrack monitor so the operator hears the effect immediately.
     */
    public static void trigger(String kind, long triggerAtMs) {
        enqueue(kind, triggerAtMs);
        playMonitor(kind);
    }

    private static void playMonitor(String kind) {
        if (kind == null || kind.trim().isEmpty()) return;

        final String safeKind = kind.trim();

        new Thread(() -> {
            android.media.AudioTrack track = null;

            try {
                final int sampleRate = 48000;
                Voice voice = new Voice(safeKind, sampleRate);
                int frames = voice.totalFrames;
                byte[] pcm = new byte[frames * 2];

                for (int i = 0; i < frames; i++) {
                    double sample = sampleVoice(voice);
                    voice.frame++;

                    // Monitor is intentionally lower than the digitally recorded SFX
                    // to reduce acoustic re-capture by the microphone.
                    int value = (int) Math.round(
                            Math.max(-1.0, Math.min(1.0, sample))
                                    * 32767.0
                                    * 0.72
                    );
                    value = Math.max(-32768, Math.min(32767, value));

                    int p = i * 2;
                    pcm[p] = (byte) (value & 0xff);
                    pcm[p + 1] = (byte) ((value >> 8) & 0xff);
                }

                android.media.AudioAttributes attributes =
                        new android.media.AudioAttributes.Builder()
                                .setUsage(
                                        android.media.AudioAttributes
                                                .USAGE_MEDIA
                                )
                                .setContentType(
                                        android.media.AudioAttributes
                                                .CONTENT_TYPE_MUSIC
                                )
                                .build();

                android.media.AudioFormat format =
                        new android.media.AudioFormat.Builder()
                                .setEncoding(
                                        android.media.AudioFormat
                                                .ENCODING_PCM_16BIT
                                )
                                .setSampleRate(sampleRate)
                                .setChannelMask(
                                        android.media.AudioFormat
                                                .CHANNEL_OUT_MONO
                                )
                                .build();

                track = new android.media.AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(format)
                        .setTransferMode(
                                android.media.AudioTrack.MODE_STATIC
                        )
                        .setBufferSizeInBytes(pcm.length)
                        .build();

                if (android.os.Build.VERSION.SDK_INT
                        >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    track.setVolume(1.0f);
                }

                int written = track.write(
                        pcm,
                        0,
                        pcm.length,
                        android.media.AudioTrack.WRITE_BLOCKING
                );

                if (written <= 0) {
                    android.util.Log.w(
                            "SportBestCamFX",
                            "SFX monitor write failed for " + safeKind
                    );
                    return;
                }

                android.util.Log.i(
                        "SportBestCamFX",
                        "SFX monitor PLAY: " + safeKind
                                + " bytes=" + written
                );

                track.play();

                android.util.Log.i(
                        "SportBestCamFX",
                        "SFX monitor state="
                                + track.getPlayState()
                                + " session="
                                + track.getAudioSessionId()
                );

                long sleepMs = Math.max(
                        250L,
                        (long) (
                                durationSeconds(safeKind) * 1000.0
                        ) + 180L
                );

                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } catch (Throwable t) {
                android.util.Log.e(
                        "SportBestCamFX",
                        "SFX monitor failed: " + safeKind,
                        t
                );
            } finally {
                if (track != null) {
                    try {
                        track.stop();
                    } catch (Throwable ignored) {
                    }

                    try {
                        track.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "SportBestCam-SFX-" + safeKind).start();
    }

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
                Pending pending = PENDING.removeFirst();
                if (now - pending.atMs <= MAX_PENDING_AGE_MS) {
                    VOICES.add(new Voice(pending.kind, sampleRate));
                    android.util.Log.i(
                            "SportBestCamFX",
                            "SFX RECORDER PCM: " + pending.kind
                                    + " rate=" + sampleRate
                                    + " channels=" + channels
                    );
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

                fx = Math.max(-1.0, Math.min(1.0, fx));

                // Strong enough to remain audible in a noisy sports hall.
                int fxSample = (int) Math.round(fx * 32767.0 * 0.86);
                int base = frameIndex * bytesPerFrame;

                for (int ch = 0; ch < channels; ch++) {
                    int idx = base + (ch * 2);
                    int original = (short) (
                            ((pcm[idx + 1] & 0xff) << 8)
                                    | (pcm[idx] & 0xff)
                    );

                    // Duck ambient mic by ~30% only while an effect is sounding.
                    int mixed = (int) Math.round(original * 0.70) + fxSample;
                    mixed = Math.max(-32768, Math.min(32767, mixed));

                    pcm[idx] = (byte) (mixed & 0xff);
                    pcm[idx + 1] = (byte) ((mixed >> 8) & 0xff);
                }

                if (VOICES.isEmpty() && PENDING.isEmpty()) {
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
        if ("BOOM".equals(kind)) return 1.45;
        if ("THUNDER".equals(kind)) return 2.55;
        if ("GOAL_HORN".equals(kind)) return 2.25;
        if ("AIR_HORN".equals(kind)) return 1.55;
        if ("APPLAUSE".equals(kind)) return 2.70;
        if ("WHISTLE".equals(kind)) return 1.15;
        if ("STADIUM_CHEER".equals(kind)) return 3.10;
        if ("SPARKLE".equals(kind)) return 0.95;
        if ("CHIME".equals(kind)) return 1.00;
        if ("DRUM_ROLL".equals(kind)) return 2.15;
        if ("SIREN".equals(kind)) return 2.30;
        if ("SHUTTER".equals(kind)) return 0.45;
        if ("SWOOSH".equals(kind)) return 0.80;
        if ("LASER".equals(kind)) return 1.20;
        if ("SYNTH_HIT".equals(kind)) return 1.10;
        return 1.0;
    }

    private static double sampleVoice(Voice v) {
        double t = v.frame / (double) v.sampleRate;
        double duration = v.totalFrames / (double) v.sampleRate;
        double x = duration <= 0.0 ? 1.0 : Math.min(1.0, t / duration);

        if ("BOOM".equals(v.kind)) {
            double env = Math.exp(-4.2 * x);
            double freq = 82.0 - (43.0 * x);
            double rumble = Math.sin(2.0 * Math.PI * freq * t);
            double noise = nextNoise(v) * Math.exp(-6.5 * x);
            return (rumble * 0.82 + noise * 0.42) * env;
        }

        if ("THUNDER".equals(v.kind)) {
            double env = Math.pow(1.0 - x, 1.25);
            double rumble = Math.sin(
                    2.0 * Math.PI
                            * (46.0 + 15.0 * Math.sin(t * 3.7))
                            * t
            );
            double crack = nextNoise(v) * Math.exp(-8.5 * x);
            return (0.58 * rumble + 0.82 * crack) * env;
        }

        if ("GOAL_HORN".equals(v.kind) || "AIR_HORN".equals(v.kind)) {
            double attack = Math.min(1.0, t / 0.045);
            double release = Math.min(
                    1.0,
                    Math.max(0.0, (duration - t) / 0.20)
            );
            double base = "AIR_HORN".equals(v.kind) ? 466.16 : 392.0;
            double vibrato = 1.0 + (
                    0.008 * Math.sin(2.0 * Math.PI * 5.5 * t)
            );
            double horn =
                    0.62 * Math.sin(2.0 * Math.PI * base * vibrato * t)
                    + 0.40 * Math.sin(2.0 * Math.PI * base * 1.33 * vibrato * t)
                    + 0.24 * Math.sin(2.0 * Math.PI * base * 2.0 * t);
            return horn * attack * release * 0.90;
        }

        if ("WHISTLE".equals(v.kind)) {
            double env = Math.sin(Math.PI * Math.min(1.0, x));
            double warble = 2250.0
                    + (180.0 * Math.sin(2.0 * Math.PI * 8.5 * t));
            return (
                    0.82 * Math.sin(2.0 * Math.PI * warble * t)
                    + 0.28 * Math.sin(2.0 * Math.PI * warble * 1.5 * t)
            ) * env;
        }

        if ("APPLAUSE".equals(v.kind)) {
            double env = Math.pow(1.0 - x, 0.28);
            double pulse = 0.38
                    + 0.62 * Math.abs(
                            Math.sin(2.0 * Math.PI * 8.1 * t)
                    );
            return nextNoise(v) * env * pulse * 0.78;
        }

        if ("STADIUM_CHEER".equals(v.kind)) {
            double fadeIn = Math.min(1.0, t / 0.16);
            double fadeOut = Math.min(
                    1.0,
                    Math.max(0.0, (duration - t) / 0.32)
            );
            double crowd = nextNoise(v) * 0.72;
            double chant =
                    0.28 * Math.sin(2.0 * Math.PI * 176.0 * t)
                    + 0.22 * Math.sin(2.0 * Math.PI * 235.0 * t);
            return (crowd + chant) * fadeIn * fadeOut;
        }

        if ("SPARKLE".equals(v.kind)) {
            double env = Math.exp(-3.4 * x);
            return (
                    0.66 * Math.sin(2.0 * Math.PI * 988.0 * t)
                    + 0.45 * Math.sin(2.0 * Math.PI * 1480.0 * t)
            ) * env * 0.70;
        }

        if ("CHIME".equals(v.kind)) {
            double env = Math.exp(-3.2 * x);
            return (
                    0.62 * Math.sin(2.0 * Math.PI * 660.0 * t)
                    + 0.46 * Math.sin(2.0 * Math.PI * 990.0 * t)
                    + 0.28 * Math.sin(2.0 * Math.PI * 1320.0 * t)
            ) * env * 0.72;
        }

        if ("DRUM_ROLL".equals(v.kind)) {
            double env = 0.45 + 0.55 * x;
            double pulse = Math.pow(
                    Math.abs(Math.sin(2.0 * Math.PI * (10.0 + 13.0 * x) * t)),
                    5.0
            );
            return (nextNoise(v) * 0.75 + Math.sin(2.0 * Math.PI * 110.0 * t) * 0.25)
                    * pulse * env;
        }

        if ("SIREN".equals(v.kind)) {
            double freq = 620.0
                    + 240.0 * Math.sin(2.0 * Math.PI * 0.85 * t);
            return (
                    0.72 * Math.sin(2.0 * Math.PI * freq * t)
                    + 0.24 * Math.sin(2.0 * Math.PI * freq * 2.0 * t)
            ) * 0.85;
        }

        if ("SHUTTER".equals(v.kind)) {
            double click1 = nextNoise(v) * Math.exp(-35.0 * x);
            double click2 = nextNoise(v)
                    * Math.exp(-55.0 * Math.abs(x - 0.45));
            return (click1 + click2) * 0.90;
        }

        if ("SWOOSH".equals(v.kind)) {
            double env = Math.sin(Math.PI * x);
            double noise = nextNoise(v);
            double tone = Math.sin(
                    2.0 * Math.PI * (180.0 + 900.0 * x * x) * t
            );
            return (noise * 0.44 + tone * 0.50) * env;
        }

        if ("LASER".equals(v.kind)) {
            double env = Math.exp(-2.6 * x);
            double freq = 1900.0 - 1450.0 * x;
            return (
                    Math.sin(2.0 * Math.PI * freq * t)
                    + 0.25 * Math.sin(2.0 * Math.PI * freq * 2.0 * t)
            ) * env * 0.72;
        }

        if ("SYNTH_HIT".equals(v.kind)) {
            double env = Math.exp(-4.0 * x);
            return (
                    0.55 * Math.sin(2.0 * Math.PI * 110.0 * t)
                    + 0.40 * Math.sin(2.0 * Math.PI * 220.0 * t)
                    + 0.30 * Math.sin(2.0 * Math.PI * 440.0 * t)
            ) * env;
        }

        return 0.0;
    }

    private static double nextNoise(Voice v) {
        v.noiseState = v.noiseState * 1664525 + 1013904223;
        int bits = (v.noiseState >>> 8) & 0x00ffffff;
        return (bits / 8388607.5) - 1.0;
    }
}
