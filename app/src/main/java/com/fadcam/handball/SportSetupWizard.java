package com.fadcam.handball;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;

import com.fadcam.R;

import java.util.Locale;

/**
 * SportBestCam 4-step pre-match wizard.
 *
 * It only writes the existing handball_match SharedPreferences contract used by
 * the current HUD/recording overlay. Camera, Media3 and RecordingService are not
 * touched by this class.
 */
public final class SportSetupWizard {
    public interface Listener {
        void onMatchConfigured();
    }

    private static final String PREFS = "handball_match";
    private static final class SportPreset {
        final String icon;
        final String key;
        final int periods;
        final int minutes;
        SportPreset(String icon, String key, int periods, int minutes) {
            this.icon = icon;
            this.key = key;
            this.periods = periods;
            this.minutes = minutes;
        }
    }
    private static final SportPreset[] SPORTS = {
            new SportPreset("🤾", "handball", 2, 30),
            new SportPreset("🤾", "mini_handball", 2, 20),
            new SportPreset("⚽", "football", 2, 45),
            new SportPreset("🥅", "futsal", 2, 20),
            new SportPreset("🏀", "basketball", 4, 10),
            new SportPreset("🏐", "volleyball", 5, 25),
            new SportPreset("🎾", "tennis", 3, 0),
            new SportPreset("🎾", "padel", 3, 0),
            new SportPreset("🏒", "hockey", 3, 20),
            new SportPreset("⚾", "baseball", 9, 0),
            new SportPreset("🏏", "cricket", 2, 0),
            new SportPreset("🥊", "boxing", 3, 3),
            new SportPreset("✎", "custom", 2, 30)
    };
    private static final String[] STYLE_VALUES = {
            "compact_tv",
            "corner",
            "minimal"
    };

    // Expanded team palette. Keep the first six values unchanged for backwards
    // compatibility with already-saved matches and add ten commonly used team colors.
    private static final int[] TEAM_COLORS = {
            Color.rgb(33, 150, 243),   // blue
            Color.rgb(46, 125, 50),    // green
            Color.rgb(211, 47, 47),    // red
            Color.rgb(245, 124, 0),    // orange
            Color.rgb(123, 31, 162),   // purple
            Color.rgb(0, 137, 123),    // teal
            Color.rgb(251, 192, 45),   // yellow
            Color.rgb(0, 172, 193),    // cyan
            Color.rgb(216, 27, 96),    // pink
            Color.rgb(124, 179, 66),   // lime green
            Color.rgb(121, 85, 72),    // brown
            Color.rgb(40, 53, 147),    // navy / indigo
            Color.rgb(117, 117, 117),  // grey
            Color.rgb(33, 33, 33),     // black
            Color.rgb(245, 245, 245),  // white
            Color.rgb(255, 193, 7)     // amber
    };
    private final Context context;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final int d;

    private AlertDialog dialog;
    private LinearLayout progressRow;
    private LinearLayout body;
    private Button backButton;
    private Button nextButton;
    private int step;
    private int selectedSport;
    private String customSport;
    private String teamA;
    private String teamB;
    private int teamAColor;
    private int teamBColor;
    private String scoreboardStyle;
    private int periods;
    private int minutes;
    private boolean autoGoal;
    private boolean matchRecording;
    private Switch recordingModeSwitch;
    private EditText customSportField;
    private EditText teamAField;
    private EditText teamBField;
    private EditText periodsField;
    private EditText minutesField;
    private Switch autoGoalSwitch;
    private Spinner styleSpinner;
    private LinearLayout previewHolder;
    public SportSetupWizard(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.d = Math.max(1, (int) context.getResources().getDisplayMetrics().density);
        load();
    }
    private void load() {
        String key = SportRecordingProfile.sportKey(prefs);
        selectedSport = 0;
        for (int i = 0; i < SPORTS.length; i++) {
            if (SPORTS[i].key.equals(key)) {
                selectedSport = i;
                break;
            }
        }
        customSport = prefs.getString("custom_sport", "");
        if ("custom".equals(key) && (customSport == null || customSport.trim().isEmpty())) {
            String old = prefs.getString("sport_name", "");
            if (old != null && !old.trim().isEmpty()
                    && !"custom".equalsIgnoreCase(old.trim())) {
                customSport = old.trim();
            }
        }
        if (customSport == null) customSport = "";
        teamA = SportRecordingProfile.teamA(context, prefs);
        teamB = SportRecordingProfile.teamB(context, prefs);
        teamAColor = prefs.getInt("team_a_color", TEAM_COLORS[0]);
        teamBColor = prefs.getInt("team_b_color", TEAM_COLORS[1]);
        scoreboardStyle = prefs.getString("scoreboard_style", "compact_tv");
        periods = Math.max(1, prefs.getInt("match_halves", SPORTS[selectedSport].periods));
        minutes = Math.max(0, prefs.getInt("half_minutes", SPORTS[selectedSport].minutes));
        autoGoal = prefs.getBoolean("auto_goal", false);
        matchRecording = SportRecordingProfile.isMatchRecording(prefs);
    }
    public void show() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(14 * d, 10 * d, 14 * d, 10 * d);
        root.setBackgroundColor(Color.WHITE);

