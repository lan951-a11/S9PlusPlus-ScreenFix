package com.s9plus.helper;

import android.content.Intent;
import android.content.SharedPreferences;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class HbmTileService extends TileService {
    private static String computeTileLabel(boolean active) {
        if (active && S9HelperService.lastThermalStage >= 2) {
            return "HBM Hot (" + S9HelperService.lastEffectiveCapNits + ")";
        }
        if (active && S9HelperService.lastWattThrottling) {
            return "HBM Pwr (" + S9HelperService.lastEffectiveCapNits + ")";
        }
        return active ? "720 nit HBM" : "Max HBM";
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile == null) return;
        SharedPreferences prefs = getSharedPreferences("s9_prefs", MODE_PRIVATE);
        boolean active = prefs.getBoolean("hbm_force_max", false);
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(computeTileLabel(active));
        tile.updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        SharedPreferences prefs = getSharedPreferences("s9_prefs", MODE_PRIVATE);
        boolean nextActive = !prefs.getBoolean("hbm_force_max", false);

        Intent svc = new Intent(this, S9HelperService.class);
        svc.setAction("TOGGLE_FORCE_HBM");
        startService(svc);

        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(nextActive ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel(computeTileLabel(nextActive));
            tile.updateTile();
        }
    }
}
