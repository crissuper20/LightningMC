package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;

public class LNService {
    private final LightningPlugin plugin;

    public LNService(LightningPlugin plugin) {
        this.plugin = plugin;
        plugin.getDebugLogger().debug("Initializing Lightning service,");
        // TODO: load all the lighnting backend stuff here. 
    }

    public void shutdown() {
        plugin.getDebugLogger().debug("Shutting down Lightning service...");
        // TODO: close connections!
    }
}
