package com.fadcam.handball;

import android.graphics.Color;

/**
 * Maps an arbitrary saved team RGB color to the nearest standard colored marker.
 * The UI keeps the exact selected RGB; this helper is only for the text-based
 * video scoreboard where the existing watermark renderer accepts plain text.
 */
public final class TeamColorMarker {
    private TeamColorMarker() {}

    private static final int[] MARKER_COLORS = {
            Color.rgb(33, 150, 243),   // blue
            Color.rgb(46, 125, 50),    // green
            Color.rgb(211, 47, 47),    // red
            Color.rgb(245, 124, 0),    // orange
            Color.rgb(123, 31, 162),   // purple
            Color.rgb(251, 192, 45),   // yellow
            Color.rgb(121, 85, 72),    // brown
            Color.rgb(33, 33, 33),     // black
            Color.rgb(245, 245, 245)   // white
    };

    private static final String[] MARKERS = {
            "🔵", "🟢", "🔴", "🟠", "🟣", "🟡", "🟤", "⚫", "⚪"
    };

    public static String marker(int color) {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        for (int i = 0; i < MARKER_COLORS.length; i++) {
            int dr = r - Color.red(MARKER_COLORS[i]);
            int dg = g - Color.green(MARKER_COLORS[i]);
            int db = b - Color.blue(MARKER_COLORS[i]);
            long distance = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return MARKERS[best];
    }
}
