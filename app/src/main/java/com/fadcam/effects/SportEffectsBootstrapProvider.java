package com.fadcam.effects;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Adds Live FX to FullscreenPreviewActivity without changing the very large
 * activity class. This provider is registered in the debug source set.
 */
public final class SportEffectsBootstrapProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (!(context instanceof Application)) return true;

        Application app = (Application) context;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                if (isFullscreenPreview(activity)) {
                    activity.getWindow().getDecorView().post(() ->
                            SportEffectsController.attach(activity)
                    );
                }
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
                if (isFullscreenPreview(activity)) {
                    SportEffectsController.detach(activity);
                }
            }

            @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}
            @Override public void onActivityStarted(@NonNull Activity a) {}
            @Override public void onActivityPaused(@NonNull Activity a) {}
            @Override public void onActivityStopped(@NonNull Activity a) {}
            @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
        });

        return true;
    }

    private boolean isFullscreenPreview(Activity activity) {
        return "com.fadcam.ui.FullscreenPreviewActivity".equals(
                activity.getClass().getName()
        );
    }

    @Nullable
    @Override
    public Cursor query(
            @NonNull Uri uri,
            @Nullable String[] projection,
            @Nullable String selection,
            @Nullable String[] selectionArgs,
            @Nullable String sortOrder
    ) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(
            @NonNull Uri uri,
            @Nullable String selection,
            @Nullable String[] selectionArgs
    ) {
        return 0;
    }

    @Override
    public int update(
            @NonNull Uri uri,
            @Nullable ContentValues values,
            @Nullable String selection,
            @Nullable String[] selectionArgs
    ) {
        return 0;
    }
}
