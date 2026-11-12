package dev.neosync.boox.activity;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.neosync.boox.R;
import dev.neosync.boox.contentprovider.OnyxMetadataRepository;
import dev.neosync.boox.kosync.SyncSettings;
import dev.neosync.boox.library.LibraryModel;
import dev.neosync.boox.library.LibraryScope;

/**
 * Library settings (spec 5.8): which folders the home list shows (EPUB
 * books only: the types both Neo Reader and the Picco open). Each tick is saved at once; the home screen reloads when it is back.
 */
public class LibraryScopeActivity extends Activity {

    private static final String STORAGE_ROOT = "/storage/emulated/0/";

    private SyncSettings settings;
    private LinearLayout folderList;
    /** The listable book files, once loaded; null until then. */
    private List<String> bookPaths;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new SyncSettings(this);
        setContentView(R.layout.activity_library_scope);
        SystemBars.fit(this);
        findViewById(R.id.button_back).setOnClickListener(v -> finish());

        // Reading the whole Onyx library (and checking each file) is slow on
        // a big library: build the folder list off the UI thread.
        folderList = findViewById(R.id.list_folders);
        Context app = getApplicationContext();
        new Thread(() -> {
            List<String> paths = bookPaths(app);
            runOnUiThread(() -> {
                if (!isDestroyed()) {
                    bookPaths = paths;
                    showFolders();
                }
            });
        }, "NeoSync library folders").start();
    }

    /** Builds the folder list; nothing until the books are loaded. */
    private void showFolders() {
        if (bookPaths == null) {
            return;
        }
        folderList.removeAllViews();
        List<String> folders = folders(bookPaths, LibraryScope.LISTED_TYPES,
                settings.getHiddenFolders());
        if (folders.isEmpty()) {
            TextView none = new TextView(this);
            none.setText(R.string.library_scope_no_folders);
            none.setTextAppearance(R.style.NeoSync_Small);
            folderList.addView(none);
        }
        Set<String> hidden = settings.getHiddenFolders();
        for (String folder : folders) {
            CheckBox box = addRow(folderList, folderLabel(folder));
            box.setChecked(!hidden.contains(folder));
            box.setOnCheckedChangeListener((b, on) -> {
                Set<String> now = settings.getHiddenFolders();
                if (on) {
                    now.remove(folder);
                } else {
                    now.add(folder);
                }
                settings.setHiddenFolders(now);
            });
        }
    }

    /**
     * Folders to list: those with books of the listed types, plus any hidden
     * one (so it can be ticked again).
     */
    static List<String> folders(List<String> paths, Set<String> types, Set<String> hidden) {
        Set<String> all = new HashSet<>(LibraryScope.bookFolders(paths, types));
        all.addAll(hidden);
        List<String> sorted = new ArrayList<>(all);
        java.util.Collections.sort(sorted);
        return sorted;
    }

    /** The listable book files on this Boox. Off the UI thread. */
    private static List<String> bookPaths(Context app) {
        List<String> paths = new ArrayList<>();
        try {
            for (OnyxMetadataRepository.Book book : LibraryModel.listable(
                    new OnyxMetadataRepository(app).queryAllBooks(), b -> b.path,
                    path -> new File(path).exists(), Permissions.hasAllFilesAccess())) {
                paths.add(book.path);
            }
        } catch (RuntimeException ignored) {
            // No library to read: only the saved choices are shown.
        }
        return paths;
    }

    private CheckBox addRow(LinearLayout parent, String label) {
        CheckBox box = new CheckBox(this);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int height = (int) (56 * getResources().getDisplayMetrics().density);
        box.setMinHeight(height);
        box.setMinimumHeight(height);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        box.setButtonDrawable(R.drawable.check_box);
        box.setText(label);
        box.setTextAppearance(R.style.NeoSync_Body);
        box.setPadding((int) (12 * getResources().getDisplayMetrics().density), 0, 0, 0);
        parent.addView(box);
        return box;
    }

    /** "Books/Old" for /storage/emulated/0/Books/Old. */
    static String folderLabel(String folder) {
        return folder.startsWith(STORAGE_ROOT) ? folder.substring(STORAGE_ROOT.length()) : folder;
    }
}
