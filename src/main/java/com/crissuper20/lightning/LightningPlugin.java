package com.crissuper20.lightning;

import com.crissuper20.lightning.util.DebugLogger;
import com.crissuper20.lightning.managers.LNService;
import com.crissuper20.lightning.commands.BalanceCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class LightningPlugin extends JavaPlugin {

    private static LightningPlugin instance;
    private DebugLogger debugLogger;
    private LNService lnService;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig(); // ensures config.yml exists

        // Initialize debug logger
        boolean debug = getConfig().getBoolean("debug", false);
        debugLogger = new DebugLogger(getLogger(), debug);

        debugLogger.info("Hello nodenation. Lightning plugin starting up...(jk, will crash.)");

        // Initialize Lightning backend (LNbits / LND)
        lnService = new LNService(this);

        // Register commands
        registerCommands();

        // Register events (optional for now)
        registerEvents();

        debugLogger.info("Plugin enabled successfully!");
    }

    @Override
    public void onDisable() {
        debugLogger.info("Lightning plugin shutting down...");
        if (lnService != null) {
            lnService.shutdown();
        }
        debugLogger.info("Plugin disabled cleanly.");
    }

    private void registerCommands() {
        getCommand("balance").setExecutor(new BalanceCommand(this));
        debugLogger.info("Registered /balance command.");
        // Add more commands later: /pay, /invoice, etc.
    }

    private void registerEvents() {
        // Example: getServer().getPluginManager().registerEvents(new PlayerJoinListener(), this);
        debugLogger.info("Event listeners registered (none yet).");
    }

    public static LightningPlugin getInstance() {
        return instance;
    }

    public DebugLogger getDebugLogger() {
        return debugLogger;
    }

    public LNService getLnService() {
        return lnService;
    }
}
