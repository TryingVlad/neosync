package dev.neosync.boox.activity;

import android.app.Activity;
import android.os.Bundle;

/**
 * Placeholder target for the home screen's status chips: closes at once.
 * The real Connections screen (spec 5.3) replaces it.
 */
public class ConnectionsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
