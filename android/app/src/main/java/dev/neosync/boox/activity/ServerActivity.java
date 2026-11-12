package dev.neosync.boox.activity;

import android.app.Activity;
import android.content.Context;
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
 * new address only if that works. The password is never asked for or shown here.
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
        SystemBars.fit(this);
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
        return settings.getServerLabel();
    }

    private void save() {
        String url = field.getText().toString().trim();
        if (SyncSettings.isInsecureServerUrl(url)) {
            showResult(getString(R.string.signin_https_required));
            return;
        }
        if (url.equals(settings.getServerUrl())) {
            finish();
            return;
        }
        Context app = getApplicationContext();
        if (!settings.hasCredentials()) {
            // Not signed in yet: signing in checks the account on this server.
            settings.setServerUrl(url);
            saved();
            return;
        }
        // The new address is kept only once the account works there; until
        // then nothing is saved and background sync carries on as before.
        SyncSettings candidate = new SyncSettings(app) {
            @Override
            public String getServerUrl() {
                return url;
            }
        };
        saveButton.setEnabled(false);
        showResult(getString(R.string.auth_in_progress));
        SignInActivity.checkAccount(this, candidate, false, message -> {
            if (message == null) {
                // Runs even if this page was left meanwhile (R25).
                settings.setServerUrl(url);
                // Restart so the service drops what it knew about the old server.
                SyncService.stop(app);
                SyncService.start(app);
            }
            if (isDestroyed()) {
                return;
            }
            saveButton.setEnabled(true);
            if (message == null) {
                saved();
            } else {
                showResult(message);
            }
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
