package com.fadcam.handball;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import java.util.Locale;

/** Builds the live handball scoreboard line that is burned into recorded video. */
public final class HandballVideoOverlay {
    private static final String PREFS = "handball_match";

    private HandballVideoOverlay() {}

    public static String append(Context context, String baseText) {
        String result = baseText == null ? "" : baseText;

        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (SportRecordingProfile.isMatchRecording(p)
                && p.getBoolean("video_overlay_enabled", true)) {
            String home = clean(
                    SportRecordingProfile.teamA(context, p),
                    context.getString(com.fadcam.R.string.sbc_team_a)
            );
            String away = clean(
                    SportRecordingProfile.teamB(context, p),
                    context.getString(com.fadcam.R.string.sbc_team_b)
            );
            String matchType = cleanType(SportRecordingProfile.matchDescription(context, p));
            int defaultA = Color.rgb(33, 150, 243);
            int defaultB = Color.rgb(46, 125, 50);
            String homeMarker = TeamColorMarker.marker(
                    p.getInt("team_a_color", defaultA)
            );
            String awayMarker = TeamColorMarker.marker(
                    p.getInt("team_b_color", defaultB)
            );
            String homeWithColor = homeMarker + " " + home;
            String awayWithColor = awayMarker + " " + away;
            int hs = p.getInt("home", 0);
            int as = p.getInt("away", 0);
            int half = p.getInt("half", 1);
            long elapsed = p.getLong("elapsed", 0L);

            if (p.getBoolean("running", false)) {
                elapsed += Math.max(
                        0L,
                        System.currentTimeMillis()
                                - p.getLong("started_at", System.currentTimeMillis())
                );
            }

            long sec = elapsed / 1000L;
            String style = p.getString("scoreboard_style", "compact_tv");
            String period = context.getString(com.fadcam.R.string.sbc_period_short, half);
            String overlay;

            if ("corner".equals(style)) {
                overlay = matchType + "\n"
                        + homeWithColor + "  " + hs + "\n"
                        + awayWithColor + "  " + as + "\n"
                        + String.format(
                                Locale.ROOT,
                                "%s  %02d:%02d",
                                period,
                                sec / 60L,
                                sec % 60L
                        );
            } else if ("minimal".equals(style)) {
                overlay = String.format(
                        Locale.ROOT,
                        "%s %d-%d %s · %02d:%02d · %s · %s",
                        homeWithColor,
                        hs,
                        as,
                        awayWithColor,
                        sec / 60L,
                        sec % 60L,
                        period,
                        matchType
                );
            } else {
                String scoreLine = String.format(
                        Locale.ROOT,
                        "%s %d : %d %s   %02d:%02d   %s",
                        homeWithColor,
                        hs,
                        as,
                        awayWithColor,
                        sec / 60L,
                        sec % 60L,
                        period
                );
                overlay = matchType + "\n" + scoreLine;
            }

            String base = result.trim();
            result = base.isEmpty() ? overlay : base + "\n" + overlay;
        }

        // Live FX are encoded by their own GL layer. Do not tunnel them through
        // watermark/Digital Forensics payloads.
        return result;
    }

    private static String clean(String s, String fallback) {
        if (s == null) return fallback;
        String t = s.trim();
        return t.isEmpty() ? fallback : t;
    }

    private static String cleanType(String s) {
        if (s == null) return "";
        return s.trim();
    }
}
