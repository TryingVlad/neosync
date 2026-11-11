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
    private TextView serverLink;
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
        serverLink = findViewById(R.id.text_server_link);
        resultView = findViewById(R.id.text_auth_result);
        signInButton = findViewById(R.id.button_sign_in);
        registerButton = findViewById(R.id.button_register);

        usernameField.setText(settings.getUsername());
        if (!settings.getPasswordMd5().isEmpty()) {
            passwordField.setHint(R.string.password_stored_hint);
        }

        // The server field itself lives on its own page (Settings → Advanced).
        serverLink.setOnClickListener(v ->
                startActivity(new Intent(this, ServerActivity.class)));

        signInButton.setOnClickListener(v -> submit(false));
        registerButton.setOnClickListener(v -> submit(true));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refreshed here: the server may have just been changed on its page.
        serverLink.setText(android.text.Html.fromHtml(getString(R.string.signin_server_link,
                        android.text.TextUtils.htmlEncode(ServerActivity.serverLabel(settings))),
                android.text.Html.FROM_HTML_MODE_LEGACY));
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
        if (SyncSettings.isInsecureServerUrl(settings.getServerUrl())) {
            // Set before http servers were refused: change it on the server page.
            showResult(getString(R.string.signin_https_required));
            return;
        }
        settings.setUsername(username);
        if (!password.isEmpty()) {
            settings.setPassword(password);
            passwordField.setText("");
        }

        setBusy(true);
        showResult(getString(register ? R.string.register_in_progress : R.string.auth_in_progress));
        checkAccount(this, settings, register, message -> {
            setBusy(false);
            if (message == null) {
                SyncService.start(this);
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                finish();
            } else {
                showResult(message);
            }
        });
    }

    /**
     * Signs in (after creating the account when register is set) with the
     * account and server already in settings, off the UI thread. done gets,
     * on the UI thread, null when the server accepted the account, else the
     * sentence to show. Shared with ServerActivity, which re-checks the
     * account against a new server before keeping it.
     */
    static void checkAccount(Activity activity, SyncSettings settings, boolean register,
                             java.util.function.Consumer<String> done) {
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
            final String message;
            if (outcome.error == KoSyncClient.Error.OK) {
                message = null;
            } else if (taken) {
                message = activity.getString(R.string.register_taken);
            } else if (outcome.error == KoSyncClient.Error.AUTH_FAILED) {
                message = activity.getString(R.string.auth_failed);
            } else if (outcome.error == KoSyncClient.Error.NETWORK_ERROR) {
                message = activity.getString(R.string.network_failed);
            } else {
                message = KoSyncClient.errorString(outcome);
            }
            activity.runOnUiThread(() -> {
                if (!activity.isDestroyed()) {
                    done.accept(message);
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
