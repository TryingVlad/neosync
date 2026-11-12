package dev.neosync.boox.activity;

import android.app.Activity;
import android.os.Bundle;
import android.widget.RadioGroup;

import dev.neosync.boox.R;
import dev.neosync.boox.kosync.SyncSettings;

/**
 * Book matching (spec 5.4, Advanced): by file name or by file contents,
 * saved as soon as it is picked, with a warning that both devices must use
 * the same mode.
 */
public class MatchingActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_matching);
        SystemBars.fit(this);
        SyncSettings settings = new SyncSettings(this);

        findViewById(R.id.button_back).setOnClickListener(v -> finish());

        RadioGroup group = findViewById(R.id.group_match_method);
        group.check(SyncSettings.MATCH_BINARY.equals(settings.getMatchMethod())
                ? R.id.radio_match_binary : R.id.radio_match_filename);
        group.setOnCheckedChangeListener((g, id) -> settings.setMatchMethod(
                id == R.id.radio_match_binary
                        ? SyncSettings.MATCH_BINARY : SyncSettings.MATCH_FILENAME));
    }
}
