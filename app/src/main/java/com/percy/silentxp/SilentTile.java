package com.percy.silentxp;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class SilentTile extends TileService {
    @Override public void onStartListening() { refresh(); }
    @Override public void onClick() {
        super.onClick();
        unlockAndRun(() -> Bridge.sync(this, status -> {
            if (!status.getBoolean("ready")) { refresh(); return; }
            boolean previous = status.getBoolean("enabled");
            Config.enabled(this, !previous);
            Bridge.sync(this, result -> {
                if (!result.getBoolean("ready")) Config.enabled(this, previous);
                refresh();
            });
        }));
    }
    private void refresh() {
        Bridge.sync(this, status -> {
            Tile tile = getQsTile();
            if (tile == null) return;
            boolean ready = status.getBoolean("ready");
            boolean enabled = status.getBoolean("enabled");
            tile.setLabel("白名单静音");
            tile.setSubtitle(!ready ? "请启用 LSPosed 并重启" : enabled ? "屏蔽已开启" : "屏蔽已关闭");
            tile.setState(!ready ? Tile.STATE_UNAVAILABLE : enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.updateTile();
        });
    }
}
