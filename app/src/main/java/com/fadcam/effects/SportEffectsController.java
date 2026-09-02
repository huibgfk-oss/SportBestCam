package com.fadcam.effects;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class SportEffectsController {
    private static final String HOST_TAG = "sportbestcam_live_fx_host";
    private static final String PREFS = "sportbestcam_live_fx";

    private final Activity activity;
    private final FrameLayout root;
    private FrameLayout host;
    private SportEffectsOverlayView overlay;
    private LinearLayout drawer;

    private SportEffectsController(Activity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
    }

    public static void attach(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;

        FrameLayout root = findRoot(activity, (ViewGroup) content);
        if (root == null) return;

        if (root.findViewWithTag(HOST_TAG) != null) return;

        SportEffectsController controller = new SportEffectsController(activity, root);
        controller.install();
    }

    public static void detach(Activity activity) {
        if (activity == null) return;
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;

        FrameLayout root = findRoot(activity, (ViewGroup) content);
        if (root == null) return;

        View old = root.findViewWithTag(HOST_TAG);
        if (old != null) root.removeView(old);
    }

    private static FrameLayout findRoot(Activity activity, ViewGroup fallback) {
        int id = activity.getResources().getIdentifier(
                "fullscreenRoot",
                "id",
                activity.getPackageName()
        );
        if (id != 0) {
            View found = activity.findViewById(id);
            if (found instanceof FrameLayout) return (FrameLayout) found;
        }

        if (fallback instanceof FrameLayout) return (FrameLayout) fallback;

        for (int i = 0; i < fallback.getChildCount(); i++) {
            View child = fallback.getChildAt(i);
            if (child instanceof FrameLayout) return (FrameLayout) child;
        }
        return null;
    }

    private void install() {
        host = new FrameLayout(activity);
        host.setTag(HOST_TAG);
        host.setClickable(false);
        host.setFocusable(false);

        FrameLayout.LayoutParams hostParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        root.addView(host, hostParams);

        overlay = new SportEffectsOverlayView(activity);
        host.addView(
                overlay,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );

        addQuickBar();
        addDrawer();
    }

    private void addQuickBar() {
        LinearLayout quick = new LinearLayout(activity);
        quick.setOrientation(LinearLayout.VERTICAL);
        quick.setGravity(Gravity.CENTER);
        quick.setPadding(dp(3), dp(4), dp(3), dp(4));
        quick.setBackground(roundRect(Color.argb(138, 6, 12, 22), dp(18)));

        addQuickButton(quick, "♥", SportEffectsState.Effect.HEARTS);
        addQuickButton(quick, "B", SportEffectsState.Effect.BOOM);
        addQuickButton(quick, "⚡", SportEffectsState.Effect.THUNDER);
        addQuickButton(quick, "G", SportEffectsState.Effect.GOAL_MATCH);
        addQuickButton(quick, "📣", SportEffectsState.Effect.GOAL_HORN);

        TextView more = smallButton("FX");
        more.setContentDescription("Open Live Effects");
        more.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            drawer.setVisibility(
                    drawer.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE
            );
        });
        quick.addView(more);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(48),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL
        );
        params.rightMargin = dp(5);
        host.addView(quick, params);
    }

    private void addQuickButton(
            LinearLayout quick,
            String label,
            SportEffectsState.Effect effect
    ) {
        TextView button = smallButton(label);
        button.setContentDescription(effect.label);
        button.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            trigger(effect);
        });
        quick.addView(button);
    }

    private void addDrawer() {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setBackground(roundRect(Color.argb(230, 8, 15, 28), dp(18)));
        scroll.setPadding(dp(8), dp(8), dp(8), dp(8));

        drawer = new LinearLayout(activity);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setPadding(dp(4), dp(4), dp(4), dp(8));
        scroll.addView(drawer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = label("LIVE FX", 15, true);
        title.setGravity(Gravity.CENTER);
        drawer.addView(title, rowParams());

        TextView hint = label("Visual + audio effects burned into recording", 10, false);
        hint.setTextColor(Color.rgb(190, 203, 220));
        hint.setGravity(Gravity.CENTER);
        drawer.addView(hint, rowParams());

        addSection("VISUAL + SFX");
        addEffect("♥  Raining Hearts", SportEffectsState.Effect.HEARTS);
        addEffect("BOOM  Explosion", SportEffectsState.Effect.BOOM);
        addEffect("⚡  Raining Thunder", SportEffectsState.Effect.THUNDER);
        addEffect("🏆  Goal of the Match", SportEffectsState.Effect.GOAL_MATCH);
        addEffect("Subscribe to Channel", SportEffectsState.Effect.SUBSCRIBE);
        addEffect("+1  Give a Like", SportEffectsState.Effect.LIKE);
        addEffect("Confetti", SportEffectsState.Effect.CONFETTI);
        addEffect("Raining Stars", SportEffectsState.Effect.STARS);
        addEffect("Fire", SportEffectsState.Effect.FIRE);

        addSection("VISUAL");
        addEffect("Slide Left", SportEffectsState.Effect.SLIDE_LEFT);
        addEffect("Slide Right", SportEffectsState.Effect.SLIDE_RIGHT);

        addSection("AUDIO");
        addEffect("📣  Goal Horn", SportEffectsState.Effect.GOAL_HORN);
        addEffect("Applause", SportEffectsState.Effect.APPLAUSE);
        addEffect("Referee Whistle", SportEffectsState.Effect.WHISTLE);
        addEffect("Stadium Cheer", SportEffectsState.Effect.STADIUM_CHEER);
        addEffect("Thunder Sound", SportEffectsState.Effect.THUNDER_SOUND);

        TextView clear = actionButton("CLEAR VISUAL FX");
        clear.setOnClickListener(v -> {
            SportEffectsState.clearVisual();
            overlay.kick();
        });
        drawer.addView(clear, rowParams());

        TextView close = actionButton("CLOSE");
        close.setOnClickListener(v -> drawer.setVisibility(View.GONE));
        drawer.addView(close, rowParams());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(250),
                Math.min(dp(540), activity.getResources().getDisplayMetrics().heightPixels - dp(32)),
                Gravity.END | Gravity.CENTER_VERTICAL
        );
        params.rightMargin = dp(58);
        drawer.setVisibility(View.GONE);
        host.addView(scroll, params);
    }

    private void addSection(String title) {
        TextView t = label(title, 10, true);
        t.setTextColor(Color.rgb(125, 211, 252));
        t.setPadding(dp(8), dp(12), dp(8), dp(4));
        drawer.addView(t, rowParams());
    }

    private void addEffect(String label, SportEffectsState.Effect effect) {
        TextView button = actionButton(label);
        button.setContentDescription(effect.label);
        button.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            trigger(effect);
        });
        drawer.addView(button, rowParams());
    }

    private void trigger(SportEffectsState.Effect effect) {
        SportEffectsState.trigger(effect);
        overlay.kick();

        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .edit()
                .putString("last_effect", effect.name())
                .apply();
    }

    private TextView smallButton(String text) {
        TextView v = label(text, 14, true);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(Color.WHITE);
        v.setBackground(roundRect(Color.argb(155, 24, 35, 52), dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(2);
        v.setLayoutParams(lp);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            v.setElevation(dp(2));
        }
        return v;
    }

    private TextView actionButton(String text) {
        TextView v = label(text, 12, true);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setTextColor(Color.WHITE);
        v.setPadding(dp(12), 0, dp(12), 0);
        v.setBackground(roundRect(Color.argb(160, 30, 41, 59), dp(11)));
        return v;
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView v = new TextView(activity);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(Color.WHITE);
        v.setSingleLine(false);
        v.setTypeface(
                android.graphics.Typeface.create(
                        android.graphics.Typeface.SANS_SERIF,
                        bold
                                ? android.graphics.Typeface.BOLD
                                : android.graphics.Typeface.NORMAL
                )
        );
        return v;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
        );
        lp.topMargin = dp(3);
        lp.bottomMargin = dp(3);
        return lp;
    }

    private GradientDrawable roundRect(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
