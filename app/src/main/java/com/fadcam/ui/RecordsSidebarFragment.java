package com.fadcam.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.Bundle;
import android.os.Build;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.data.FastFileScanner;
import com.fadcam.data.VideoIndexDatabase;
import com.fadcam.data.VideoIndexRepository;
import com.fadcam.data.entity.VideoIndexEntity;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.sidesheet.SideSheetDialog;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RecordsSidebarFragment
 * Side overlay with settings-style grouped rows for Records options.
 */
public class RecordsSidebarFragment extends DialogFragment {

    private static final String ARG_SELECTED_SORT_ID = "selected_sort_id";
    private static final String PREF_RECORDS_SEARCH_TREE_URI = "records_search_tree_uri";
    private static final String PREF_DEFAULT_SPORTBESTCAM_TREE_URI = "records_default_sportbestcam_tree_uri";
    private static final String PREF_MANUAL_ADDED_URIS = "records_manual_added_uris";
    private String resultKey = "records_sidebar_result";
    private String selectedSortId;
    private int currentGridSpan = 2;
    private volatile boolean recursiveScanRunning = false;
    @Nullable private TextView selectedSearchFolderSubtitle;
    @Nullable private TextView defaultSportBestCamAccessSubtitle;

    private final ActivityResultLauncher<Uri> searchFolderPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null || !isAdded()) return;
                try {
                    requireContext().getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // Some providers grant the tree for the current session only.
                }
                requireContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(PREF_RECORDS_SEARCH_TREE_URI, uri.toString()).apply();
                updateSelectedSearchFolderSubtitle();
            });

    private final ActivityResultLauncher<Uri> defaultSportBestCamFolderPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null || !isAdded()) return;
                try {
                    requireContext().getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    requireContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString(PREF_DEFAULT_SPORTBESTCAM_TREE_URI, uri.toString()).apply();
                    updateDefaultSportBestCamAccessSubtitle();
                    com.fadcam.data.BackgroundRecordsDiscovery.schedule(
                            requireContext(), 0L, "default_folder_permission_granted");
                } catch (SecurityException e) {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.records_default_access_error_title)
                            .setMessage(R.string.records_default_access_error_message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            });

    public static RecordsSidebarFragment newInstance(String selectedSortId){
        RecordsSidebarFragment f = new RecordsSidebarFragment();
        Bundle b = new Bundle();
        b.putString(ARG_SELECTED_SORT_ID, selectedSortId);
        f.setArguments(b);
        return f;
    }

    public static RecordsSidebarFragment newInstance(String selectedSortId, int gridSpan){
        RecordsSidebarFragment f = new RecordsSidebarFragment();
        Bundle b = new Bundle();
        b.putString(ARG_SELECTED_SORT_ID, selectedSortId);
        b.putInt("grid_span", gridSpan);
        f.setArguments(b);
        return f;
    }

    public void setResultKey(String key){ this.resultKey = key; }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        SideSheetDialog dialog = new SideSheetDialog(requireContext());
        if (dialog.getWindow() != null) {
            android.view.Window window = dialog.getWindow();
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            android.view.View decor = window.getDecorView();
            if (decor instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) decor).setPadding(0, 0, 0, 0);
                decor.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            }
        }
        dialog.setOnShowListener(d -> {
            android.view.View container = dialog.findViewById(android.R.id.content);
            if (container instanceof android.view.ViewGroup) {
                clearMaterialBackgrounds((android.view.ViewGroup) container);
            }
        });
        return dialog;
    }

    private void clearMaterialBackgrounds(android.view.ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            android.view.View child = group.getChildAt(i);
            if (child.getId() != R.id.records_sidebar_root_scroll) {
                child.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                if (child instanceof android.view.ViewGroup) {
                    clearMaterialBackgrounds((android.view.ViewGroup) child);
                }
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_records_sidebar, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if(getArguments()!=null){
            selectedSortId = getArguments().getString(ARG_SELECTED_SORT_ID, "latest");
            currentGridSpan = getArguments().getInt("grid_span", 2);
        }

        ImageView closeButton = view.findViewById(R.id.records_sidebar_close_btn);
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dismiss());
        }

        View sortRow = view.findViewById(R.id.row_sort);
        TextView sortSubtitle = view.findViewById(R.id.row_sort_subtitle);
        updateSortSubtitle(sortSubtitle, selectedSortId);
        if(sortRow!=null){
            sortRow.setOnClickListener(v -> openSortPicker());
        }

        View deleteRow = view.findViewById(R.id.row_delete_all);
        if(deleteRow!=null){
            deleteRow.setOnClickListener(v -> {
                Bundle b = new Bundle();
                b.putString("action", "delete_all");
                getParentFragmentManager().setFragmentResult(resultKey, b);
                dismiss();
            });
        }

        View viewModeRow = view.findViewById(R.id.row_view_mode);
        TextView viewModeSub = view.findViewById(R.id.row_view_mode_subtitle);
        android.widget.ImageView viewModeIcon = view.findViewById(R.id.row_view_mode_icon);
        if(viewModeSub!=null){ viewModeSub.setText(getGridSpanLabel(currentGridSpan)); }
        if(viewModeIcon!=null){ viewModeIcon.setImageResource(R.drawable.ic_grid); }
        if(viewModeRow!=null){
            viewModeRow.setOnClickListener(v -> openViewModePicker());
        }

        View hideRow = view.findViewById(R.id.row_hide_thumbnails);
        com.fadcam.ui.AvatarToggleView hideSwitch = view.findViewById(R.id.row_hide_thumbnails_switch);
        TextView hideState = view.findViewById(R.id.row_hide_thumbnails_state);
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(requireContext());
        boolean currentHide = prefs.isHideThumbnailsEnabled();
        if (hideSwitch != null) {
            hideSwitch.setChecked(currentHide);
            if(hideState!=null){ hideState.setText(currentHide ? getString(R.string.enabled) : getString(R.string.disabled)); }
            hideSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.setHideThumbnailsEnabled(isChecked);
                if(hideState!=null){ hideState.setText(isChecked ? getString(R.string.enabled) : getString(R.string.disabled)); }
                Bundle b = new Bundle();
                b.putString("action", "hide_thumbnails_toggled");
                b.putBoolean("hide_thumbnails", isChecked);
                getParentFragmentManager().setFragmentResult(resultKey, b);
            });
        }
        if (hideRow != null) {
            hideRow.setOnClickListener(v -> {
                if (hideSwitch != null) hideSwitch.performClick();
            });
        }

        installRecursiveSearchGroup(view);
    }

    /**
     * Adds the manual deep-scan action directly into the current Records Options side sheet.
     * It is injected before the destructive Delete group so the base sidebar layout remains
     * compatible with upstream changes.
     */
    private void installRecursiveSearchGroup(@NonNull View rootView) {
        ViewGroup sidebarRoot = rootView.findViewById(R.id.records_sidebar_root);
        if (sidebarRoot == null) return;

        View searchGroup = LayoutInflater.from(requireContext())
                .inflate(R.layout.records_recursive_search_group, sidebarRoot, false);

        View deleteGroupTitle = rootView.findViewById(R.id.delete_group_title);
        int insertAt = deleteGroupTitle == null ? sidebarRoot.getChildCount() : sidebarRoot.indexOfChild(deleteGroupTitle);
        if (insertAt < 0) insertAt = sidebarRoot.getChildCount();
        sidebarRoot.addView(searchGroup, insertAt);

        View defaultAccessRow = searchGroup.findViewById(R.id.row_default_sportbestcam_access);
        defaultSportBestCamAccessSubtitle = searchGroup.findViewById(R.id.row_default_sportbestcam_access_subtitle);
        updateDefaultSportBestCamAccessSubtitle();
        if (defaultAccessRow != null) {
            defaultAccessRow.setOnClickListener(v ->
                    defaultSportBestCamFolderPicker.launch(getDefaultSportBestCamInitialUri()));
        }

        View chooseFolderRow = searchGroup.findViewById(R.id.row_recursive_choose_folder);
        selectedSearchFolderSubtitle = searchGroup.findViewById(R.id.row_recursive_choose_folder_subtitle);
        updateSelectedSearchFolderSubtitle();
        if (chooseFolderRow != null) {
            chooseFolderRow.setOnClickListener(v -> {
                Uri initial = getSelectedSearchTreeUri();
                searchFolderPicker.launch(initial);
            });
        }

        View recursiveRow = searchGroup.findViewById(R.id.row_recursive_search_all);
        if (recursiveRow != null) {
            recursiveRow.setOnClickListener(v -> runRecursiveSearch(recursiveRow));
        }
    }

    @Nullable
    private Uri getDefaultSportBestCamAccessTreeUri() {
        String value = requireContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_DEFAULT_SPORTBESTCAM_TREE_URI, null);
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Uri.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private Uri getDefaultSportBestCamInitialUri() {
        Uri existing = getDefaultSportBestCamAccessTreeUri();
        if (existing != null) return existing;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                return DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Movies/SportBestCam");
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void updateDefaultSportBestCamAccessSubtitle() {
        if (defaultSportBestCamAccessSubtitle == null || !isAdded()) return;
        Uri uri = getDefaultSportBestCamAccessTreeUri();
        if (uri == null) {
            defaultSportBestCamAccessSubtitle.setText(R.string.records_default_access_not_granted);
        } else {
            defaultSportBestCamAccessSubtitle.setText(R.string.records_default_access_granted);
        }
    }

    @Nullable
    private Uri getSelectedSearchTreeUri() {
        String value = requireContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_RECORDS_SEARCH_TREE_URI, null);
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Uri.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void updateSelectedSearchFolderSubtitle() {
        if (selectedSearchFolderSubtitle == null || !isAdded()) return;
        Uri uri = getSelectedSearchTreeUri();
        if (uri == null) {
            selectedSearchFolderSubtitle.setText(R.string.records_recursive_choose_folder_none);
            return;
        }
        String label = uri.toString();
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            if (docId != null && !docId.isEmpty()) {
                int colon = docId.indexOf(':');
                label = colon >= 0 && colon + 1 < docId.length() ? docId.substring(colon + 1) : docId;
                if (label == null || label.isEmpty()) label = docId;
            }
        } catch (Exception ignored) {
        }
        selectedSearchFolderSubtitle.setText(getString(R.string.records_recursive_choose_folder_selected, label));
    }

    /**
     * Performs a true full Records re-index on a worker thread. VideoIndexRepository's
     * forceFullReindex() uses FastFileScanner.scanAll(), which includes the selected SAF
     * tree, all exposed MediaStore volumes, public SportBestCam/FadCam trees and legacy
     * app-private trees. The result dialog is intentionally diagnostic and always shows
     * the number of videos/images found, including zero.
     */
    private void runRecursiveSearch(@NonNull View trigger) {
        if (recursiveScanRunning) return;
        recursiveScanRunning = true;
        trigger.setEnabled(false);

        final FragmentActivity activity = requireActivity();
        final Context appContext = activity.getApplicationContext();
        final SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(appContext);
        final Uri selectedTreeForScan = getSelectedSearchTreeUri();

        final AlertDialog progressDialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.records_recursive_search_progress_title)
                .setMessage(R.string.records_recursive_search_progress_message)
                .setCancelable(false)
                .create();
        progressDialog.show();

        final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
        scanExecutor.execute(() -> {
            long started = System.currentTimeMillis();
            try {
                VideoIndexRepository repo = VideoIndexRepository.getInstance(appContext);
                long[] beforeStats = repo.getQuickStats();
                int previouslyIndexed = beforeStats.length > 0 ? (int) beforeStats[0] : 0;

                FastFileScanner scanner = new FastFileScanner(appContext);

                // Full storage discovery is additive. Never REPLACE rows that are already
                // indexed because doing so would discard cached duration/thumbnail metadata.
                List<VideoIndexEntity> dbItems = VideoIndexDatabase.getInstance(appContext)
                        .videoIndexDao().getAllNewestFirst();
                Set<String> indexedUris = new HashSet<>();
                Set<String> indexedPhysical = new HashSet<>();
                for (VideoIndexEntity e : dbItems) {
                    if (e == null) continue;
                    if (e.uriString != null) indexedUris.add(e.uriString);
                    indexedPhysical.add(physicalKey(e));
                }

                List<VideoIndexEntity> automaticItems = scanner.scanAll(prefs);
                List<VideoIndexEntity> automaticNew = new ArrayList<>();
                for (VideoIndexEntity e : automaticItems) {
                    if (e == null || e.uriString == null) continue;
                    String key = physicalKey(e);
                    if (indexedUris.contains(e.uriString) || indexedPhysical.contains(key)) continue;
                    automaticNew.add(e);
                    indexedUris.add(e.uriString);
                    indexedPhysical.add(key);
                }
                if (!automaticNew.isEmpty()) {
                    VideoIndexDatabase.getInstance(appContext).videoIndexDao()
                            .insertOrReplaceAll(automaticNew);
                    repo.startBackgroundEnrichment(null);
                }

                // The explicitly selected diagnostics/search tree is different: files
                // found only there are presented to the user and require Add/Add all.
                List<VideoIndexEntity> selectedTreeItems = new ArrayList<>();
                if (selectedTreeForScan != null) {
                    selectedTreeItems = scanner.scanSelectedTree(selectedTreeForScan);
                }

                List<VideoIndexEntity> manualCandidates = new ArrayList<>();
                int selectedAlreadyIndexed = 0;
                for (VideoIndexEntity e : selectedTreeItems) {
                    if (e == null || e.uriString == null) continue;
                    String candidateKey = physicalKey(e);
                    if (indexedUris.contains(e.uriString) || indexedPhysical.contains(candidateKey)) {
                        selectedAlreadyIndexed++;
                    } else {
                        manualCandidates.add(e);
                        // Prevent the same physical file appearing twice in the review list
                        // when Android exposes it through more than one provider URI.
                        indexedUris.add(e.uriString);
                        indexedPhysical.add(candidateKey);
                    }
                }

                List<VideoItem> foundItems = repo.getVideos(prefs);
                int videoCount = 0;
                int imageCount = 0;
                for (VideoItem item : foundItems) {
                    if (item == null) continue;
                    if (item.mediaType == VideoItem.MediaType.IMAGE) imageCount++;
                    else videoCount++;
                }

                int totalCount = foundItems.size();
                long elapsedMs = System.currentTimeMillis() - started;

                final int resultVideos = videoCount;
                final int resultImages = imageCount;
                final int resultTotal = totalCount;
                final int resultPrevious = previouslyIndexed;
                final int resultSelectedFolder = selectedTreeItems.size();
                final int resultSelectedAlready = selectedAlreadyIndexed;
                final int resultReadyToAdd = manualCandidates.size();
                final long resultElapsed = elapsedMs;
                final List<VideoIndexEntity> candidatesForUi = new ArrayList<>(manualCandidates);

                activity.runOnUiThread(() -> {
                    if (progressDialog.isShowing()) progressDialog.dismiss();
                    recursiveScanRunning = false;
                    trigger.setEnabled(true);

                    RecordsFragment.requestRefresh();
                    if (isAdded()) {
                        for (androidx.fragment.app.Fragment fragment : getParentFragmentManager().getFragments()) {
                            if (fragment instanceof RecordsFragment) {
                                ((RecordsFragment) fragment).refreshList();
                                break;
                            }
                        }
                    }

                    String resultMessage = activity.getString(
                            R.string.records_recursive_search_result_message,
                            resultVideos,
                            resultImages,
                            resultTotal,
                            resultPrevious,
                            resultSelectedFolder,
                            resultSelectedAlready,
                            resultReadyToAdd,
                            resultElapsed);

                    dismissAllowingStateLoss();
                    MaterialAlertDialogBuilder resultBuilder = new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.records_recursive_search_result_title)
                            .setMessage(resultMessage)
                            .setNegativeButton(android.R.string.ok, null);
                    if (!candidatesForUi.isEmpty()) {
                        resultBuilder.setPositiveButton(R.string.records_recursive_review_found,
                                (d, which) -> showManualCandidatesDialog(activity, candidatesForUi));
                        resultBuilder.setNeutralButton(R.string.records_recursive_add_all,
                                (d, which) -> addManualCandidates(activity, candidatesForUi));
                    }
                    resultBuilder.show();
                });
            } catch (Exception e) {
                final String details = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                activity.runOnUiThread(() -> {
                    if (progressDialog.isShowing()) progressDialog.dismiss();
                    recursiveScanRunning = false;
                    trigger.setEnabled(true);
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.records_recursive_search_error_title)
                            .setMessage(activity.getString(R.string.records_recursive_search_error_message, details))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            } finally {
                scanExecutor.shutdown();
            }
        });
    }

    private void showManualCandidatesDialog(@NonNull FragmentActivity activity,
                                            @NonNull List<VideoIndexEntity> candidates) {
        String[] labels = new String[candidates.size()];
        boolean[] checked = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            labels[i] = formatCandidateLabel(activity, candidates.get(i));
        }

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.records_recursive_found_items_title)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.records_recursive_add_selected, (dialog, which) -> {
                    List<VideoIndexEntity> selected = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) selected.add(candidates.get(i));
                    }
                    if (!selected.isEmpty()) addManualCandidates(activity, selected);
                })
                .setNeutralButton(R.string.records_recursive_add_all,
                        (dialog, which) -> addManualCandidates(activity, candidates))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void addManualCandidates(@NonNull FragmentActivity activity,
                                     @NonNull List<VideoIndexEntity> items) {
        final List<VideoIndexEntity> requestedItems = new ArrayList<>(items);
        ExecutorService addExecutor = Executors.newSingleThreadExecutor();
        addExecutor.execute(() -> {
            try {
                Context appContext = activity.getApplicationContext();
                com.fadcam.data.dao.VideoIndexDao dao =
                        VideoIndexDatabase.getInstance(appContext).videoIndexDao();

                // Last line of defence: never insert if the exact URI OR the same
                // filename+size is already indexed.
                List<VideoIndexEntity> existingRows = dao.getAllNewestFirst();
                Set<String> existingUris = new HashSet<>();
                Set<String> existingFiles = new HashSet<>();
                for (VideoIndexEntity e : existingRows) {
                    if (e == null) continue;
                    if (e.uriString != null) existingUris.add(e.uriString);
                    existingFiles.add(physicalKey(e));
                }

                List<VideoIndexEntity> actuallyNew = new ArrayList<>();
                for (VideoIndexEntity item : requestedItems) {
                    if (item == null || item.uriString == null) continue;
                    String fileKey = physicalKey(item);
                    if (existingUris.contains(item.uriString) || existingFiles.contains(fileKey)) {
                        continue;
                    }
                    actuallyNew.add(item);
                    existingUris.add(item.uriString);
                    existingFiles.add(fileKey);
                }

                if (!actuallyNew.isEmpty()) {
                    dao.insertOrReplaceAll(actuallyNew);
                }

                // Persist only the exact files that were really added.
                android.content.SharedPreferences storagePrefs = appContext
                        .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
                Set<String> pinned = new HashSet<>();
                Set<String> existingPinned = storagePrefs.getStringSet(PREF_MANUAL_ADDED_URIS, null);
                if (existingPinned != null) pinned.addAll(existingPinned);
                for (VideoIndexEntity item : actuallyNew) {
                    if (item.uriString != null && !item.uriString.trim().isEmpty()) {
                        pinned.add(item.uriString);
                    }
                }
                storagePrefs.edit().putStringSet(PREF_MANUAL_ADDED_URIS, pinned).apply();

                if (!actuallyNew.isEmpty()) {
                    VideoIndexRepository.getInstance(appContext).startBackgroundEnrichment(null);
                }
                final int addedCount = actuallyNew.size();
                activity.runOnUiThread(() -> {
                    RecordsFragment.requestRefresh();
                    for (androidx.fragment.app.Fragment fragment : activity.getSupportFragmentManager().getFragments()) {
                        if (fragment instanceof RecordsFragment) {
                            ((RecordsFragment) fragment).refreshList();
                            break;
                        }
                    }
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.records_recursive_add_complete_title)
                            .setMessage(activity.getString(R.string.records_recursive_add_complete_message, addedCount))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            } finally {
                addExecutor.shutdown();
            }
        });
    }

    @NonNull
    private String formatCandidateLabel(@NonNull Context context, @NonNull VideoIndexEntity e) {
        String name = e.displayName == null ? context.getString(R.string.records_recursive_unknown_name) : e.displayName;
        String date = android.text.format.DateFormat.getMediumDateFormat(context)
                .format(new Date(e.lastModified));
        String time = android.text.format.DateFormat.getTimeFormat(context)
                .format(new Date(e.lastModified));
        String size = Formatter.formatFileSize(context, Math.max(0L, e.fileSize));
        String location = e.uriString == null ? "" : e.uriString;
        return name + "\n" + date + " " + time + " • " + size + "\n" + location;
    }

    @NonNull
    private String physicalKey(@NonNull VideoIndexEntity e) {
        String name = e.displayName == null ? "" : e.displayName.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return "uri|" + (e.uriString == null ? "" : e.uriString);
        }
        return "file|" + name + "|" + Math.max(0L, e.fileSize);
    }

    private void openViewModePicker(){
        final String pickerKey = "records_view_mode_picker";
        ArrayList<OptionItem> options = new ArrayList<>();
        options.add(OptionItem.withLigature("view_1", getString(R.string.records_grid_1), "view_list"));
        options.add(OptionItem.withLigature("view_2", getString(R.string.records_grid_2), "grid_view"));
        options.add(OptionItem.withLigature("view_3", getString(R.string.records_grid_3), "grid_view"));
        options.add(OptionItem.withLigature("view_4", getString(R.string.records_grid_4), "grid_view"));
        options.add(OptionItem.withLigature("view_5", getString(R.string.records_grid_5), "grid_view"));
        String selectedId = "view_" + currentGridSpan;
        getParentFragmentManager().setFragmentResultListener(pickerKey, this, (k, bundle) -> {
            String selId = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if(selId!=null){
                int span = 2;
                switch (selId) {
                    case "view_1": span = 1; break;
                    case "view_2": span = 2; break;
                    case "view_3": span = 3; break;
                    case "view_4": span = 4; break;
                    case "view_5": span = 5; break;
                }
                Bundle out = new Bundle();
                out.putString("action", "set_view_mode");
                out.putInt("grid_span", span);
                getParentFragmentManager().setFragmentResult(resultKey, out);
                dismiss();
            }
        });
        PickerBottomSheetFragment picker = PickerBottomSheetFragment.newInstance(
                getString(R.string.records_grid_option), options, selectedId, pickerKey, getString(R.string.records_grid_helper)
        );
        picker.show(getParentFragmentManager(), "RecordsViewModePicker");
    }

    private String getGridSpanLabel(int span) {
        switch (span) {
            case 1: return getString(R.string.records_grid_1);
            case 3: return getString(R.string.records_grid_3);
            case 4: return getString(R.string.records_grid_4);
            case 5: return getString(R.string.records_grid_5);
            default: return getString(R.string.records_grid_2);
        }
    }

    private void openSortPicker(){
        ArrayList<OptionItem> options = new ArrayList<>();
        options.add(OptionItem.withLigature("latest", getString(R.string.sort_latest_first), "arrow_upward"));
        options.add(OptionItem.withLigature("oldest", getString(R.string.sort_oldest_first), "arrow_downward"));
        options.add(OptionItem.withLigature("smallest", getString(R.string.sort_smallest_first), "trending_down"));
        options.add(OptionItem.withLigature("largest", getString(R.string.sort_largest_first), "trending_up"));
        final String pickerKey = "records_sort_picker";
        getParentFragmentManager().setFragmentResultListener(pickerKey, this, (k, bundle)->{
            String selId = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if(selId!=null){
                selectedSortId = selId;
                Bundle out = new Bundle();
                out.putString("action", "sort");
                out.putString("sort_id", selId);
                getParentFragmentManager().setFragmentResult(resultKey, out);
                View root = getView();
                if(root!=null){
                    TextView sub = root.findViewById(R.id.row_sort_subtitle);
                    updateSortSubtitle(sub, selId);
                }
            }
        });
        PickerBottomSheetFragment picker = PickerBottomSheetFragment.newInstance(
                getString(R.string.sort_by), options, selectedSortId, pickerKey, getString(R.string.records_sort_helper)
        );
        picker.show(getParentFragmentManager(), "RecordsSortPicker");
    }

    @Override
    public void onStart() {
        super.onStart();
        View root = getView();
        if(root != null){
            View p = (View) root.getParent();
            int guard = 0;
            while(p != null && guard < 5){
                try {
                    if(p.getBackground() != null){ p.setBackgroundColor(android.graphics.Color.TRANSPARENT); }
                } catch (Exception ignored) { }
                if(!(p.getParent() instanceof View)) break;
                p = (View) p.getParent();
                guard++;
            }
        }
    }

    private void updateSortSubtitle(TextView tv, String id){
        if(tv==null) return;
        switch (id){
            case "oldest": tv.setText(R.string.sort_oldest_first); break;
            case "smallest": tv.setText(R.string.sort_smallest_first); break;
            case "largest": tv.setText(R.string.sort_largest_first); break;
            default: tv.setText(R.string.sort_latest_first);
        }
    }

    @Override
    public int getTheme() {
        return R.style.CustomSideSheetDialogTheme;
    }
}
