package com.habitu.onetaprealhelp;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class SosTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel("SOS Button");
            tile.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        Intent serviceIntent = new Intent(this, SosNotificationService.class);
        serviceIntent.setAction(SosNotificationService.ACTION_TRIGGER_SOS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }
}
