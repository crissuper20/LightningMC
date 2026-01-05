package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.crissuper20.lightning.util.DebugLogger;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LNService - Handles Lightning Network operations via LNbits API
 * 
 * This service provides high-level Lightning operations:
 * - Creating invoices
 * - Paying invoices
 * - Checking wallet info
 * 
 * Works with WalletManager for per-player wallet management
 * and WebSocketInvoiceMonitor for real-time payment notifications
 */
public class LNService {

    private final LightningPlugin plugin;
    private final WalletManager walletManager;
    private final WebSocketInvoiceMonitor invoiceMonitor;
    private final HttpClient httpClient;
    private final DebugLogger debug;
    
    private final String lnbitsUrl;
    private final String adminKey;

    private final AtomicBoolean isHealthy = new AtomicBoolean(true);
    private final AtomicLong lastHealthCheck = new AtomicLong(0);
    private final AtomicBoolean checkInProgress = new AtomicBoolean(false);

    public LNService(LightningPlugin plugin, WalletManager walletManager, WebSocketInvoiceMonitor invoiceMonitor) {
        this(plugin, walletManager, invoiceMonitor, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build());
    }

    /**
     * Changed for more testing.
     */
    public LNService(LightningPlugin plugin, WalletManager walletManager, WebSocketInvoiceMonitor invoiceMonitor, HttpClient httpClient) {
        this.plugin = plugin;
        this.walletManager = walletManager;
        this.invoiceMonitor = invoiceMonitor;
        this.httpClient = httpClient;
        this.debug = plugin.getDebugLogger().withPrefix("LNService");

        // Build LNbits URL from config
        String host = plugin.getConfig().getString("lnbits.host", "http://127.0.0.1:5000");
        boolean useHttps = plugin.getConfig().getBoolean("lnbits.use_https", false);
        
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = (useHttps ? "https://" : "http://") + host;
        }
        
        // Remove trailing slash if present
        if (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        
        this.lnbitsUrl = host;
        this.adminKey = plugin.getConfig().getString("lnbits.adminKey", "");

        debug.info("LNService initialized baseUrl=" + lnbitsUrl);
        
        // Register connection listener for instant failure detection
        this.invoiceMonitor.addConnectionListener(new WebSocketInvoiceMonitor.ConnectionListener() {
            @Override
            public void onConnected(String walletId) {
                if (!isHealthy.get()) {
                    // If we were marked as unhealthy, a successful connection suggests recovery
                    triggerHealthCheck();
                }
            }

            @Override
            public void onDisconnected(String walletId) {
                // Instant reaction to connection loss
                triggerHealthCheck();
            }
        });

        startHealthChecks();
    }

    /**
     * Starts periodic health monitoring tasks.
     */
    private void startHealthChecks() {
        plugin.getScheduler().runTaskTimerAsync(() -> {
            // If we have active websockets, we rely on them as our heartbeat.
            // We assume the server is healthy if connections are held open.
            if (invoiceMonitor.getActiveSessionCount() > 0) {
                // Only check if we currently think we are unhealthy (to recover state)
                if (!isHealthy.get()) {
                    performHealthCheck();
                }
                return;
            }
            
            // No active connections (no players?) -> Poll to keep status updated
            performHealthCheck();
        }, 200L, 200L); // Check slot every 10 seconds
    }

    private void triggerHealthCheck() {
        // Debounce: Don't check more than once every 5 seconds to avoid thundering herd
        long now = System.currentTimeMillis();
        if (now - lastHealthCheck.get() < 5000) return;
        
        plugin.getScheduler().runTaskAsync(this::performHealthCheck);
    }

    private void performHealthCheck() {
        if (checkInProgress.getAndSet(true)) return;

        debug.debug("Performing health check...");
        getWalletInfo(adminKey).thenAccept(response -> {
            boolean wasHealthy = isHealthy.get();
            boolean nowHealthy = response.success;
            
            isHealthy.set(nowHealthy);
            lastHealthCheck.set(System.currentTimeMillis());
            
            if (wasHealthy != nowHealthy) {
                if (nowHealthy) {
                    plugin.getLogger().info("LNbits connection restored!");
                    // Optional: Force reconnect sockets if they are stuck?
                    invoiceMonitor.forceReconnectNow();
                } else {
                    plugin.getLogger().warning("LNbits connection lost! (HTTP " + response.statusCode + ")");
                }
            }
        }).exceptionally(ex -> {
            boolean wasHealthy = isHealthy.get();
            isHealthy.set(false);
            lastHealthCheck.set(System.currentTimeMillis());
            
            if (wasHealthy) {
                plugin.getLogger().warning("LNbits connection lost! " + ex.getMessage());
            }
            return null;
        }).whenComplete((result, ex) -> {
            // Reset flag after async operation completes, regardless of success or failure
            checkInProgress.set(false);
        });
    }

