package dev.neosync.boox.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import dev.neosync.boox.R;
import dev.neosync.boox.kosync.SyncSettings;

/**
 * Settings screen: account, Picco connection, book matching, and the guide.
 * Everything that is not a day-to-day action lives here instead of the home
 * screen.
 */
public class SettingsActivity extends Activity {

    private SyncSettings settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new SyncSettings(this);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.button_back).setOnClickListener(v -> finish());

        findViewById(R.id.button_account).setOnClickListener(v ->
                startActivity(new Intent(this, SignInActivity.class)));

        EditText piccoAddressField = findViewById(R.id.field_picco_address);
        piccoAddressField.setText(settings.getPiccoAddress());
        piccoAddressField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                settings.setPiccoAddress(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) { }
        });

        RadioGroup matchMethodGroup = findViewById(R.id.group_match_method);
        matchMethodGroup.check(SyncSettings.MATCH_BINARY.equals(settings.getMatchMethod())
                ? R.id.radio_match_binary : R.id.radio_match_filename);
        matchMethodGroup.setOnCheckedChangeListener((group, id) -> settings.setMatchMethod(
                id == R.id.radio_match_binary
                        ? SyncSettings.MATCH_BINARY : SyncSettings.MATCH_FILENAME));

        findViewById(R.id.button_support).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse(getString(R.string.support_url))));
            } catch (android.content.ActivityNotFoundException e) {
                // No browser on this device: the address is in the text above.
                android.widget.Toast.makeText(this, R.string.support_url,
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });

        findViewById(R.id.button_how_it_works).setOnClickListener(v ->
                startActivity(new Intent(this, OnboardingActivity.class)
                        .putExtra(OnboardingActivity.EXTRA_REVIEW, true)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refreshed here, not in onCreate: the account may have just changed.
        ((TextView) findViewById(R.id.text_settings_account))
                .setText(getString(R.string.main_signed_in_as, settings.getUsername()));
    }
}
