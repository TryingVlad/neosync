package dev.neosync.boox.activity;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import dev.neosync.boox.R;
import dev.neosync.boox.epub.EpubCover;
import dev.neosync.boox.jobs.CleanPlan;
import dev.neosync.boox.jobs.FixCoversJob;
import dev.neosync.boox.transfer.PiccoClient;

/**
 * "Fix missing covers" confirmation (spec 5.6, mockup 7): each book's black
 * tile and the cover it gets, the Picco warning when it applies, the
 * Picco-only tick box (unticked), and "Add N covers", which starts
 * FixCoversJob. The job's behaviour is unchanged.
 */
final class CoverSheet {

    /** Rows shown before "+ N more". */
    private static final int MAX_ROWS = 3;
    private static final int[] ROWS = {R.id.cover_row_1, R.id.cover_row_2, R.id.cover_row_3};
    private static final int[] TITLES = {R.id.cover_title_1, R.id.cover_title_2,
            R.id.cover_title_3};
    private static final int[] KINDS = {R.id.cover_kind_1, R.id.cover_kind_2, R.id.cover_kind_3};
    private static final int[] AFTER_TEXT = {R.id.cover_after_text_1, R.id.cover_after_text_2,
            R.id.cover_after_text_3};
    private static final int[] AFTER_IMAGE = {R.id.cover_after_image_1,
            R.id.cover_after_image_2, R.id.cover_after_image_3};

    private CoverSheet() {
    }

    /** Shows the plan; home stays busy until the sheet starts the job or closes. */
    static void show(MainActivity a, CleanPlan plan) {
        a.setBusy(true);
        View v = LayoutInflater.from(a).inflate(R.layout.sheet_covers, null, false);
        BottomSheetDialog dialog = BookSheet.dialog(a, v);
        final boolean[] started = {false};

        int heading = plan.booxBooks.isEmpty() ? plan.piccoOnly.size() : plan.booxBooks.size();
        ((TextView) v.findViewById(R.id.cover_heading)).setText(a.getResources()
                .getQuantityString(R.plurals.cover_title, heading, heading));

        bindRows(a, v, plan);

        int onPicco = plan.piccoPathOf.size();
        TextView warning = v.findViewById(R.id.cover_warning);
        if (onPicco > 0) {
            SpannableStringBuilder text = new SpannableStringBuilder(a.getResources()
                    .getQuantityString(R.plurals.cover_warning_head, onPicco, onPicco));
            text.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.append(' ').append(a.getResources()
                    .getQuantityString(R.plurals.cover_warning_body, onPicco));
            warning.setText(text);
            warning.setVisibility(View.VISIBLE);
        }

        CheckBox piccoOnly = v.findViewById(R.id.cover_picco_only);
        if (!plan.piccoOnly.isEmpty()) {
            piccoOnly.setText(a.getString(R.string.clean_picco_only_checkbox,
                    plan.piccoOnly.size(), MainActivity.titleList(plan.piccoOnlyTitles)));
            piccoOnly.setChecked(false);
            piccoOnly.setVisibility(View.VISIBLE);
        }

        Button add = v.findViewById(R.id.cover_primary);
        Runnable label = () -> {
            int n = count(plan, piccoOnly.isChecked());
            add.setText(a.getResources().getQuantityString(R.plurals.cover_add, n, n));
            add.setEnabled(n > 0);
        };
        label.run();
        piccoOnly.setOnCheckedChangeListener((b, checked) -> label.run());

        add.setOnClickListener(x -> {
            boolean includePiccoOnly = piccoOnly.isChecked();
            if (count(plan, includePiccoOnly) == 0) {
                return;
            }
            started[0] = true;
            dialog.dismiss();
            // Application context: the job outlives this screen (rotation).
            Context app = a.getApplicationContext();
            a.startJob(new FixCoversJob(app, a.settings, a.repository, a.stateStore,
                    a.buildClient(), a.deviceId(), new PiccoClient(a.settings.getPiccoAddress()),
                    plan, includePiccoOnly));
        });

        dialog.setOnDismissListener(d -> {
            a.sheetClosed(dialog);
            if (!started[0]) {
                a.setBusy(false);
            }
        });
        a.sheetOpened(dialog);
        dialog.show();
    }

    private static int count(CleanPlan plan, boolean includePiccoOnly) {
        return plan.booxBooks.size() + (includePiccoOnly ? plan.piccoOnly.size() : 0);
    }

    /** Up to three before -> after rows, then "+ N more". */
    private static void bindRows(MainActivity a, View v, CleanPlan plan) {
        int shown = Math.min(MAX_ROWS, plan.booxBooks.size());
        final List<File> withPicture = new ArrayList<>();
        final List<ImageView> images = new ArrayList<>();
        final List<TextView> texts = new ArrayList<>();
        for (int i = 0; i < shown; i++) {
            File file = plan.booxBooks.get(i);
            String title = i < plan.booxTitles.size() ? plan.booxTitles.get(i) : file.getName();
            EpubCover.Kind kind = plan.kindOf.get(file.getPath());
            boolean picture = kind == EpubCover.Kind.TAG_IMAGE;
            ((TextView) v.findViewById(TITLES[i])).setText(title);
            ((TextView) v.findViewById(KINDS[i])).setText(kind == null ? ""
                    : a.getString(picture ? R.string.cover_from_book : R.string.cover_first_page));
            TextView after = v.findViewById(AFTER_TEXT[i]);
            after.setText(title); // the first page shows the title; replaced by a picture below
            if (picture) {
                withPicture.add(file);
                images.add(v.findViewById(AFTER_IMAGE[i]));
                texts.add(after);
            }
            v.findViewById(ROWS[i]).setVisibility(View.VISIBLE);
        }
        int more = plan.booxBooks.size() - shown;
        if (more > 0) {
            TextView moreView = v.findViewById(R.id.cover_more);
            moreView.setText(a.getString(R.string.cover_more, more));
            moreView.setVisibility(View.VISIBLE);
        }
        if (withPicture.isEmpty()) {
            return;
        }
        final int w = Math.round(34 * a.getResources().getDisplayMetrics().density);
        final int h = Math.round(48 * a.getResources().getDisplayMetrics().density);
        new Thread(() -> {
            List<Bitmap> made = new ArrayList<>();
            for (File f : withPicture) {
                made.add(a.metaCache.thumbnail(f, w, h));
            }
            a.runOnUiThread(() -> {
                for (int i = 0; i < made.size(); i++) {
                    if (made.get(i) != null) {
                        images.get(i).setImageBitmap(made.get(i));
                        images.get(i).setVisibility(View.VISIBLE);
                        texts.get(i).setText("");
                    }
                }
            });
        }, "NeoSync cover thumbs").start();
    }
}
