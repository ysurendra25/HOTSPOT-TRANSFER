package com.pocketbridge;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile "Refresh mobile data". One tap runs the phone's
 * airplane reset: automatically when PocketBridge has the adb grant or its
 * accessibility service, otherwise by opening the guided screen.
 */
public final class AirplaneTileService extends TileService {
    @Override public void onStartListening() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Tile.STATE_INACTIVE);
        tile.setLabel("Refresh mobile data");
        tile.updateTile();
    }

    @Override public void onClick() {
        if (!"guided".equals(AirplaneController.method(this))) {
            AirplaneController.startCycle(this, "tile");
            return;
        }
        Intent open = new Intent(this, AirplaneCycleActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 9, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapseCompat(open);
        }
    }

    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private void startActivityAndCollapseCompat(Intent open) { startActivityAndCollapse(open); }
}
