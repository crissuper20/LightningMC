package com.crissuper20.lightning;

import com.crissuper20.lightning.util.DebugLogger;
import com.crissuper20.lightning.managers.LNService;
import com.crissuper20.lightning.managers.WalletManager;
import com.crissuper20.lightning.commands.BalanceCommand;
import com.crissuper20.lightning.commands.WalletCommand;
import com.crissuper20.lightning.commands.InvoiceCommand;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class LightningPlugin extends JavaPlugin {

    private static LightningPlugin instance;
    private DebugLogger debugLogger;
    private LNService lnService;
    private WalletManager walletManager;

    @Override
    public void onEnable() {
        instance = this;
        
        // Create default config if it doesn't exist
        saveDefaultConfig();

        // Initialize debug logger first
        boolean debug = getConfig().getBoolean("debug", false);
        debugLogger = new DebugLogger(getLogger(), debug);

        debugLogger.info("Lightning Plugin starting up...");
        debugLogger.info("beware, using real money is against Minecraft EULA");

        // Validate configuration
        if (!validateConfig()) {
            getLogger().severe("==============================================");
            getLogger().severe("  PLUGIN DISABLED - INVALID CONFIGURATION");
            getLogger().severe("==============================================");
            getLogger().severe("Please check config.yml for required fields.");
            getLogger().severe("==============================================");
            
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize Lightning service (handles both LNbits and LND)
        try {
            lnService = new LNService(this);
            debugLogger.info("LNService initialized successfully");
            debugLogger.info("Backend: " + lnService.getBackend());
            
            // Initialize wallet manager
            walletManager = new WalletManager(this);
            debugLogger.info("WalletManager initialized successfully");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize LNService: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Test connection to Lightning backend
        if (!testConnection()) {
            getLogger().warning("==============================================");
            getLogger().warning("  WARNING: Could not connect to backend");
            getLogger().warning("==============================================");
            getLogger().warning("Plugin will continue, but commands may fail.");
            getLogger().warning("Please verify:");
            getLogger().warning("  - Backend instance is running");
            getLogger().warning("  - Credentials are correct");
            getLogger().warning("  - Network connectivity");
            
            String backend = getConfig().getString("backend", "lnbits");
            if (getConfig().getBoolean(backend + ".use_tor_proxy", false)) {
                getLogger().warning("  - Tor proxy is running");
            }
            getLogger().warning("==============================================");
        }

        // Register commands
        registerCommands();

        // Register events
        registerEvents();

        debugLogger.info("Lightning Plugin enabled successfully!");
    }

    @Override
    public void onDisable() {
        debugLogger.info("Lightning Plugin shutting down...");
        
        if (lnService != null) {
            try {
                lnService.shutdown();
                debugLogger.info("LNService shutdown cleanly");
            } catch (Exception e) {
                getLogger().warning("Error during LNService shutdown: " + e.getMessage());
            }
        }
        
        debugLogger.info("Lightning Plugin disabled.");
    }

    /**
     * Validates that all required config fields are present
     */
    private boolean validateConfig() {
        String backend = getConfig().getString("backend", "lnbits").toLowerCase();

        if (backend.equals("lnd")) {
            return validateLNDConfig();
        } else {
            return validateLNbitsConfig();
        }
    }

    private boolean validateLNbitsConfig() {
        String host = getConfig().getString("lnbits.host", "");
        String apiKey = getConfig().getString("lnbits.api_key", "");

        if (host.isEmpty()) {
            getLogger().severe("Missing required config: lnbits.host");
            return false;
        }

        if (apiKey.isEmpty()) {
            getLogger().warning("==============================================");
            getLogger().warning("  WARNING: No API key configured");
            getLogger().warning("==============================================");
            getLogger().warning("The plugin will work in read-only mode.");
            getLogger().warning("Set lnbits.api_key in config.yml to enable");
            getLogger().warning("full functionality.");
            getLogger().warning("==============================================");
        } else if (apiKey.length() < 10) {
            getLogger().warning("API key appears invalid (too short)");
        }

        return true;
    }

    private boolean validateLNDConfig() {
        String host = getConfig().getString("lnd.host", "");
        int port = getConfig().getInt("lnd.port", 0);
        String macaroonHex = getConfig().getString("lnd.macaroon_hex", "");
        String macaroonPath = getConfig().getString("lnd.macaroon_path", "");

        if (host.isEmpty()) {
            getLogger().severe("Missing required config: lnd.host");
            return false;
        }

        if (port == 0) {
            getLogger().severe("Missing required config: lnd.port");
            return false;
        }

        if (macaroonHex.isEmpty() && macaroonPath.isEmpty()) {
            getLogger().severe("Missing required config: lnd.macaroon_hex OR lnd.macaroon_path");
            return false;
        }

        return true;
    }

    /**
     * Tests connection to Lightning backend
     */
    private boolean testConnection() {
        debugLogger.debug("Testing connection to backend...");
        
        try {
            // Use async API with a short timeout to avoid hanging startup indefinitely.
            LNService.LNResponse<?> response = lnService.getWalletInfoAsync()
                    .get(5, TimeUnit.SECONDS);

            if (response.success) {
                debugLogger.info("Successfully connected to backend!");
                return true;
            } else {
                getLogger().warning("Connection test failed: " + response.error);
                return false;
            }
        } catch (TimeoutException e) {
            getLogger().warning("Connection test timed out (backend did not respond within 5s)");
            debugLogger.error("Connection test timeout", e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            getLogger().warning("Connection test interrupted: " + e.getMessage());
            debugLogger.error("Connection test interrupted", e);
            return false;
        } catch (ExecutionException e) {
            getLogger().warning("Connection test failed: " + e.getCause().getMessage());
            debugLogger.error("Connection test execution error", e.getCause());
            return false;
        } catch (Exception e) {
            getLogger().warning("Connection test failed: " + e.getMessage());
            debugLogger.error("Connection test unexpected error", e);
            return false;
        }
    }

    private void registerCommands() {
        try {
            if (getCommand("balance") != null) {
                getCommand("balance").setExecutor(new BalanceCommand(this));
                debugLogger.info("Registered /balance command");
            } else {
                getLogger().warning("Could not register /balance - command not found in plugin.yml");
            }
            
            if (getCommand("wallet") != null) {
                getCommand("wallet").setExecutor(new WalletCommand(this, walletManager));
                debugLogger.info("Registered /wallet command");
            } else {
                getLogger().warning("Could not register /wallet - command not found in plugin.yml");
            }
            
            if (getCommand("invoice") != null) {
                getCommand("invoice").setExecutor(new InvoiceCommand(this));
                debugLogger.info("Registered /invoice command");
            } else {
                getLogger().warning("Could not register /invoice - command not found in plugin.yml");
            }
            
            // Add more commands later: /pay, etc.
        } catch (Exception e) {
            getLogger().severe("Error registering commands: " + e.getMessage());
            debugLogger.error("Command registration error", e);
        }
    }

    private void registerEvents() {
        // Example: getServer().getPluginManager().registerEvents(new PlayerJoinListener(), this);
        debugLogger.debug("Event listeners ready (none registered yet)");
    }

    // Getters
    public static LightningPlugin getInstance() {
        return instance;
    }

    public DebugLogger getDebugLogger() {
        return debugLogger;
    }

    public LNService getLnService() {
        return lnService;
    }

    public WalletManager getWalletManager() {
        return walletManager;
    }

    /**
     * Sends a formatted message to a player/console
     */
    public static String formatMessage(String message) {
        return message;
    }

    public static String formatError(String message) {
        return message;
    }

    public static String formatSuccess(String message) {
        return message;
    }
}