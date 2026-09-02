package com.fadcam.effects;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

public final class SportEffectsOverlayView extends View {
    public SportEffectsOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        SportEffectsFrameRenderer.draw(canvas);

        if (SportEffectsState.isVisualActive()) {
            postInvalidateDelayed(33L);
        }
    }

    public void kick() {
        invalidate();
        postInvalidateOnAnimation();
    }
}
