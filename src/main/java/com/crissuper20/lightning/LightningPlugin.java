package com.crissuper20.lightning;

import com.crissuper20.lightning.util.DebugLogger;
import com.crissuper20.lightning.util.PluginMetrics;
import com.crissuper20.lightning.util.RetryHelper;
import com.crissuper20.lightning.managers.WebSocketInvoiceMonitor;
import com.crissuper20.lightning.managers.LNService;
import com.crissuper20.lightning.managers.WalletManager;
import com.crissuper20.lightning.commands.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Lightning Plugin for Minecraft - LNbits Backend with WebSocket support
 * 
 * Mainnet validation removed - FakeWallet extension handles testnet simulation
 */
public class LightningPlugin extends JavaPlugin {

    private static LightningPlugin instance;
    private DebugLogger debugLogger;
    private LNService lnService;
    private WalletManager walletManager;
    private WebSocketInvoiceMonitor invoiceMonitor;
    private PluginMetrics metrics;

    @Override
    public void onEnable() {
        instance = this;
        
        // Create default config if it doesn't exist
        saveDefaultConfig();

        // Initialize debug logger first
        boolean debug = getConfig().getBoolean("debug", false);
        debugLogger = new DebugLogger(getLogger(), debug);

        debugLogger.info("Lightning Plugin starting up...");
        debugLogger.info("Version: " + getDescription().getVersion());
        debugLogger.info("Backend: LNbits (WebSocket)");
        debugLogger.info("Note: Use FakeWallet extension for testnet simulation");

        // Initialize metrics system
        metrics = new PluginMetrics();
        debugLogger.info("Metrics system initialized");

        // Validate configuration
        if (!validateConfig()) {
            getLogger().severe("==============================================");
            getLogger().severe("  PLUGIN DISABLED - INVALID CONFIGURATION");
            getLogger().severe("==============================================");
            getLogger().severe("Please check config.yml for LNbits settings");
            getLogger().severe("==============================================");
            
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize Lightning service
        try {
            lnService = new LNService(this);
            debugLogger.info("LNService initialized successfully");
            debugLogger.info("Backend: " + lnService.getBackendName());
        } catch (Exception e) {
            getLogger().severe("Failed to initialize LNService: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize wallet manager
        try {
            walletManager = new WalletManager(this);
            debugLogger.info("WalletManager initialized successfully");
            
            // IMPORTANT: Inject WalletManager into LNService
            lnService.setWalletManager(walletManager);
            debugLogger.info("WalletManager injected into LNService");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize WalletManager: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Test connection to LNbits backend with retry
        if (!testConnection()) {
            getLogger().warning("==============================================");
            getLogger().warning("  WARNING: Could not connect to LNbits");
            getLogger().warning("==============================================");
            getLogger().warning("Plugin will continue, but commands may fail.");
            getLogger().warning("Please verify:");
            getLogger().warning("  - LNbits instance is running");
            getLogger().warning("  - API key is correct");
            getLogger().warning("  - Network connectivity");
            
            if (getConfig().getBoolean("lnbits.use_tor_proxy", false)) {
                getLogger().warning("  - Tor proxy is running");
            }
            getLogger().warning("==============================================");
        }

        // Initialize invoice monitor
        try {
            invoiceMonitor = new WebSocketInvoiceMonitor(this);
            debugLogger.info("WebSocketInvoiceMonitor initialized successfully");
            debugLogger.info("Real-time payment notifications enabled!");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize WebSocketInvoiceMonitor: " + e.getMessage());
            e.printStackTrace();
            // Don't disable plugin - monitor is optional
        }

        // Register commands
        registerCommands();

        // Register events
        registerEvents();

        debugLogger.info("Lightning Plugin enabled successfully!");
        debugLogger.info("Wallets loaded: " + walletManager.getWalletCount());
        debugLogger.info("Ready to process Lightning payments!");
    }

    @Override
    public void onDisable() {
        debugLogger.info("Lightning Plugin shutting down...");
        
        // Show final metrics
        if (metrics != null) {
            debugLogger.info("=== Final Statistics ===");
            debugLogger.info("Uptime: " + metrics.getUptimeFormatted());
            debugLogger.info("Invoices: " + metrics.getInvoicesCreated() + " created, " + 
                           metrics.getInvoicesPaid() + " paid");
            debugLogger.info("Payments: " + metrics.getPaymentsSuccessful() + "/" + 
                           metrics.getPaymentsAttempted() + " successful");
            debugLogger.info("Volume: ↓" + metrics.getTotalSatsDeposited() + " ↑" + 
                           metrics.getTotalSatsWithdrawn() + " sats");
        }
        
        // Shutdown components in reverse order
        if (invoiceMonitor != null) {
            try {
                invoiceMonitor.shutdown();
                debugLogger.info("WebSocketInvoiceMonitor shutdown cleanly");
            } catch (Exception e) {
                getLogger().warning("Error during WebSocketInvoiceMonitor shutdown: " + e.getMessage());
            }
        }
        
        if (lnService != null) {
            try {
                lnService.shutdown();
                debugLogger.info("LNService shutdown cleanly");
            } catch (Exception e) {
                getLogger().warning("Error during LNService shutdown: " + e.getMessage());
            }
        }
        
        // Shutdown retry helper
        try {
            RetryHelper.shutdown();
            debugLogger.info("RetryHelper shutdown cleanly");
        } catch (Exception e) {
            getLogger().warning("Error during RetryHelper shutdown: " + e.getMessage());
        }
        
        debugLogger.info("Lightning Plugin disabled.");
    }

    /**
     * Validates required LNbits config fields
     * Network checking removed - FakeWallet handles testnet simulation
     */
    private boolean validateConfig() {
        String host = getConfig().getString("lnbits.host", "");
        String apiKey = getConfig().getString("lnbits.api_key", "");

        if (host.isEmpty()) {
            getLogger().severe("Missing required config: lnbits.host");
            return false;
        }

        if (apiKey.isEmpty()) {
            getLogger().severe("Missing required config: lnbits.api_key");
            return false;
        }
        
        if (apiKey.length() < 10) {
            getLogger().warning("API key appears invalid (too short)");
        }

        return true;
    }

    /**
     * Tests connection to LNbits backend with retry logic
     */
    private boolean testConnection() {
        debugLogger.debug("Testing connection to LNbits...");
        
        try {
            // Use retry helper for resilient connection test
            RetryHelper.RetryConfig retryConfig = new RetryHelper.RetryConfig()
                .maxAttempts(3)
                .initialDelay(1000)
                .backoff(2.0);
            
            // Use LNService.LNResponse instead of LNClient.LNResponse
            LNService.LNResponse<?> response = retryConfig.execute(() -> 
                lnService.getWalletInfoAsync()
            ).get(15, TimeUnit.SECONDS);

            if (response.success) {
                debugLogger.info("Successfully connected to LNbits!");
                return true;
            } else {
                getLogger().warning("Connection test failed: " + response.error);
                metrics.recordApiError();
                return false;
            }
        } catch (TimeoutException e) {
            getLogger().warning("Connection test timed out");
            debugLogger.error("Connection test timeout", e);
            metrics.recordNetworkError();
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            getLogger().warning("Connection test interrupted: " + e.getMessage());
            debugLogger.error("Connection test interrupted", e);
            return false;
        } catch (ExecutionException e) {
            getLogger().warning("Connection test failed: " + e.getCause().getMessage());
            debugLogger.error("Connection test execution error", e.getCause());
            metrics.recordNetworkError();
            return false;
        } catch (Exception e) {
            getLogger().warning("Connection test failed: " + e.getMessage());
            debugLogger.error("Connection test unexpected error", e);
            metrics.recordError("connection_test", e.getMessage());
            return false;
        }
    }

    private void registerCommands() {
        try {
            if (getCommand("balance") != null) {
                getCommand("balance").setExecutor(new BalanceCommand(this));
                debugLogger.info("Registered /balance command");
            }
            
            if (getCommand("wallet") != null) {
                getCommand("wallet").setExecutor(new WalletCommand(this, walletManager));
                debugLogger.info("Registered /wallet command");
            }
            
            if (getCommand("invoice") != null) {
                getCommand("invoice").setExecutor(new InvoiceCommand(this));
                debugLogger.info("Registered /invoice command");
            }
            
            if (getCommand("pay") != null) {
                getCommand("pay").setExecutor(new PayCommand(this));
                debugLogger.info("Registered /pay command");
            }
            
            if (getCommand("lnadmin") != null) {
                AdminCommand adminCmd = new AdminCommand(this, metrics);
                getCommand("lnadmin").setExecutor(adminCmd);
                getCommand("lnadmin").setTabCompleter(adminCmd);
                debugLogger.info("Registered /lnadmin command");
            }
        } catch (Exception e) {
            getLogger().severe("Error registering commands: " + e.getMessage());
            debugLogger.error("Command registration error", e);
        }
    }

    private void registerEvents() {
        debugLogger.debug("Event listeners ready (none registered yet)");
    }

    // ================================================================
    // Getters
    // ================================================================

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
    
    public WebSocketInvoiceMonitor getInvoiceMonitor() {
        return invoiceMonitor;
    }
    
    public PluginMetrics getMetrics() {
        return metrics;
    }

    // ================================================================
    // Message formatting helpers 
    // ================================================================

    public String formatMessage(String message) {
        String prefix = getConfig().getString("messages.prefix", "§8[§eLN§8]§r ");
        return prefix + message;
    }

    public String formatError(String message) {
        return formatMessage("§c" + message);
    }

    public String formatSuccess(String message) {
        return formatMessage("§a" + message);
    }
    
    // Static helper for when you don't have plugin instance
    public static String simpleFormat(String message) {
        return "§8[§eln§8]§r " + message;
    }
}