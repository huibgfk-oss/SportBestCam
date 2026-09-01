package com.fadcam.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fadcam.FLog;
import com.fadcam.R;
import com.google.android.material.button.MaterialButton;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Safe SportBestCam control-rail polish.
 *
 * Important: this class never removes/re-adds or reorders control views. The previous
 * Samsung-style experiment changed the child hierarchy at runtime and could leave the
 * proxy controls underneath another overlay / without reliable touch dispatch.
 *
 * This version only applies geometry/appearance and explicitly keeps the control rail
 * above passive preview overlays. Existing IDs, listeners, enabled states and camera
 * behaviour remain untouched.
 */
public final class SportControlsUiPolish {
    private static final String TAG = "SportControlsUiPolish";
    private static final Map<Activity, ViewTreeObserver.OnGlobalLayoutListener> INSTALLED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private SportControlsUiPolish() { }

    public static void install(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        apply(activity);
        synchronized (INSTALLED) {
            if (INSTALLED.containsKey(activity)) return;
            View decor = activity.getWindow().getDecorView();
            ViewTreeObserver.OnGlobalLayoutListener listener = () -> apply(activity);
            try {
                decor.getViewTreeObserver().addOnGlobalLayoutListener(listener);
                INSTALLED.put(activity, listener);
            } catch (Throwable t) {
                FLog.w(TAG, "Unable to attach UI listener", t);
            }
        }
    }

    private static void apply(Activity activity) {
        try {
            View railView = activity.findViewById(R.id.layoutControls);
            if (railView instanceof LinearLayout) {
                LinearLayout rail = (LinearLayout) railView;
                enforceRailGeometry(activity, rail);
                enforceSquareButtons(activity, rail);
                enforceIconOnlyLabels(rail);

                // Keep the real controls above preview/calibration decoration layers.
                // Do not make the rail itself clickable: blank rail space should not
                // intercept touches intended for the preview.
                rail.setClickable(false);
                rail.setFocusable(false);
                rail.bringToFront();
                if (android.os.Build.VERSION.SDK_INT >= 21) rail.setElevation(dp(activity, 12));
            }

            View hudView = activity.findViewById(R.id.hb_overlay);
            if (hudView instanceof LinearLayout) {
                centerScoreboard(activity, (LinearLayout) hudView);
            }
        } catch (Throwable t) {
            FLog.w(TAG, "Sport UI polish skipped", t);
        }
    }

