package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.clients.LNClient;
import com.crissuper20.lightning.clients.LNbitsClient;
import com.crissuper20.lightning.clients.LNDClient;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

/**
 * Lightning Network Service Manager - Player-Aware Version (Refactored)
 *
 * This version:
 *  - Uses new LNbitsClient async methods (xxxAsync(..., key))
 *  - Provides per-player LNbits wallet support via WalletManager
 *  - Validates config and prevents mainnet usage
 *  - This plugin now is big enough that AIs hallucinate on debugging ;p 
 */
public class LNService {

    private final LightningPlugin plugin;
    private final LNClient client;
    private final String backendType;
    private WalletManager walletManager; // Injected later

    public LNService(LightningPlugin plugin) {
        this.plugin = plugin;
        plugin.getDebugLogger().info("Initializing Lightning Service...");

        try {
            this.backendType = plugin.getConfig().getString("backend", "lnbits").toLowerCase();
            plugin.getDebugLogger().debug("Backend type: " + backendType);

            validateConfiguration();
            checkMainnetAndAbort();
            this.client = createClient();

            plugin.getDebugLogger().info("Lightning Service initialized with backend: " + client.getBackendName());
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize Lightning Service: " + e.getMessage());
            throw new IllegalStateException("Lightning Service initialization failed", e);
        }
    }

    public void setWalletManager(WalletManager walletManager) {
        this.walletManager = walletManager;
        plugin.getDebugLogger().debug("WalletManager injected into LNService");
    }

    private LNClient createClient() {
        switch (backendType) {
            case "lnd":
                return new LNDClient(plugin);
            case "lnbits":
                return new LNbitsClient(plugin);
            default:
                throw new IllegalArgumentException("Unknown backend: " + backendType + " (expected: lnbits or lnd)");
        }
    }

    private void validateConfiguration() {
        plugin.saveDefaultConfig();
        String hostKey = backendType.equals("lnd") ? "lnd.host" : "lnbits.host";
        String host = plugin.getConfig().getString(hostKey);

        if (host == null || host.trim().isEmpty()) {
            throw new IllegalStateException("Missing required config: " + hostKey);
        }

        if (backendType.equals("lnbits")) validateLNbitsConfig();
        if (backendType.equals("lnd")) validateLNDConfig();
        if (host.endsWith(".onion")) validateTorConfig(host);

        plugin.getDebugLogger().debug("Configuration validation successful");
    }

