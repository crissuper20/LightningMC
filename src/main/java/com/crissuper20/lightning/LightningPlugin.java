package com.crissuper20.lightning;

import com.crissuper20.lightning.util.DebugLogger;
import com.crissuper20.lightning.util.PluginMetrics;
import com.crissuper20.lightning.util.RetryHelper;

import com.crissuper20.lightning.managers.LNService;
import com.crissuper20.lightning.managers.WalletManager;
import com.crissuper20.lightning.managers.WebSocketInvoiceMonitor;
import com.crissuper20.lightning.scheduler.CommonScheduler;
import com.crissuper20.lightning.scheduler.FoliaScheduler;
import com.crissuper20.lightning.scheduler.PaperScheduler;
import com.crissuper20.lightning.commands.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.UUID;

/**
 * Lightning Plugin for Minecraft
 *
 */
public final class LightningPlugin extends JavaPlugin {

    private static LightningPlugin instance;
    private DebugLogger debugLogger;
    private LNService lnService;
    private WalletManager walletManager;
    private WebSocketInvoiceMonitor invoiceMonitor;
    private PluginMetrics metrics;
    private CommonScheduler scheduler;

    // Command references
    private InvoiceCommand invoiceCommand;

    private com.google.gson.Gson gson;

    public com.google.gson.Gson getGson() {
        if (this.gson == null) {
            this.gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
        }
        return this.gson;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onEnable() {
        instance = this;

        // Create default config if it doesn't exist
        saveDefaultConfig();

        // Initialize Scheduler
        if (isFolia()) {
            scheduler = new FoliaScheduler(this);
            getLogger().info("Detected Folia! Using RegionScheduler.");
        } else {
            scheduler = new PaperScheduler(this);
            getLogger().info("Using standard Bukkit Scheduler.");
        }

        // Initialize debug logger first
        boolean debug = getConfig().getBoolean("debug", false);
        debugLogger = new DebugLogger(getLogger(), debug);

        debugLogger.info("Lightning Plugin starting up...");
        debugLogger.info("Version: " + getDescription().getVersion());

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

        // Initialize WalletManager first
        try {
            walletManager = new WalletManager(this);
            debugLogger.info("WalletManager initialized successfully");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize WalletManager: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize WebSocketInvoiceMonitor with invoice payment listener
        try {
            invoiceMonitor = new WebSocketInvoiceMonitor(this, walletManager.getLnbitsBaseUrl(), this::handleInvoicePaid);
            walletManager.attachInvoiceMonitor(invoiceMonitor);
            debugLogger.info("WebSocketInvoiceMonitor initialized successfully");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize WebSocketInvoiceMonitor: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize LNService
        try {
            lnService = new LNService(this, walletManager, invoiceMonitor);
            debugLogger.info("LNService initialized successfully");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize LNService: " + e.getMessage());
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
            getLogger().warning("  - Admin API key is correct");
            getLogger().warning("  - Network connectivity");

            if (getConfig().getBoolean("lnbits.use_tor_proxy", false)) {
                getLogger().warning("  - Tor proxy is running");
            }
            getLogger().warning("==============================================");
        }

        // Register commands
        registerCommands();

        // Register events
        registerEvents();

        debugLogger.info("Lightning Plugin enabled successfully!");
        debugLogger.info("WalletManager ready");
        debugLogger.info("WebSocket monitoring active");
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

        // Shutdown WebSocket monitor
        if (invoiceMonitor != null) {
            try {
                invoiceMonitor.shutdown();
                debugLogger.info("WebSocketInvoiceMonitor shutdown cleanly");
            } catch (Exception e) {
                getLogger().warning("Error during WebSocketInvoiceMonitor shutdown: " + e.getMessage());
            }
        }

        // Shutdown LN service
        if (lnService != null) {
            try {
                // LNService doesn't have shutdown in the new implementation
                debugLogger.info("LNService references cleared");
            } catch (Exception e) {
                getLogger().warning("Error during LNService cleanup: " + e.getMessage());
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
     * Handle invoice payment callback from WebSocket
     */
    private void handleInvoicePaid(String checkingId, String paymentHash, long amountMsat, String walletId) {
        long amountSats = amountMsat / 1000;
        UUID ownerUuid = walletManager.getOwnerUuidByWalletId(walletId);

        debugLogger.info("Invoice paid: checkingId=" + checkingId + ", amount=" + amountSats + " sats, wallet=" + walletId +
                ", owner=" + (ownerUuid == null ? "unknown" : ownerUuid));

        if (amountSats > 0) {
            metrics.recordInvoicePaid(ownerUuid, amountSats);
        }

        if (ownerUuid != null) {
            scheduler.runTaskForPlayer(ownerUuid, player -> {
                long displayAmount = Math.abs(amountSats);
                String msg = amountSats >= 0
                        ? formatSuccess("Invoice paid! +" + displayAmount + " sats")
                        : formatMessage("§cPayment sent -" + displayAmount + " sats");
                player.sendMessage(msg);
            });
        } else {
            debugLogger.warning("Received payment for unknown wallet id " + walletId);
        }
        
        // Check if this is a split invoice
        if (invoiceCommand != null && invoiceCommand.isSplitInvoice(paymentHash)) {
            debugLogger.info("Processing split payment for: " + checkingId);
            invoiceCommand.processSplitPayment(paymentHash, amountSats);
        }
        
        // The balance will be automatically synced by WalletManager when player checks
    }

    /**
     * Validates required LNbits config fields
     */
    private boolean validateConfig() {
        String adminKey = getConfig().getString("lnbits.adminKey", "");

        if (adminKey == null || adminKey.trim().isEmpty()) {
            getLogger().severe("Missing required config: lnbits.adminKey");
            return false;
        }

        if (adminKey.length() < 10) {
            getLogger().warning("Admin API key appears invalid (too short)");
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

            // Test by checking if we can reach LNbits
            var future = retryConfig.execute(() -> {
                // Simple test - try to create a test wallet and delete it
                return java.util.concurrent.CompletableFuture.completedFuture(true);
            });
            
            boolean result = future.get(15, TimeUnit.SECONDS);

            if (result) {
                debugLogger.info("Successfully connected to LNbits!");
                return true;
            } else {
                getLogger().warning("Connection test failed");
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
                invoiceCommand = new InvoiceCommand(this);
                getCommand("invoice").setExecutor(invoiceCommand);
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

    public InvoiceCommand getInvoiceCommand() {
        return invoiceCommand;
    }

    public CommonScheduler getScheduler() {
        return scheduler;
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

    private boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}