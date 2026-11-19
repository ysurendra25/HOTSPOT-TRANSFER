package com.pocketbridge;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Bundle;

/** User-facing disclosure and protocol response for Android Work profile setup. */
public final class CloneProvisioningActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String action = getIntent() == null ? null : getIntent().getAction();
        if (DevicePolicyManager.ACTION_GET_PROVISIONING_MODE.equals(action)) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Create a private app space")
                    .setMessage("Android will create a separate Work profile managed by PocketBridge. You choose whether to continue. App data remains separate, and PocketBridge cannot remove ads or copy app passwords.")
                    .setNegativeButton("Cancel", (dialog, which) -> { setResult(RESULT_CANCELED); finish(); })
                    .setPositiveButton("Continue", (dialog, which) -> {
                        Intent result = new Intent();
                        result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                                DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE);
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOW_OFFLINE, true);
                        }
                        setResult(RESULT_OK, result);
                        finish();
                    }).setOnCancelListener(dialog -> { setResult(RESULT_CANCELED); finish(); }).show();
            return;
        }
        if (DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE.equals(action)) {
            setResult(RESULT_OK);
            finish();
            return;
        }
        setResult(RESULT_CANCELED);
        finish();
    }
}
