package dev.neosync.boox.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import dev.neosync.boox.R;
import dev.neosync.boox.kosync.KoSyncClient;
import dev.neosync.boox.kosync.SyncSettings;
import dev.neosync.boox.service.SyncService;

/**
 * Account screen: sign in to, or create, the sync account shared with the
 * Picco. Only the MD5 of the password is stored (the kosync credential);
 * the plaintext is discarded as soon as it is hashed and is never logged.
 */
public class SignInActivity extends Activity {

    private SyncSettings settings;
    private EditText usernameField;
    private EditText passwordField;
    private EditText serverUrlField;
    private TextView resultView;
    private Button signInButton;
    private Button registerButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_in);
        settings = new SyncSettings(this);

        usernameField = findViewById(R.id.edit_username);
        passwordField = findViewById(R.id.edit_password);
        serverUrlField = findViewById(R.id.edit_server_url);
        resultView = findViewById(R.id.text_auth_result);
        signInButton = findViewById(R.id.button_sign_in);
        registerButton = findViewById(R.id.button_register);

        usernameField.setText(settings.getUsername());
        serverUrlField.setText(settings.getServerUrl());
        if (!settings.getPasswordMd5().isEmpty()) {
            passwordField.setHint(R.string.password_stored_hint);
        }

        View advanced = findViewById(R.id.group_advanced);
        if (!settings.getServerUrl().isEmpty()) {
            advanced.setVisibility(View.VISIBLE);
        }
        findViewById(R.id.text_advanced_toggle).setOnClickListener(v ->
                advanced.setVisibility(advanced.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE));

        signInButton.setOnClickListener(v -> submit(false));
        registerButton.setOnClickListener(v -> submit(true));
    }

    private void submit(boolean register) {
        String username = usernameField.getText().toString().trim();
        String password = passwordField.getText().toString();
        boolean hasStoredPassword = !settings.getPasswordMd5().isEmpty()
                && username.equals(settings.getUsername());
        if (username.isEmpty() || (password.isEmpty() && !hasStoredPassword)) {
            showResult(getString(R.string.signin_missing));
            return;
        }
        String serverUrl = serverUrlField.getText().toString();
        if (SyncSettings.isInsecureServerUrl(serverUrl)) {
            showResult(getString(R.string.signin_https_required));
            return;
        }
        settings.setServerUrl(serverUrl);
        settings.setUsername(username);
        if (!password.isEmpty()) {
            settings.setPassword(password);
            passwordField.setText("");
        }

        setBusy(true);
        showResult(getString(register ? R.string.register_in_progress : R.string.auth_in_progress));
        new Thread(() -> {
            KoSyncClient client = new KoSyncClient(settings, "NeoSync", "settings-ui");
            KoSyncClient.Result result = register ? client.register() : null;
            boolean registerOk = result == null || result.error == KoSyncClient.Error.OK;
            if (registerOk) {
                // Always confirm with a real login, also right after creating.
                result = client.authenticate();
            }
            final KoSyncClient.Result outcome = result;
            final boolean taken = register && !registerOk
                    && (outcome.httpCode == 402 || outcome.httpCode == 409);
            runOnUiThread(() -> {
                setBusy(false);
                if (outcome.error == KoSyncClient.Error.OK) {
                    SyncService.start(this);
                    startActivity(new Intent(this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    finish();
                } else if (taken) {
                    showResult(getString(R.string.register_taken));
                } else if (outcome.error == KoSyncClient.Error.AUTH_FAILED) {
                    showResult(getString(R.string.auth_failed));
                } else if (outcome.error == KoSyncClient.Error.NETWORK_ERROR) {
                    showResult(getString(R.string.network_failed));
                } else {
                    showResult(KoSyncClient.errorString(outcome));
                }
            });
        }).start();
    }

    private void setBusy(boolean busy) {
        signInButton.setEnabled(!busy);
        registerButton.setEnabled(!busy);
    }

    private void showResult(String message) {
        resultView.setText(message);
        resultView.setVisibility(View.VISIBLE);
    }
}
