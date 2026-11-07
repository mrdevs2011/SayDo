package uz.saydo.app.kernel.ui;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import uz.saydo.app.FloatService;

/**
 * System Quick Settings Tile Service (API 24+).
 * Provides one-tap toggle for the SayDo floating button in notification shade.
 */
public final class SayDoTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean isActive = FloatService.showing;

        if (isActive) {
            // Stop services
            stopService(new Intent(this, FloatService.class));
        } else {
            // Start services
            Intent floatIntent = new Intent(this, FloatService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(floatIntent);
            } else {
                startService(floatIntent);
            }
        }

        updateTileState();
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean isActive = FloatService.showing;
        tile.setState(isActive ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(isActive ? "SayDo: Active" : "SayDo: Paused");
        tile.updateTile();
    }
}
