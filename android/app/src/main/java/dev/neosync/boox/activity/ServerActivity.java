package dev.neosync.boox.activity;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import dev.neosync.boox.R;
import dev.neosync.boox.kosync.SyncSettings;
import dev.neosync.boox.service.SyncService;

/**
 * Sync server (spec 5.4, Advanced): the server address, moved here from
 * sign-in. Only https is accepted. When an account is set up, saving signs
 * in to the new server with it (SignInActivity.checkAccount) and keeps the
 * old address if that fails. The password is never asked for or shown here.
 */
public class ServerActivity extends Activity {

    private SyncSettings settings;
    private EditText field;
    private TextView resultView;
    private Button saveButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server);
        settings = new SyncSettings(this);

        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        field = findViewById(R.id.edit_server_url);
        resultView = findViewById(R.id.text_server_result);
        saveButton = findViewById(R.id.button_save_server);

        field.setText(settings.getServerUrl());
        saveButton.setOnClickListener(v -> save());
    }

    /** The server as shown in Settings and on sign-in: its address without https://. */
    static String serverLabel(SyncSettings settings) {
        String url = settings.getBaseUrl();
        int scheme = url.indexOf("://");
        return scheme >= 0 ? url.substring(scheme + 3) : url;
    }

    private void save() {
        String url = field.getText().toString().trim();
        if (SyncSettings.isInsecureServerUrl(url)) {
            showResult(getString(R.string.signin_https_required));
            return;
        }
        String previous = settings.getServerUrl();
        if (url.equals(previous)) {
            finish();
            return;
        }
        settings.setServerUrl(url);
        if (!settings.hasCredentials()) {
            // Not signed in yet: signing in checks the account on this server.
            saved();
            return;
        }
        // Keep the background sync off the unconfirmed server while it is
        // checked; it starts again either way (start() honours the switch).
        SyncService.stop(this);
        saveButton.setEnabled(false);
        showResult(getString(R.string.auth_in_progress));
        SignInActivity.checkAccount(this, settings, false, message -> {
            saveButton.setEnabled(true);
            if (message != null) {
                settings.setServerUrl(previous);
                SyncService.start(this);
                showResult(message);
                return;
            }
            SyncService.start(this);
            saved();
        });
    }

    private void saved() {
        Toast.makeText(this, R.string.server_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void showResult(String message) {
        resultView.setText(message);
        resultView.setVisibility(View.VISIBLE);
    }
}
