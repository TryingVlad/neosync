package dev.neosync.boox.activity;

import android.app.Activity;
import android.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.neosync.boox.R;
import dev.neosync.boox.kosync.SyncSettings;
import dev.neosync.boox.transfer.PiccoClient;

/**
 * Asks which Picco folder new books go to (suggesting "books") and
 * remembers the choice. Shared by the first send from home and Settings'
 * "Folder for new books · Change". On the UI thread.
 */
final class PiccoFolderChooser {

    private PiccoFolderChooser() {
    }

    /** then gets the chosen folder; onStop gets null when the user cancels. */
    static void ask(Activity activity, List<String> folders, Consumer<String> then,
                    Consumer<String> onStop) {
        if (MainActivity.gone(activity)) {
            onStop.accept(null); // rotated or closed while listing: release the caller
            return;
        }
        final List<String> choices = new ArrayList<>();
        choices.add("/");
        choices.addAll(folders);
        String[] labels = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            labels[i] = MainActivity.piccoFolderLabel(activity, choices.get(i));
        }
        int current = choices.indexOf(new SyncSettings(activity).getPiccoFolder());
        final int[] picked = {current >= 0 ? current
                : choices.indexOf(PiccoClient.suggestedFolder(folders))};
        new AlertDialog.Builder(activity)
                .setTitle(R.string.picco_folder_title)
                .setSingleChoiceItems(labels, picked[0], (d, which) -> picked[0] = which)
                .setPositiveButton(R.string.picco_folder_ok, (d, w) -> {
                    String folder = choices.get(Math.max(0, picked[0]));
                    new SyncSettings(activity).setPiccoFolder(folder);
                    then.accept(folder);
                })
                .setNegativeButton(R.string.send_missing_cancel, (d, w) -> onStop.accept(null))
                .setOnCancelListener(d -> onStop.accept(null))
                .show();
    }
}