    private static void enforceRailGeometry(Context context, LinearLayout rail) {
        boolean landscape = isLandscape(context);
        int wantedHeight = dp(context, landscape ? 50 : 46);
        ViewGroup.LayoutParams params = rail.getLayoutParams();
        if (params != null && params.height != wantedHeight) {
            params.height = wantedHeight;
            rail.setLayoutParams(params);
        }
        rail.setGravity(Gravity.CENTER);
        rail.setOrientation(LinearLayout.HORIZONTAL);
        rail.setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3));
        rail.setClipChildren(false);
        rail.setClipToPadding(false);
    }

    private static void enforceSquareButtons(Context context, LinearLayout rail) {
        boolean landscape = isLandscape(context);
        styleButton(context, rail, R.id.hb_timer_toggle, landscape, true);
        styleButton(context, rail, R.id.hb_settings, landscape, true);
        styleButton(context, rail, R.id.hb_mute_proxy, landscape, true);
        styleTorch(context, rail, landscape);
        styleButton(context, rail, R.id.hb_screenshot_proxy, landscape, true);
        styleButton(context, rail, R.id.hb_fullscreen_proxy, landscape, true);
        styleButton(context, rail, R.id.buttonStartStop, landscape, false);
        styleButton(context, rail, R.id.buttonPauseResume, landscape, false);
        styleButton(context, rail, R.id.hb_camera_tools, landscape, false);
    }

    private static void styleButton(Context context, LinearLayout rail, int id,
                                    boolean landscape, boolean slightlyLargerIcon) {
        View view = rail.findViewById(id);
        if (!(view instanceof MaterialButton) || view.getParent() != rail) return;
        MaterialButton button = (MaterialButton) view;
        int size = dp(context, landscape ? 36 : 32);
        int gap = dp(context, 1);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(gap, 0, gap, 0);
        button.setLayoutParams(lp);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(0, 0, 0, 0);
        button.setGravity(Gravity.CENTER);
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setIconPadding(0);
        int iconDp = landscape ? (slightlyLargerIcon ? 22 : 21) : (slightlyLargerIcon ? 20 : 19);
        button.setIconSize(dp(context, iconDp));
        button.setCornerRadius(dp(context, landscape ? 9 : 8));
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setMaxLines(1);
        button.setText("");
        button.setClickable(true);
        button.setFocusable(true);
    }

    private static void styleTorch(Context context, LinearLayout rail, boolean landscape) {
        View wrapperView = rail.findViewById(R.id.torch_btn_wrapper);
        if (!(wrapperView instanceof FrameLayout) || wrapperView.getParent() != rail) return;
        FrameLayout wrapper = (FrameLayout) wrapperView;
        int size = dp(context, landscape ? 36 : 32);
        int gap = dp(context, 1);
        LinearLayout.LayoutParams wrapperLp = new LinearLayout.LayoutParams(size, size);
        wrapperLp.setMargins(gap, 0, gap, 0);
        wrapper.setLayoutParams(wrapperLp);
        wrapper.setClickable(false);

        View buttonView = wrapper.findViewById(R.id.buttonTorchSwitch);
        if (buttonView instanceof MaterialButton) {
            MaterialButton button = (MaterialButton) buttonView;
            FrameLayout.LayoutParams buttonLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            buttonLp.gravity = Gravity.CENTER;
            button.setLayoutParams(buttonLp);
            button.setMinWidth(0);
            button.setMinHeight(0);
            button.setInsetTop(0);
            button.setInsetBottom(0);
            button.setPadding(0, 0, 0, 0);
            button.setGravity(Gravity.CENTER);
            button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            button.setIconPadding(0);
            button.setIconSize(dp(context, landscape ? 22 : 20));
            button.setCornerRadius(dp(context, landscape ? 9 : 8));
            button.setAllCaps(false);
            button.setText("");
            button.setSingleLine(true);
            button.setMaxLines(1);
            button.setClickable(true);
            button.setFocusable(true);
        }

        View statusView = wrapper.findViewById(R.id.torch_status_label);
        if (statusView instanceof TextView && statusView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            TextView status = (TextView) statusView;
            status.setTextSize(4.5f);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) status.getLayoutParams();
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.bottomMargin = dp(context, 1);
            lp.setMarginEnd(dp(context, 2));
            status.setLayoutParams(lp);
            status.setClickable(false);
            status.setFocusable(false);
        }
    }

    private static void enforceIconOnlyLabels(LinearLayout rail) {
        int[] ids = {
                R.id.hb_timer_toggle,
                R.id.hb_settings,
                R.id.hb_mute_proxy,
                R.id.hb_screenshot_proxy,
                R.id.hb_fullscreen_proxy,
                R.id.buttonStartStop,
                R.id.buttonPauseResume,
                R.id.hb_camera_tools
        };
        for (int id : ids) {
            View v = rail.findViewById(id);
            if (v instanceof MaterialButton) {
                MaterialButton button = (MaterialButton) v;
                if (button.getText() != null && button.getText().length() > 0) button.setText("");
            }
        }
        View torch = rail.findViewById(R.id.buttonTorchSwitch);
        if (torch instanceof MaterialButton) ((MaterialButton) torch).setText("");
    }

    private static void centerScoreboard(Context context, LinearLayout scoreboard) {
        View parent = (View) scoreboard.getParent();
        if (parent == null || !(scoreboard.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        int parentWidth = parent.getWidth();
        if (parentWidth <= 0) return;
        boolean landscape = isLandscape(context);
        int sideSpace = dp(context, landscape ? 64 : 24);
        int maxWidth = dp(context, landscape ? 620 : 360);
        int minWidth = dp(context, landscape ? 320 : 240);
        int available = Math.max(dp(context, 180), parentWidth - sideSpace);
        int desired = Math.min(parentWidth, Math.max(minWidth, Math.min(maxWidth, available)));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) scoreboard.getLayoutParams();
        boolean changed = false;
        if (lp.width != desired) {
            lp.width = desired;
            changed = true;
        }
        int wantedGravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        if (lp.gravity != wantedGravity) {
            lp.gravity = wantedGravity;
            changed = true;
        }
        if (scoreboard.getTranslationX() != 0f) {
            scoreboard.setTranslationX(0f);
            changed = true;
        }
        scoreboard.setGravity(Gravity.CENTER);
        scoreboard.setClickable(false);
        if (changed) {
            scoreboard.setLayoutParams(lp);
            scoreboard.requestLayout();
        }
    }

    private static boolean isLandscape(Context context) {
        return context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
