package com.pocketbridge;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Profile owner used only for the user-approved Android Work profile. */
public final class CloneAdminReceiver extends DeviceAdminReceiver {
    @Override public void onProfileProvisioningComplete(Context context, Intent intent) {
        super.onProfileProvisioningComplete(context, intent);
        DevicePolicyManager policy = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(context, CloneAdminReceiver.class);
        try {
            if (policy != null && policy.isProfileOwnerApp(context.getPackageName())) {
                policy.setProfileEnabled(admin);
            }
        } catch (RuntimeException error) {
            Log.e("PocketBridge", "Could not enable the user-approved private app profile", error);
        }
    }
}