    private void validateLNbitsConfig() {
        String apiKey = plugin.getConfig().getString("lnbits.api_key");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Missing required config: lnbits.api_key (admin key)");
        }
        if (apiKey.length() < 32) {
            throw new IllegalStateException("Invalid lnbits.api_key: too short (expected 32+ characters)");
        }
    }

    private void validateLNDConfig() {
        int port = plugin.getConfig().getInt("lnd.port", -1);
        if (port <= 0 || port > 65535)
            throw new IllegalStateException("Invalid lnd.port: must be between 1 and 65535");

        String macaroonHex = plugin.getConfig().getString("lnd.macaroon_hex", "");
        String macaroonPath = plugin.getConfig().getString("lnd.macaroon_path", "");

        if (macaroonHex.isEmpty() && macaroonPath.isEmpty()) {
            throw new IllegalStateException("Must specify either lnd.macaroon_hex or lnd.macaroon_path");
        }

        if (!macaroonPath.isEmpty() && !Files.exists(Paths.get(macaroonPath))) {
            throw new IllegalStateException("Invalid lnd.macaroon_path: file not found " + macaroonPath);
        }
    }

    private void validateTorConfig(String host) {
        String prefix = backendType.equals("lnd") ? "lnd" : "lnbits";
        boolean useTor = plugin.getConfig().getBoolean(prefix + ".use_tor_proxy", false);
        if (!useTor)
            throw new IllegalStateException("Using .onion host requires " + prefix + ".use_tor_proxy: true");
    }

    private void checkMainnetAndAbort() {
        String globalNet = plugin.getConfig().getString("network", "").trim();
        String backendNetKey = backendType.equals("lnd") ? "lnd.network" : "lnbits.network";
        String backendNet = plugin.getConfig().getString(backendNetKey, "").trim();

        boolean isMainnet = "mainnet".equalsIgnoreCase(globalNet) || "mainnet".equalsIgnoreCase(backendNet);
        if (isMainnet) {
            String msg = "MAINNET USE BLOCKED: plugin not allowed on mainnet (EULA compliance)";
            plugin.getLogger().severe("=".repeat(60));
            plugin.getLogger().severe(msg);
            plugin.getLogger().severe("=".repeat(60));
            plugin.getServer().getPluginManager().disablePlugin(plugin);
            throw new IllegalStateException(msg);
        }
    }

    // ========================================================================
    // Player-Aware LNbits API
    // ========================================================================

    public CompletableFuture<LNClient.LNResponse<LNClient.Invoice>> createInvoiceForPlayer(
            Player player, long amountSats, String memo) {

        if (!backendType.equals("lnbits")) {
            plugin.getLogger().warning("Per-player invoices only supported with LNbits backend");
            return client.createInvoiceAsync(amountSats, memo);
        }

        if (walletManager == null)
            throw new IllegalStateException("WalletManager not initialized");

        String key = walletManager.getPlayerAdminKey(player);
        if (key == null)
            return CompletableFuture.completedFuture(LNClient.LNResponse.failure("Player has no wallet", 400));

        LNbitsClient lnbits = (LNbitsClient) client;
        return lnbits.createInvoiceAsync(amountSats, memo, key);
    }

    public CompletableFuture<LNClient.LNResponse<Long>> getBalanceForPlayer(Player player) {
        if (!backendType.equals("lnbits")) {
            plugin.getLogger().warning("Per-player balances only supported with LNbits backend");
            return client.getBalanceAsync(null);
        }

        if (walletManager == null)
            throw new IllegalStateException("WalletManager not initialized");

        String key = walletManager.getPlayerAdminKey(player);
        if (key == null)
            return CompletableFuture.completedFuture(LNClient.LNResponse.failure("Player has no wallet", 400));

        LNbitsClient lnbits = (LNbitsClient) client;
        return lnbits.getWalletInfoAsync(key)
                .thenApply(resp -> {
                    if (!resp.success)
                        return LNClient.LNResponse.failure(resp.error, resp.statusCode);
                    long sats = resp.data.get("balance").getAsLong() / 1000;
                    return LNClient.LNResponse.success(sats, resp.statusCode);
                });
    }

    public CompletableFuture<LNClient.LNResponse<Boolean>> checkInvoiceForPlayer(Player player, String paymentHash) {
        if (!backendType.equals("lnbits")) {
            plugin.getLogger().warning("Per-player invoice checks only supported with LNbits backend");
            return client.checkInvoiceAsync(paymentHash);
        }

        if (walletManager == null)
            throw new IllegalStateException("WalletManager not initialized");

        String key = walletManager.getPlayerAdminKey(player);
        if (key == null)
            return CompletableFuture.completedFuture(LNClient.LNResponse.failure("Player has no wallet", 400));

        LNbitsClient lnbits = (LNbitsClient) client;
        return lnbits.checkInvoiceAsync(paymentHash, key);
    }

    public CompletableFuture<LNClient.LNResponse<JsonObject>> payInvoiceForPlayer(Player player, String bolt11) {
        if (!backendType.equals("lnbits")) {
            plugin.getLogger().warning("Per-player payments only supported with LNbits backend");
            return client.payInvoiceAsync(bolt11);
        }

        if (walletManager == null)
            throw new IllegalStateException("WalletManager not initialized");

        String key = walletManager.getPlayerAdminKey(player);
        if (key == null)
            return CompletableFuture.completedFuture(LNClient.LNResponse.failure("Player has no wallet", 400));

        LNbitsClient lnbits = (LNbitsClient) client;
        return lnbits.payInvoiceAsync(bolt11, key);
    }

    // ========================================================================
    // Shared/Admin Wallet API (backward compatible)
    // ========================================================================

    public CompletableFuture<LNClient.LNResponse<JsonObject>> getWalletInfoAsync() {
        return client.getWalletInfoAsync();
    }

    public CompletableFuture<LNClient.LNResponse<Long>> getBalanceAsync(String walletId) {
        return client.getBalanceAsync(walletId);
    }

    public CompletableFuture<LNClient.LNResponse<LNClient.Invoice>> createInvoiceAsync(long amountSats, String memo) {
        return client.createInvoiceAsync(amountSats, memo);
    }

    public CompletableFuture<LNClient.LNResponse<Boolean>> checkInvoiceAsync(String paymentHash) {
        return client.checkInvoiceAsync(paymentHash);
    }

    public CompletableFuture<LNClient.LNResponse<JsonObject>> payInvoiceAsync(String bolt11) {
        return client.payInvoiceAsync(bolt11);
    }

    // ========================================================================
    // Diagnostics & Accessors
    // ========================================================================

    public boolean isHealthy() {
        return client.isHealthy();
    }

    public LNClient.HealthMetrics getHealthMetrics() {
        return client.getHealthMetrics();
    }

    public void shutdown() {
        plugin.getDebugLogger().info("Shutting down Lightning Service...");
        client.shutdown();
    }

    public String getBackend() {
        return backendType;
    }

    public String getBackendName() {
        return client.getBackendName();
    }

    public LNClient getClient() {
        return client;
    }

    public boolean supportsPerPlayerWallets() {
        return backendType.equals("lnbits");
    }
}
