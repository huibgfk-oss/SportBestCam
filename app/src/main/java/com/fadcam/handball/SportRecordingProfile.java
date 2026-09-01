package com.fadcam.handball;

import android.content.Context;
import android.content.SharedPreferences;

import com.fadcam.R;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Central contract for SportBestCam match-vs-simple recording behavior.
 *
 * Internal preference values are deliberately language-neutral. User-visible labels
 * are resolved from Android resources so changing the app language does not rewrite
 * or corrupt existing match settings.
 */
public final class SportRecordingProfile {
    public static final String PREFS = "handball_match";
    public static final String KEY_RECORDING_MODE = "recording_mode";
    public static final String MODE_MATCH = "match";
    public static final String MODE_SIMPLE = "simple";

    private SportRecordingProfile() { }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isMatchRecording(Context context) {
        return isMatchRecording(prefs(context));
    }

    public static boolean isMatchRecording(SharedPreferences prefs) {
        return !MODE_SIMPLE.equals(prefs.getString(KEY_RECORDING_MODE, MODE_MATCH));
    }

    public static void setMatchRecording(Context context, boolean match) {
        prefs(context).edit().putString(KEY_RECORDING_MODE, match ? MODE_MATCH : MODE_SIMPLE).apply();
    }

    public static String teamA(Context context, SharedPreferences prefs) {
        return normalizedTeamName(context, prefs.getString("home_name", null), true);
    }

    public static String teamB(Context context, SharedPreferences prefs) {
        return normalizedTeamName(context, prefs.getString("away_name", null), false);
    }

    private static String normalizedTeamName(Context context, String raw, boolean first) {
        String fallback = context.getString(first ? R.string.sbc_team_a : R.string.sbc_team_b);
        if (raw == null || raw.trim().isEmpty()) return fallback;
        String v = raw.trim();
        if (first) {
            if (equalsAny(v, "Echipa A", "Team A", "Gazde", "GAZDE")) return fallback;
        } else {
            if (equalsAny(v, "Echipa B", "Team B", "Oaspeți", "Oaspeti", "OASPETI", "OASP")) return fallback;
        }
        return v;
    }

    private static boolean equalsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (candidate.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    public static String canonicalSportKey(String rawSport, String rawMatchType) {
        String raw = rawSport == null ? "" : rawSport.trim();
        if (raw.isEmpty() && rawMatchType != null) {
            String mt = rawMatchType.trim();
            int sep = mt.indexOf(" · ");
            raw = sep > 0 ? mt.substring(0, sep).trim() : mt;
        }
        String n = normalize(raw);
        if (n.isEmpty()) return "handball";
        if (n.equals("handbal") || n.equals("handball")) return "handball";
        if (n.equals("mini-handbal") || n.equals("mini handbal") || n.equals("mini-handball") || n.equals("mini handball")) return "mini_handball";
        if (n.equals("fotbal") || n.equals("football") || n.equals("soccer")) return "football";
        if (n.equals("futsal")) return "futsal";
        if (n.equals("baschet") || n.equals("basketball")) return "basketball";
        if (n.equals("volei") || n.equals("volleyball")) return "volleyball";
        if (n.equals("tenis") || n.equals("tennis")) return "tennis";
        if (n.equals("padel")) return "padel";
        if (n.equals("hochei") || n.equals("hockey") || n.equals("ice hockey")) return "hockey";
        if (n.equals("baseball")) return "baseball";
        if (n.equals("cricket")) return "cricket";
        if (n.equals("box") || n.equals("boxing")) return "boxing";
        if (n.equals("custom") || n.equals("personalizat")) return "custom";
        return "custom";
    }

    public static String sportKey(SharedPreferences prefs) {
        String key = prefs.getString("sport_key", null);
        if (key != null && !key.trim().isEmpty()) return key.trim();
        return canonicalSportKey(prefs.getString("sport_name", ""), prefs.getString("match_type", ""));
    }

    public static String sportLabel(Context context, String key) {
        if (key == null) key = "handball";
        switch (key) {
            case "mini_handball": return context.getString(R.string.sbc_sport_mini_handball);
            case "football": return context.getString(R.string.sbc_sport_football);
            case "futsal": return context.getString(R.string.sbc_sport_futsal);
            case "basketball": return context.getString(R.string.sbc_sport_basketball);
            case "volleyball": return context.getString(R.string.sbc_sport_volleyball);
            case "tennis": return context.getString(R.string.sbc_sport_tennis);
            case "padel": return context.getString(R.string.sbc_sport_padel);
            case "hockey": return context.getString(R.string.sbc_sport_hockey);
            case "baseball": return context.getString(R.string.sbc_sport_baseball);
            case "cricket": return context.getString(R.string.sbc_sport_cricket);
            case "boxing": return context.getString(R.string.sbc_sport_boxing);
            case "custom": return context.getString(R.string.sbc_sport_custom);
            case "handball":
            default: return context.getString(R.string.sbc_sport_handball);
        }
    }

    public static String matchTitle(Context context, SharedPreferences prefs) {
        String key = sportKey(prefs);
        if ("custom".equals(key)) {
            String custom = prefs.getString("custom_sport", "");
            if (custom == null || custom.trim().isEmpty()) {
                String old = prefs.getString("sport_name", "");
                if (old != null && !old.trim().isEmpty() && !"custom".equalsIgnoreCase(old.trim())) custom = old;
            }
            if (custom != null && !custom.trim().isEmpty()) return custom.trim();
        }
        return sportLabel(context, key);
    }

    public static String matchDescription(Context context, SharedPreferences prefs) {
        String title = matchTitle(context, prefs);
        int periods = Math.max(1, prefs.getInt("match_halves", 2));
        int minutes = Math.max(0, prefs.getInt("half_minutes", 30));
        if (minutes > 0) {
            return context.getString(R.string.sbc_match_description_minutes, title, periods, minutes);
        }
        return context.getString(R.string.sbc_match_description_rounds, title, periods);
    }

    public static String buildFilename(Context context, String prefix, String timestamp, String segmentSuffix, String extension) {
        String safePrefix = prefix == null ? "SportBestCam_" : prefix;
        String suffix = segmentSuffix == null ? "" : segmentSuffix;
        String ext = extension == null || extension.trim().isEmpty() ? "mp4" : extension.trim();

        // Remote Stream output keeps its established naming contract.
        if (safePrefix.startsWith("Stream_")) {
            return safePrefix + timestamp + suffix + "." + ext;
        }

        SharedPreferences p = prefs(context);
        if (!isMatchRecording(p)) {
            return safePrefix + timestamp + suffix + "." + ext;
        }

        String title = filePart(matchTitle(context, p), 28);
        String a = filePart(teamA(context, p), 24);
        String b = filePart(teamB(context, p), 24);
        if (title.isEmpty()) title = "Match";
        if (a.isEmpty()) a = "Team-A";
        if (b.isEmpty()) b = "Team-B";
        return safePrefix + title + "_" + a + "_vs_" + b + "_" + timestamp + suffix + "." + ext;
    }

    private static String filePart(String value, int max) {
        if (value == null) return "";
        String s = Normalizer.normalize(value.trim(), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^[._-]+|[._-]+$", "");
        if (s.length() > max) s = s.substring(0, max).replaceAll("[._-]+$", "");
        return s;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
    }
}