    public boolean isHealthy() {
        return isHealthy.get();
    }

    // ================================================================
    // Invoice Operations
    // ================================================================

    /**
     * Create an invoice for a player using their wallet
     */
    public CompletableFuture<LNResponse<Invoice>> createInvoiceForPlayer(Player player, long amountSats, String memo) {
        if (!walletManager.hasWallet(player)) {
            CompletableFuture<LNResponse<Invoice>> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Player has no wallet"));
            return future;
        }

        String invoiceKey = walletManager.getPlayerInvoiceKey(player);
        String walletId = walletManager.getWalletId(player);

        if (invoiceKey == null || walletId == null) {
            CompletableFuture<LNResponse<Invoice>> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Failed to get wallet credentials"));
            return future;
        }

        return createInvoice(invoiceKey, amountSats, memo);
    }

    /**
     * Create an invoice using a specific invoice key
     */
    public CompletableFuture<LNResponse<Invoice>> createInvoice(String invoiceKey, long amountSats, String memo) {
        debug.info("Creating invoice amount=" + amountSats + " memo='" + memo + "' invoiceKey=" + maskKey(invoiceKey));
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("out", false);
        requestBody.addProperty("amount", amountSats);
        requestBody.addProperty("memo", memo);
        
        int expirySeconds = plugin.getConfig().getInt("lnbits.invoice_expiry_seconds", 3600);
        requestBody.addProperty("expiry", expirySeconds);

        String url = lnbitsUrl + "/api/v1/payments";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", invoiceKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .timeout(Duration.ofSeconds(30))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                debug.debug("Invoice response status=" + response.statusCode() + " body=" + response.body());
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        try {
                            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

                            Invoice invoice = new Invoice();
                            invoice.paymentHash = json.get("payment_hash").getAsString();
                            invoice.bolt11 = json.get("payment_request").getAsString();
                            invoice.checkingId = json.get("checking_id").getAsString();

                            return LNResponse.success(invoice, response.statusCode());
                        } catch (Exception e) {
                            debug.error("Failed to parse invoice response", e);
                            return LNResponse.<Invoice>error("Failed to parse response: " + e.getMessage(), response.statusCode());
                        }
                    } else {
                        String error = "Invoice creation failed: HTTP " + response.statusCode();
                        debug.error(error + " - " + response.body());
                        return LNResponse.<Invoice>error(error, response.statusCode());
                    }
                })
                .exceptionally(ex -> {
                    debug.error("Invoice creation exception", ex);
                    triggerHealthCheck();
                    return LNResponse.<Invoice>error("Network error: " + ex.getMessage(), 0);
                });
    }

    // ================================================================
    // Payment Operations
    // ================================================================

    /**
     * Pay an invoice using a player's wallet
     */
    public CompletableFuture<LNResponse<JsonObject>> payInvoiceForPlayer(Player player, String bolt11) {
        if (!walletManager.hasWallet(player)) {
            CompletableFuture<LNResponse<JsonObject>> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Player has no wallet"));
            return future;
        }

        String adminKeyPlayer = walletManager.getPlayerAdminKey(player);

        if (adminKeyPlayer == null) {
            CompletableFuture<LNResponse<JsonObject>> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Failed to get wallet admin key"));
            return future;
        }

        return payInvoice(adminKeyPlayer, bolt11);
    }

    /**
     * Pay an invoice using a specific admin key
     */
    public CompletableFuture<LNResponse<JsonObject>> payInvoice(String adminKey, String bolt11) {
        debug.info("Paying invoice with adminKey=" + maskKey(adminKey));
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("out", true);
        requestBody.addProperty("bolt11", bolt11);

        String url = lnbitsUrl + "/api/v1/payments";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", adminKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .timeout(Duration.ofSeconds(60))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    debug.debug("Pay invoice status=" + response.statusCode() + " body=" + response.body());
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        try {
                            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                            return LNResponse.success(json, response.statusCode());
                        } catch (Exception e) {
                            debug.error("Failed to parse payment response", e);
                            return LNResponse.<JsonObject>error("Failed to parse response: " + e.getMessage(), response.statusCode());
                        }
                    } else {
                        String error = "Payment failed: HTTP " + response.statusCode();
                        debug.error(error + " - " + response.body());
                        return LNResponse.<JsonObject>error(error + " - " + response.body(), response.statusCode());
                    }
                })
                .exceptionally(ex -> {
                    debug.error("Payment exception", ex);
                    triggerHealthCheck();
                    return LNResponse.<JsonObject>error("Network error: " + ex.getMessage(), 0);
                });
    }

    // ================================================================
    // Wallet Operations
    // ================================================================

    /**
     * Get wallet info (used for health checks)
     */
    public CompletableFuture<LNResponse<JsonObject>> getWalletInfoAsync() {
        return getWalletInfo(adminKey);
    }

    /**
     * Get wallet info for a specific player using their admin key
     */
    public CompletableFuture<LNResponse<JsonObject>> getWalletInfoForPlayer(Player player) {
        if (!walletManager.hasWallet(player)) {
            CompletableFuture<LNResponse<JsonObject>> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Player has no wallet"));
            return future;
        }

        String adminKeyPlayer = walletManager.getPlayerAdminKey(player);
        if (adminKeyPlayer == null || adminKeyPlayer.isBlank()) {
            CompletableFuture<LNResponse<JsonObject>> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Missing player admin key"));
            return future;
        }

        return getWalletInfo(adminKeyPlayer);
    }

    private CompletableFuture<LNResponse<JsonObject>> getWalletInfo(String apiKey) {
        String url = lnbitsUrl + "/api/v1/wallet";

        debug.debug("Fetching wallet info with apiKey=" + maskKey(apiKey));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Api-Key", apiKey)
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    debug.debug("Wallet info response status=" + response.statusCode() + " body=" + response.body());
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        try {
                            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                            return LNResponse.success(json, response.statusCode());
                        } catch (Exception e) {
                            return LNResponse.<JsonObject>error("Failed to parse response", response.statusCode());
                        }
                    } else {
                        debug.warn("Wallet info failed status=" + response.statusCode());
                        return LNResponse.<JsonObject>error("Failed to get wallet info", response.statusCode());
                    }
                })
                .exceptionally(ex -> {
                    debug.error("Wallet info exception", ex);
                    return LNResponse.<JsonObject>error("Network error: " + ex.getMessage(), 0);
                });
    }

    // ================================================================
    // Public API
    // ================================================================

    /**
     * Returns true if the player has a wallet set up.
     */
    public boolean hasWallet(Player player) {
        return walletManager.hasWallet(player.getUniqueId().toString());
    }

    /**
     * Returns the admin key of the player's wallet.
     */
    public String getAdminKey(Player player) {
        return walletManager.getWalletAdminKey(player.getUniqueId().toString());
    }

    /**
     * Returns the invoice key of the player's wallet.
     */
    public String getInvoiceKey(Player player) {
        return walletManager.getInvoiceKey(player.getUniqueId().toString());
    }

    /**
     * Returns the LNBits wallet ID of the player.
     */
    public String getWalletId(Player player) {
        return walletManager.getWalletId(player.getUniqueId().toString());
    }

    /**
     * Creates a new wallet for the player asynchronously.
     * This is the preferred method to avoid blocking the main thread.
     */
    public CompletableFuture<Boolean> createWalletAsync(Player player) {
        return walletManager.createWalletForAsync(player.getUniqueId().toString(), player.getName());
    }

    /**
     * Creates a new wallet for the player.
     * @deprecated Use createWalletAsync instead to avoid main thread blocking.
     */
    @Deprecated
    public boolean createWallet(Player player) {
        return walletManager.createWalletFor(player.getUniqueId().toString(), player.getName());
    }

    /**
     * Used when doing API requests
     */
    public HttpClient getHttpClient() {
        return httpClient;
    }

    /**
     * Expose WebSocket monitor for subscriptions.
     */
    public WebSocketInvoiceMonitor getInvoiceMonitor() {
        return invoiceMonitor;
    }

    /**
     * Get backend name for display
     */
    public String getBackendName() {
        return "LNbits (" + lnbitsUrl + ")";
    }

    // ================================================================
    // Data Classes
    // ================================================================

    public static class Invoice {
        public String paymentHash;
        public String bolt11;
        public String checkingId;
    }

    public static class LNResponse<T> {
        public final boolean success;
        public final T data;
        public final String error;
        public final int statusCode;

        private LNResponse(boolean success, T data, String error, int statusCode) {
            this.success = success;
            this.data = data;
            this.error = error;
            this.statusCode = statusCode;
        }

        public static <T> LNResponse<T> success(T data, int statusCode) {
            return new LNResponse<T>(true, data, null, statusCode);
        }

        public static <T> LNResponse<T> error(String error, int statusCode) {
            return new LNResponse<T>(false, null, error, statusCode);
        }
    }

    private String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "<none>";
        }
        if (key.length() <= 8) {
            return key.charAt(0) + "***" + key.charAt(key.length() - 1);
        }
        return key.substring(0, 4) + "***" + key.substring(key.length() - 4);
    }
}