        TextView title = text(context.getString(R.string.sbc_wizard_title), 20f, Color.rgb(20, 20, 20), true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, lpMatch(44));
        progressRow = new LinearLayout(context);
        progressRow.setGravity(Gravity.CENTER);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(progressRow, lpMatch(42));
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(2 * d, 4 * d, 2 * d, 4 * d);
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollLp);
        LinearLayout nav = new LinearLayout(context);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(0, 8 * d, 0, 0);
        Button cancel = navButton(context.getString(R.string.sbc_cancel), Color.rgb(84, 110, 122));
        backButton = navButton(context.getString(R.string.sbc_back), Color.rgb(69, 90, 100));
        nextButton = navButton(context.getString(R.string.sbc_next), Color.rgb(25, 118, 210));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, 46 * d, 1f);
        nlp.setMargins(3 * d, 0, 3 * d, 0);
        nav.addView(cancel, nlp);
        nav.addView(backButton, nlp);
        nav.addView(nextButton, nlp);
        root.addView(nav, lpMatch(54));
        dialog = new AlertDialog.Builder(context).setView(root).create();
        cancel.setOnClickListener(v -> dialog.dismiss());
        backButton.setOnClickListener(v -> {
            captureStep();
            if (step > 0) {
                step--;
                renderStep();
            }
        });
        nextButton.setOnClickListener(v -> {
            captureStep();
            if (!validateStep()) return;
            if (step == 0 && !matchRecording) {
                commitSimpleMode();
                dialog.dismiss();
                if (listener != null) listener.onMatchConfigured();
            } else if (step < 3) {
                step++;
                renderStep();
            } else {
                commit();
                dialog.dismiss();
                if (listener != null) listener.onMatchConfigured();
            }
        });
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            boolean landscape = context.getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
            int width = context.getResources().getDisplayMetrics().widthPixels;
            int height = context.getResources().getDisplayMetrics().heightPixels;
            w.setLayout((int) (width * (landscape ? 0.72f : 0.95f)),
                    (int) (height * (landscape ? 0.88f : 0.90f)));
            // Fullscreen/edge-to-edge parent state can leave this flag set,
            // preventing Android's IME from appearing for otherwise valid EditTexts.
            w.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        renderStep();
    }
    private void renderProgress() {
        progressRow.removeAllViews();
        for (int i = 0; i < 4; i++) {
            TextView circle = text(String.valueOf(i + 1), 14f,
                    i <= step ? Color.WHITE : Color.rgb(90, 90, 90), true);
            GradientDrawable bg = rounded(i <= step
                    ? Color.rgb(25, 118, 210)
                    : Color.rgb(232, 234, 236), 20, 0, Color.TRANSPARENT);
            circle.setBackground(bg);
            circle.setGravity(Gravity.CENTER);
            progressRow.addView(circle, new LinearLayout.LayoutParams(34 * d, 34 * d));
            if (i < 3) {
                View line = new View(context);
                line.setBackgroundColor(i < step
                        ? Color.rgb(25, 118, 210)
                        : Color.rgb(215, 218, 221));
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(42 * d, 2 * d);
                ll.setMargins(6 * d, 0, 6 * d, 0);
                progressRow.addView(line, ll);
            }
        }
    }
    private void renderStep() {
        body.removeAllViews();
        recordingModeSwitch = null;
        customSportField = null;
        teamAField = null;
        teamBField = null;
        periodsField = null;
        minutesField = null;
        autoGoalSwitch = null;
        styleSpinner = null;
        previewHolder = null;
        renderProgress();
        backButton.setEnabled(step > 0);
        backButton.setAlpha(step > 0 ? 1f : 0.35f);
        nextButton.setText(step == 0 && !matchRecording
                ? context.getString(R.string.sbc_use_simple_video)
                : (step == 3
                    ? context.getString(R.string.sbc_start_match)
                    : context.getString(R.string.sbc_next)));
        if (step == 0) buildSportStep();
        else if (step == 1) buildTeamsStep();
        else if (step == 2) buildRulesStep();
        else buildConfirmStep();
    }

    private void buildSportStep() {
        body.addView(sectionTitle(context.getString(R.string.sbc_wizard_step1_title)));
        recordingModeSwitch = new Switch(context);
        recordingModeSwitch.setText(context.getString(R.string.sbc_match_recording));
        recordingModeSwitch.setTextColor(Color.rgb(35, 35, 35));
        recordingModeSwitch.setChecked(matchRecording);
        recordingModeSwitch.setPadding(8 * d, 5 * d, 8 * d, 5 * d);
        recordingModeSwitch.setOnCheckedChangeListener((button, checked) -> {
            matchRecording = checked;
            renderStep();
        });
        body.addView(recordingModeSwitch, lpMatch(48));
        if (!matchRecording) {
            TextView simple = info(context.getString(R.string.sbc_simple_video_info));
            simple.setTextSize(13f);
            simple.setTextColor(Color.rgb(65, 65, 65));
            simple.setPadding(12 * d, 12 * d, 12 * d, 12 * d);
            simple.setBackground(rounded(Color.rgb(245, 247, 249), 12, 1, Color.rgb(205, 210, 215)));
            body.addView(simple, lpWrap());
            return;
        }
        body.addView(info(context.getString(R.string.sbc_wizard_choose_sport_info)));
        for (int i = 0; i < SPORTS.length; i += 2) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < 2; j++) {
                int idx = i + j;
                if (idx >= SPORTS.length) {
                    View spacer = new View(context);
                    row.addView(spacer, new LinearLayout.LayoutParams(0, 82 * d, 1f));
                    continue;
                }
                SportPreset p = SPORTS[idx];
                Button b = new Button(context);
                b.setAllCaps(false);
                b.setText(p.icon + "\n" + SportRecordingProfile.sportLabel(context, p.key));
                b.setTextSize(13f);
                b.setTextColor(selectedSport == idx ? Color.WHITE : Color.rgb(25, 25, 25));
                b.setGravity(Gravity.CENTER);
                b.setMinHeight(0);
                b.setBackgroundTintList(ColorStateList.valueOf(selectedSport == idx
                        ? Color.rgb(25, 118, 210)
                        : Color.rgb(245, 246, 247)));
                final int selected = idx;
                b.setOnClickListener(v -> {
                    selectedSport = selected;
                    if (selectedSport != SPORTS.length - 1) {
                        periods = Math.max(1, SPORTS[selectedSport].periods);
                        minutes = Math.max(0, SPORTS[selectedSport].minutes);
                    }
                    renderStep();
                });
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, 82 * d, 1f);
                bp.setMargins(4 * d, 4 * d, 4 * d, 4 * d);
                row.addView(b, bp);
            }
            body.addView(row);
        }
        if (selectedSport == SPORTS.length - 1) {
            body.addView(label(context.getString(R.string.sbc_custom_sport_label)));
            customSportField = field(context.getString(R.string.sbc_custom_sport_hint), customSport);
            body.addView(customSportField, lpMatch(48));
        }
    }
    private void buildTeamsStep() {
        body.addView(sectionTitle(context.getString(R.string.sbc_wizard_step2_title)));
        body.addView(info(context.getString(R.string.sbc_wizard_teams_info)));

        previewHolder = new LinearLayout(context);
        previewHolder.setOrientation(LinearLayout.VERTICAL);
        previewHolder.setPadding(6 * d, 6 * d, 6 * d, 8 * d);
        body.addView(previewHolder, lpWrap());
        rebuildPreview();
        teamAField = field(context.getString(R.string.sbc_team_a_name), teamA);
        teamBField = field(context.getString(R.string.sbc_team_b_name), teamB);
        body.addView(label(context.getString(R.string.sbc_team_a)));
        body.addView(teamAField, lpMatch(48));
        body.addView(label(context.getString(R.string.sbc_team_b)));
        body.addView(teamBField, lpMatch(48));
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (teamAField != null) teamA = clean(teamAField.getText().toString(), context.getString(R.string.sbc_team_a));
                if (teamBField != null) teamB = clean(teamBField.getText().toString(), context.getString(R.string.sbc_team_b));
                rebuildPreview();
            }
            @Override public void afterTextChanged(Editable s) { }
        };
        teamAField.addTextChangedListener(watcher);
        teamBField.addTextChangedListener(watcher);
        LinearLayout colorRow = new LinearLayout(context);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        Button colorA = colorButton(true);
        Button colorB = colorButton(false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, 44 * d, 1f);
        cp.setMargins(3 * d, 4 * d, 3 * d, 4 * d);
        colorRow.addView(colorA, cp);
        colorRow.addView(colorB, cp);
        body.addView(colorRow);
        body.addView(label(context.getString(R.string.sbc_scoreboard_style)));
        styleSpinner = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                context, android.R.layout.simple_spinner_item, styleLabels()) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(Color.rgb(25, 25, 25));
                v.setTextSize(14f);
                v.setPadding(10 * d, 0, 10 * d, 0);
                return v;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getDropDownView(position, convertView, parent);
                v.setTextColor(Color.rgb(25, 25, 25));
                v.setBackgroundColor(Color.WHITE);
                v.setPadding(12 * d, 8 * d, 12 * d, 8 * d);
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        styleSpinner.setAdapter(adapter);
        styleSpinner.setSelection(styleIndex(scoreboardStyle));
        styleSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                scoreboardStyle = STYLE_VALUES[Math.max(0, Math.min(STYLE_VALUES.length - 1, position))];
                rebuildPreview();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        body.addView(styleSpinner, lpMatch(48));
    }
    private void buildRulesStep() {
        body.addView(sectionTitle(context.getString(R.string.sbc_wizard_step3_title)));
        body.addView(info(context.getString(R.string.sbc_wizard_rules_info)));
        body.addView(label(context.getString(R.string.sbc_sport_description)));
        TextView sportView = text(sportName(), 15f, Color.rgb(25, 25, 25), true);
        sportView.setPadding(10 * d, 6 * d, 10 * d, 6 * d);
        sportView.setBackground(rounded(Color.rgb(245, 246, 247), 10, 1, Color.rgb(210, 214, 218)));
        body.addView(sportView, lpMatch(42));
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        periodsField = field(context.getString(R.string.sbc_periods_rounds), String.valueOf(periods));
        minutesField = field(context.getString(R.string.sbc_minutes_per_period), String.valueOf(minutes));
        configureNumericField(periodsField);
        configureNumericField(minutesField);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, 48 * d, 1f);
        fp.setMargins(3 * d, 4 * d, 3 * d, 4 * d);
        row.addView(periodsField, fp);
        row.addView(minutesField, fp);
        body.addView(row);
        autoGoalSwitch = new Switch(context);
        autoGoalSwitch.setText(context.getString(R.string.sbc_auto_goal));
        autoGoalSwitch.setTextColor(Color.rgb(35, 35, 35));
        autoGoalSwitch.setChecked(autoGoal);
        autoGoalSwitch.setPadding(8 * d, 6 * d, 8 * d, 6 * d);
        body.addView(autoGoalSwitch, lpMatch(48));

        TextView note = info(context.getString(R.string.sbc_auto_goal_note));
        body.addView(note);
    }
    private void buildConfirmStep() {
        body.addView(sectionTitle(context.getString(R.string.sbc_wizard_step4_title)));
        long elapsed = prefs.getLong("elapsed", 0L);
        if (prefs.getBoolean("running", false)) {
            elapsed += Math.max(0L, System.currentTimeMillis()
                    - prefs.getLong("started_at", System.currentTimeMillis()));
        }
        long sec = elapsed / 1000L;
        String oldSummary = String.format(Locale.ROOT,
                "%s  %d : %d  %s\n%02d:%02d · R%d\n%s",
                SportRecordingProfile.teamA(context, prefs),
                prefs.getInt("home", 0),
                prefs.getInt("away", 0),
                SportRecordingProfile.teamB(context, prefs),
                sec / 60L, sec % 60L,
                prefs.getInt("half", 1),
                SportRecordingProfile.matchDescription(context, prefs));
        TextView previous = text(context.getString(R.string.sbc_current_match) + "\n" + oldSummary, 13f, Color.rgb(80, 80, 80), false);
        previous.setPadding(12 * d, 10 * d, 12 * d, 10 * d);
        previous.setBackground(rounded(Color.rgb(247, 247, 248), 12, 1, Color.rgb(220, 222, 225)));
        body.addView(previous, lpWrap());
        body.addView(label(context.getString(R.string.sbc_new_match_label)));
        TextView next = text(
                teamA + "  0 : 0  " + teamB
                        + "\n" + matchDescription()
                        + "\n" + context.getString(R.string.sbc_periods_summary, periods, minutes)
                        + "\n" + context.getString(R.string.sbc_scoreboard_value, styleLabels()[styleIndex(scoreboardStyle)])
                        + "\n" + context.getString(R.string.sbc_auto_goal_value, autoGoal ? context.getString(R.string.sbc_status_on) : context.getString(R.string.sbc_status_off)),
                14f, Color.rgb(25, 25, 25), true);
        next.setPadding(12 * d, 10 * d, 12 * d, 10 * d);
        next.setBackground(rounded(Color.rgb(235, 245, 255), 12, 1, Color.rgb(25, 118, 210)));
        body.addView(next, lpWrap());
        LinearLayout finalPreview = new LinearLayout(context);
        finalPreview.setOrientation(LinearLayout.VERTICAL);
        finalPreview.setPadding(0, 8 * d, 0, 4 * d);
        previewHolder = finalPreview;
        body.addView(finalPreview, lpWrap());
        rebuildPreview();

        body.addView(info(context.getString(R.string.sbc_wizard_confirm_info)));
    }
    private void captureStep() {
        if (step == 0 && recordingModeSwitch != null) {
            matchRecording = recordingModeSwitch.isChecked();
        }
        if (step == 0 && customSportField != null) {
            customSport = customSportField.getText().toString().trim();
        }
        if (step == 1) {
            if (teamAField != null) teamA = clean(teamAField.getText().toString(), context.getString(R.string.sbc_team_a));
            if (teamBField != null) teamB = clean(teamBField.getText().toString(), context.getString(R.string.sbc_team_b));
            if (styleSpinner != null) {
                int pos = styleSpinner.getSelectedItemPosition();
                scoreboardStyle = STYLE_VALUES[Math.max(0, Math.min(STYLE_VALUES.length - 1, pos))];
            }
        }
        if (step == 2) {
            periods = parseInt(periodsField, periods, 1, 12);
            minutes = parseInt(minutesField, minutes, 0, 180);
            if (autoGoalSwitch != null) autoGoal = autoGoalSwitch.isChecked();
        }
    }
    private boolean validateStep() {
        if (step == 0 && !matchRecording) return true;
        if (step == 0 && selectedSport == SPORTS.length - 1
                && (customSport == null || customSport.trim().isEmpty())) {
            if (customSportField != null) customSportField.setError(context.getString(R.string.sbc_custom_sport_error));
            return false;
        }
        if (step == 1) {
            teamA = clean(teamA, context.getString(R.string.sbc_team_a));
            teamB = clean(teamB, context.getString(R.string.sbc_team_b));
        }
        return true;
    }
    private void commitSimpleMode() {
        if (prefs.getBoolean("running", false)) {
            long elapsed = prefs.getLong("elapsed", 0L);
            elapsed += Math.max(0L, System.currentTimeMillis()
                    - prefs.getLong("started_at", System.currentTimeMillis()));
            prefs.edit().putLong("elapsed", elapsed).apply();
        }
        prefs.edit()
                .putString(SportRecordingProfile.KEY_RECORDING_MODE, SportRecordingProfile.MODE_SIMPLE)
                .putBoolean("running", false)
                .putBoolean("timer_paused_by_video", false)
                .putBoolean("video_recording_running", false)
                .apply();
    }
    private void commit() {
        String sportKey = SPORTS[selectedSport].key;
        String sport = sportName();
        SharedPreferences.Editor e = prefs.edit()
                .putString(SportRecordingProfile.KEY_RECORDING_MODE, SportRecordingProfile.MODE_MATCH)
                .putString("sport_key", sportKey)
                .putString("sport_name", sport)
                .putString("custom_sport", "custom".equals(sportKey) ? clean(customSport, context.getString(R.string.sbc_sport_custom)) : "")
                .putString("match_type", matchDescription())
                .putInt("match_halves", periods)
                .putInt("half_minutes", minutes)
                .putString("home_name", teamA)
                .putString("away_name", teamB)
                .putString("home_short", shortName(teamA))
                .putString("away_short", shortName(teamB))
                .putInt("team_a_color", teamAColor)
                .putInt("team_b_color", teamBColor)
                .putString("scoreboard_style", scoreboardStyle)
                .putBoolean("auto_goal", autoGoal)
                .putBoolean("goal_prompt_snoozed", false)
                .putInt("home", 0)
                .putInt("away", 0)
                .putInt("half", 1)
                .putLong("elapsed", 0L)
                .putBoolean("running", false)
                .putBoolean("timer_paused_by_video", false)
                .putBoolean("video_recording_running", false)
                .putLong("started_at", System.currentTimeMillis())
                .putBoolean("video_overlay_enabled", true);
        e.apply();
    }
    private String sportName() {
        if (selectedSport == SPORTS.length - 1) {
            return clean(customSport, context.getString(R.string.sbc_sport_custom));
        }
        return SportRecordingProfile.sportLabel(context, SPORTS[selectedSport].key);
    }
    private String matchDescription() {
        String sport = sportName();
        if (minutes > 0) {
            return context.getString(R.string.sbc_match_description_minutes, sport, periods, minutes);
        }
        return context.getString(R.string.sbc_match_description_rounds, sport, periods);
    }
    private void rebuildPreview() {
        if (previewHolder == null) return;
        previewHolder.removeAllViews();
        String a = clean(teamA, context.getString(R.string.sbc_team_a));
        String b = clean(teamB, context.getString(R.string.sbc_team_b));
        if ("corner".equals(scoreboardStyle)) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(8 * d, 6 * d, 8 * d, 6 * d);
            card.setBackground(rounded(Color.argb(230, 20, 22, 25), 10, 1, Color.argb(100,255,255,255)));
            card.addView(teamRow(a, "0", teamAColor));
            card.addView(teamRow(b, "0", teamBColor));
            TextView t = text(context.getString(R.string.sbc_period_short, 1) + "   00:00", 12f, Color.WHITE, true);
            t.setGravity(Gravity.CENTER);
            card.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 28 * d));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    Math.min(260 * d, (int) (context.getResources().getDisplayMetrics().widthPixels * 0.70f)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            previewHolder.addView(card, cardLp);
        } else if ("minimal".equals(scoreboardStyle)) {
            TextView minimal = text(a + "  0   ·   00:00 " + context.getString(R.string.sbc_period_short, 1) + "   ·   0  " + b,
                    14f, Color.WHITE, true);
            minimal.setGravity(Gravity.CENTER);
            minimal.setPadding(12 * d, 8 * d, 12 * d, 8 * d);
            minimal.setBackground(rounded(Color.argb(120, 0, 0, 0), 12, 0, Color.TRANSPARENT));
            previewHolder.addView(minimal, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 44 * d));
        } else {
            LinearLayout hud = new LinearLayout(context);
            hud.setOrientation(LinearLayout.HORIZONTAL);
            hud.setGravity(Gravity.CENTER);
            hud.setPadding(6 * d, 4 * d, 6 * d, 4 * d);
            hud.setBackground(rounded(Color.argb(230, 27, 30, 35), 12, 1,
                    Color.argb(100, 255, 255, 255)));
            TextView av = previewTeam(a);
            TextView as = previewScore("0", teamAColor);
            TextView tm = text("00:00", 13f, Color.WHITE, true);
            tm.setGravity(Gravity.CENTER);
            TextView pr = text(context.getString(R.string.sbc_period_short, 1), 12f, Color.rgb(220, 231, 240), true);
            pr.setGravity(Gravity.CENTER);
            TextView bs = previewScore("0", teamBColor);
            TextView bv = previewTeam(b);
            hud.addView(av, new LinearLayout.LayoutParams(0, 40 * d, 1f));
            hud.addView(as, new LinearLayout.LayoutParams(46 * d, 36 * d));
            hud.addView(tm, new LinearLayout.LayoutParams(70 * d, 36 * d));
            hud.addView(pr, new LinearLayout.LayoutParams(38 * d, 34 * d));
            hud.addView(bs, new LinearLayout.LayoutParams(46 * d, 36 * d));
            hud.addView(bv, new LinearLayout.LayoutParams(0, 40 * d, 1f));
            previewHolder.addView(hud, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 48 * d));
        }
    }
    private View teamRow(String name, String score, int color) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(context);
        dot.setBackground(rounded(color, 10, 0, Color.TRANSPARENT));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(10 * d, 10 * d);
        dp.setMargins(0, 0, 6 * d, 0);
        row.addView(dot, dp);
        TextView n = text(name, 13f, Color.WHITE, true);
        row.addView(n, new LinearLayout.LayoutParams(0, 32 * d, 1f));
        TextView sc = previewScore(score, color);
        row.addView(sc, new LinearLayout.LayoutParams(42 * d, 30 * d));
        return row;
    }
    private TextView previewTeam(String value) {
        TextView v = text(value, 12f, Color.WHITE, true);
        v.setGravity(Gravity.CENTER);
        v.setMaxLines(1);
        return v;
    }

    private TextView previewScore(String value, int color) {
        TextView v = text(value, 18f, Color.WHITE, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(rounded(withAlpha(color, 190), 9, 1, withAlpha(Color.WHITE, 80)));
        return v;
    }
    private Button colorButton(boolean first) {
        Button b = new Button(context);
        int color = first ? teamAColor : teamBColor;
        b.setAllCaps(false);
        b.setText("");
        b.setContentDescription(first
                ? context.getString(R.string.sbc_choose_team_a_color)
                : context.getString(R.string.sbc_choose_team_b_color));
        b.setBackgroundTintList(ColorStateList.valueOf(color));
        b.setOnClickListener(v -> showColorChooser(first));
        return b;
    }
    private void showColorChooser(boolean first) {
        final int current = first ? teamAColor : teamBColor;
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12 * d, 8 * d, 12 * d, 8 * d);

        final AlertDialog[] holder = new AlertDialog[1];
        final int columns = 4;
        for (int rowStart = 0; rowStart < TEAM_COLORS.length; rowStart += columns) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            for (int col = 0; col < columns; col++) {
                int index = rowStart + col;
                if (index >= TEAM_COLORS.length) {
                    View spacer = new View(context);
                    row.addView(spacer, new LinearLayout.LayoutParams(0, 54 * d, 1f));
                    continue;
                }
                final int color = TEAM_COLORS[index];
                TextView swatch = new TextView(context);
                swatch.setGravity(Gravity.CENTER);
                swatch.setText(color == current ? "✓" : "");
                swatch.setTextSize(22f);
                swatch.setTypeface(null, Typeface.BOLD);
                swatch.setTextColor(contrastTextColor(color));
                swatch.setContentDescription(String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF));
                int stroke = color == current ? 3 : 1;
                int strokeColor = color == current
                        ? Color.rgb(25, 118, 210)
                        : Color.rgb(165, 170, 175);
                swatch.setBackground(rounded(color, 12, stroke, strokeColor));
                swatch.setOnClickListener(v -> {
                    if (first) teamAColor = color;
                    else teamBColor = color;
                    if (holder[0] != null) holder[0].dismiss();
                    renderStep();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 54 * d, 1f);
                lp.setMargins(5 * d, 5 * d, 5 * d, 5 * d);
                row.addView(swatch, lp);
            }
            root.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        holder[0] = new AlertDialog.Builder(context)
                .setTitle(first ? context.getString(R.string.sbc_team_a_color) : context.getString(R.string.sbc_team_b_color))
                .setView(root)
                .setNegativeButton(context.getString(R.string.sbc_cancel), null)
                .create();
        holder[0].show();
    }
    private int contrastTextColor(int color) {
        double luminance = (0.299 * Color.red(color))
                + (0.587 * Color.green(color))
                + (0.114 * Color.blue(color));
        return luminance >= 170.0 ? Color.BLACK : Color.WHITE;
    }
    private String[] styleLabels() {
        return new String[] {
                context.getString(R.string.sbc_style_compact_tv),
                context.getString(R.string.sbc_style_corner),
                context.getString(R.string.sbc_style_minimal)
        };
    }

    private int styleIndex(String value) {
        for (int i = 0; i < STYLE_VALUES.length; i++) {
            if (STYLE_VALUES[i].equals(value)) return i;
        }
        return 0;
    }
    private void configureNumericField(EditText e) {
        if (e == null) return;
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setFocusable(true);
        e.setFocusableInTouchMode(true);
        e.setClickable(true);
        e.setCursorVisible(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            e.setShowSoftInputOnFocus(true);
        }
        View.OnClickListener openKeyboard = v -> {
            e.requestFocus();
            if (e.getText() != null) e.setSelection(e.getText().length());
            e.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager)
                        context.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 80L);
        };
        e.setOnClickListener(openKeyboard);
        e.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) openKeyboard.onClick(v);
        });
    }
    private int parseInt(EditText field, int fallback, int min, int max) {
        try {
            int v = Integer.parseInt(field == null ? "" : field.getText().toString().trim());
            return Math.max(min, Math.min(max, v));
        } catch (Exception ignored) {
            return Math.max(min, Math.min(max, fallback));
        }
    }
    private String shortName(String full) {
        String s = clean(full, context.getString(R.string.sbc_team_generic)).toUpperCase(Locale.ROOT);
        return s.length() > 18 ? s.substring(0, 18) : s;
    }

    private String clean(String s, String fallback) {
        s = s == null ? "" : s.trim();
        return s.isEmpty() ? fallback : s;
    }
    private TextView sectionTitle(String value) {
        TextView v = text(value, 18f, Color.rgb(20, 20, 20), true);
        v.setPadding(2 * d, 6 * d, 2 * d, 4 * d);
        return v;
    }

    private TextView label(String value) {
        TextView v = text(value, 12.5f, Color.rgb(70, 70, 70), false);
        v.setPadding(2 * d, 7 * d, 2 * d, 3 * d);
        return v;
    }
    private TextView info(String value) {
        TextView v = text(value, 11.5f, Color.rgb(100, 100, 100), false);
        v.setPadding(2 * d, 2 * d, 2 * d, 7 * d);
        return v;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView v = new TextView(context);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(null, Typeface.BOLD);
        return v;
    }
    private EditText field(String hint, String value) {
        EditText e = new EditText(context);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setSingleLine(true);
        e.setTextColor(Color.rgb(24, 24, 24));
        e.setHintTextColor(Color.rgb(110, 110, 110));
        e.setTextSize(14f);
        e.setPadding(12 * d, 0, 12 * d, 0);
        e.setBackground(rounded(Color.WHITE, 10, 1, Color.rgb(185, 190, 195)));
        e.setBackgroundTintList(null);
        return e;
    }
    private Button navButton(String value, int color) {
        Button b = new Button(context);
        b.setText(value);
        b.setTextColor(Color.WHITE);
        b.setTextSize(11f);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setBackgroundTintList(ColorStateList.valueOf(color));
        return b;
    }
    private LinearLayout.LayoutParams lpMatch(int heightDp) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heightDp * d);
    }

    private LinearLayout.LayoutParams lpWrap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 4 * d, 0, 4 * d);
        return lp;
    }
    private GradientDrawable rounded(int fill, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusDp * d);
        if (strokeDp > 0) g.setStroke(Math.max(1, strokeDp * d), strokeColor);
        return g;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
