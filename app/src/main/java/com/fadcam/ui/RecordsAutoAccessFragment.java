package com.fadcam.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.data.BackgroundRecordsDiscovery;

/**
 * Headless one-shot SAF permission helper for Records.
 *
 * When Records is opened and no usable persisted tree permission exists, this
 * launches the normal Android folder picker once. The default initial location is
 * Movies/SportBestCam; if Settings already contains a custom storage URI, that URI
 * is offered instead. A cancellation is remembered for that exact target so the
 * picker does not nag on every Records visit. Users can always grant/change access
 * later from Records Options or Settings.
 */
public final class RecordsAutoAccessFragment extends Fragment {
    private static final String TAG = "RecordsAutoAccess";
    private static final String FRAGMENT_TAG = "records_auto_access_fragment";
    private static final String PREF_DEFAULT_TREE_URI = "records_default_sportbestcam_tree_uri";
    private static final String PREF_SEARCH_TREE_URI = "records_search_tree_uri";
    private static final String PREF_PROMPTED_FOR = "records_auto_access_prompted_for";

    private boolean launched = false;

    private final ActivityResultLauncher<Uri> folderPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), this::onFolderPicked);

    public static void ensureAccessOrPrompt(@NonNull FragmentActivity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (hasUsableAccess(activity)) return;

        String targetToken = currentTargetToken(activity);
        String promptedFor = activity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_PROMPTED_FOR, null);
        if (targetToken.equals(promptedFor)) return;

        FragmentManager fm = activity.getSupportFragmentManager();
        if (fm.findFragmentByTag(FRAGMENT_TAG) != null) return;
        try {
            fm.beginTransaction()
                    .add(new RecordsAutoAccessFragment(), FRAGMENT_TAG)
                    .commitAllowingStateLoss();
        } catch (Throwable t) {
            FLog.w(TAG, "Unable to attach automatic Records access helper", t);
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (launched) return;
        launched = true;
        folderPicker.launch(getInitialUri(requireContext()));
    }

    private void onFolderPicked(@Nullable Uri uri) {
        if (!isAdded()) return;
        Context context = requireContext();
        String targetToken = currentTargetToken(context);
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);

        // Ask automatically only once for this destination, regardless of whether
        // the user grants or cancels. Records Options can still reopen the picker.
        prefs.edit().putString(PREF_PROMPTED_FOR, targetToken).apply();

        if (uri != null) {
            try {
                context.getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                android.content.SharedPreferences.Editor editor = prefs.edit()
                        .putString(PREF_DEFAULT_TREE_URI, uri.toString());
                if (!prefs.contains(PREF_SEARCH_TREE_URI)) {
                    editor.putString(PREF_SEARCH_TREE_URI, uri.toString());
                }
                editor.apply();

                // Permission is active now: run the same recursive discovery/Add-all
                // workflow immediately and refresh Records when it finishes.
                BackgroundRecordsDiscovery.schedule(context, 120L, "records_saf_permission_granted");
            } catch (SecurityException e) {
                FLog.w(TAG, "Persisting Records SAF permission failed", e);
            }
        }

        removeSelf();
    }

    private void removeSelf() {
        if (!isAdded()) return;
        try {
            getParentFragmentManager().beginTransaction()
                    .remove(this)
                    .commitAllowingStateLoss();
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasUsableAccess(@NonNull Context context) {
        SharedPreferencesManager settings = SharedPreferencesManager.getInstance(context);
        String custom = settings.getCustomStorageUri();
        if (custom != null && !custom.trim().isEmpty()) {
            try {
                if (hasPersistedReadPermission(context, Uri.parse(custom))) return true;
            } catch (Throwable ignored) {
            }
        }

        String saved = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_DEFAULT_TREE_URI, null);
        if (saved != null && !saved.trim().isEmpty()) {
            try {
                return hasPersistedReadPermission(context, Uri.parse(saved));
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean hasPersistedReadPermission(@NonNull Context context, @NonNull Uri uri) {
        try {
            for (android.content.UriPermission permission
                    : context.getContentResolver().getPersistedUriPermissions()) {
                if (uri.equals(permission.getUri()) && permission.isReadPermission()) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @NonNull
    private static String currentTargetToken(@NonNull Context context) {
        String custom = SharedPreferencesManager.getInstance(context).getCustomStorageUri();
        if (custom != null && !custom.trim().isEmpty()) return "custom:" + custom;
        return "default:Movies/SportBestCam";
    }

    @Nullable
    private static Uri getInitialUri(@NonNull Context context) {
        String custom = SharedPreferencesManager.getInstance(context).getCustomStorageUri();
        if (custom != null && !custom.trim().isEmpty()) {
            try {
                return Uri.parse(custom);
            } catch (Throwable ignored) {
            }
        }

        String saved = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_DEFAULT_TREE_URI, null);
        if (saved != null && !saved.trim().isEmpty()) {
            try {
                return Uri.parse(saved);
            } catch (Throwable ignored) {
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                return DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Movies/SportBestCam");
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
