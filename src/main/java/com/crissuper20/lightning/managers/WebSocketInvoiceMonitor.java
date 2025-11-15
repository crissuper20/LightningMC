package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket Lightning invoice monitor
 * 
 */
public class WebSocketInvoiceMonitor {
    private final LightningPlugin plugin;
    private final WalletManager walletManager;

    // Track pending invoices
    private final ConcurrentHashMap<String, PendingInvoice> pendingInvoices;

    // Track active WebSocket connections per wallet
    private final ConcurrentHashMap<String, WalletWebSocket> activeConnections;

    // Track all player wallets for external payment monitoring
    private final ConcurrentHashMap<UUID, Long> lastKnownBalances;

    // Prevent duplicate processing
    private final ConcurrentHashMap<String, CompletableFuture<Void>> processingInvoices;
    
    // NEW: Track recently processed payments to avoid double-counting
    private final ConcurrentHashMap<UUID, Long> recentlyProcessedPayments;

    // Scheduler for reconnection attempts and cleanup
    private final ScheduledExecutorService scheduler;

    // HTTP client for WebSocket connections
    private final HttpClient httpClient;

    // Configuration
    private final String wsBaseUrl;
    private final long invoiceExpiryMinutes;
    private final int maxReconnectAttempts;
    private final long reconnectInitialDelayMs;
    private final double reconnectBackoffMultiplier;
    private final long externalPaymentCheckIntervalSeconds;

    // Metrics
    private final AtomicInteger totalProcessed = new AtomicInteger(0);
    private final AtomicInteger totalExpired = new AtomicInteger(0);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicInteger externalPaymentsDetected = new AtomicInteger(0);

    public WebSocketInvoiceMonitor(LightningPlugin plugin) {
        this.plugin = plugin;
        this.walletManager = plugin.getWalletManager();
        this.pendingInvoices = new ConcurrentHashMap<>();
        this.activeConnections = new ConcurrentHashMap<>();
        this.lastKnownBalances = new ConcurrentHashMap<>();
        this.processingInvoices = new ConcurrentHashMap<>();
        this.recentlyProcessedPayments = new ConcurrentHashMap<>();

        // Load configuration
        String host = plugin.getConfig().getString("lnbits.host");
        boolean useHttps = plugin.getConfig().getBoolean("lnbits.use_https", true);

        // Base WebSocket URL
        this.wsBaseUrl = (useHttps ? "wss://" : "ws://") + host + "/api/v1/ws";

        this.invoiceExpiryMinutes = plugin.getConfig().getLong("invoice_monitor.expiry_minutes", 60);
        this.maxReconnectAttempts = plugin.getConfig().getInt("websocket.max_reconnect_attempts", 5);
        this.reconnectInitialDelayMs = plugin.getConfig().getLong("websocket.reconnect_initial_delay_ms", 1000);
        this.reconnectBackoffMultiplier = plugin.getConfig().getDouble("websocket.reconnect_backoff_multiplier", 2.0);
        this.externalPaymentCheckIntervalSeconds = plugin.getConfig().getLong("invoice_monitor.external_payment_check_interval", 30);

        // Create HTTP client for WebSocket
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

        // Create scheduler
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "WebSocketMonitor-Worker");
            t.setDaemon(true);
            return t;
        });

        // Start periodic cleanup task
        startCleanupTask();

        // Start external payment monitoring
        startExternalPaymentMonitoring();

        plugin.getDebugLogger().info("WebSocketInvoiceMonitor initialized:");
        plugin.getDebugLogger().info("  WebSocket Base URL: " + wsBaseUrl);
        plugin.getDebugLogger().info("  Invoice expiry: " + invoiceExpiryMinutes + " minutes");
        plugin.getDebugLogger().info("  Max reconnect attempts: " + maxReconnectAttempts);
        plugin.getDebugLogger().info("  External payment check: every " + externalPaymentCheckIntervalSeconds + "s");
    }

    /**
     * Start periodic cleanup of expired invoices
     */
    private void startCleanupTask() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                cleanupExpiredInvoices();
                cleanupRecentPayments();
            } catch (Exception e) {
                plugin.getDebugLogger().error("Cleanup task error", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Monitor for external payments (payments received outside plugin)
     */
    private void startExternalPaymentMonitoring() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkForExternalPayments();
            } catch (Exception e) {
                plugin.getDebugLogger().error("External payment check error", e);
            }
        }, externalPaymentCheckIntervalSeconds, externalPaymentCheckIntervalSeconds, TimeUnit.SECONDS);
    }

    private void checkForExternalPayments() {
        plugin.getDebugLogger().debug("=== Checking for External Payments ===");
        plugin.getDebugLogger().debug("Online players: " + Bukkit.getOnlinePlayers().size());
        
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!walletManager.hasWallet(player)) {
                plugin.getDebugLogger().debug("Skipping " + player.getName() + " - no wallet");
                continue;
            }

            walletManager.fetchBalanceFromLNbits(player)
                .thenAccept(currentBalance -> {
                    UUID uuid = player.getUniqueId();
                    Long lastBalance = lastKnownBalances.get(uuid);

                    plugin.getDebugLogger().debug("Balance for " + player.getName() + 
                        ": last=" + lastBalance + ", current=" + currentBalance);

                    if (lastBalance != null && currentBalance > lastBalance) {
                        long difference = currentBalance - lastBalance;
                        
                        // NEW: Check if this was recently processed via WebSocket
                        Long recentPayment = recentlyProcessedPayments.get(uuid);
                        if (recentPayment != null && Math.abs(recentPayment - difference) < 10) {
                            plugin.getDebugLogger().debug("Skipping payment for " + player.getName() + 
                                " - already processed via WebSocket (amount: " + difference + " sats)");
                            // Update balance but don't notify
                            lastKnownBalances.put(uuid, currentBalance);
                            return;
                        }
                        
                        // This is a genuine external payment
                        externalPaymentsDetected.incrementAndGet();

                        plugin.getDebugLogger().info(" External payment detected for " +
                            player.getName() + ": +" + difference + " sats");

                        // Notify player
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            player.sendMessage("§a§l PAYMENT RECEIVED!");
                            player.sendMessage("§7Amount: §f+" + difference + " sats");
                            player.sendMessage("§7New balance: §a" + currentBalance + " sats");
                            player.sendMessage("§8(Received externally)");

                            // Play sound
                            try {
                                player.playSound(player.getLocation(), "entity.experience_orb.pickup", 1.0f, 1.0f);
                            } catch (Exception e) {
                                // Ignore sound errors
                            }
                        });
                    }

                    // Update last known balance
                    lastKnownBalances.put(uuid, currentBalance);
                })
                .exceptionally(ex -> {
                    plugin.getDebugLogger().debug("Failed to check balance for " + player.getName() + ": " + ex.getMessage());
                    return null;
                });
        }
    }

    /**
     * NEW: Clean up old entries from recently processed payments
     * Remove entries older than 2 minutes
     */
    private void cleanupRecentPayments() {
        if (recentlyProcessedPayments.isEmpty()) return;
        
        plugin.getDebugLogger().debug("Cleaning up recent payment records (" + 
            recentlyProcessedPayments.size() + " entries)");

        recentlyProcessedPayments.clear();
    }

    /**
     * Track an invoice and establish WebSocket connection
     */
    public void trackInvoice(Player player, String paymentHash, long amountSats, String memo) {
        plugin.getDebugLogger().info("=== TRACKING NEW INVOICE ===");
        plugin.getDebugLogger().info("Player: " + player.getName());
        plugin.getDebugLogger().info("Payment Hash: " + paymentHash);
        plugin.getDebugLogger().info("Amount: " + amountSats + " sats");

        PendingInvoice pending = new PendingInvoice(
            player.getUniqueId(),
            player.getName(),
            paymentHash,
            amountSats,
            memo,
            System.currentTimeMillis()
        );

        pendingInvoices.put(paymentHash, pending);

        // Initialize last known balance for this player
        walletManager.fetchBalanceFromLNbits(player)
            .thenAccept(balance -> {
                lastKnownBalances.put(player.getUniqueId(), balance);
                plugin.getDebugLogger().debug("Initialized balance for " + player.getName() + ": " + balance + " sats");
            })
            .exceptionally(ex -> {
                plugin.getDebugLogger().error("Failed to get initial balance for " + player.getName(), ex);
                return null;
            });

        String adminKey = walletManager.getPlayerAdminKey(player);
        if (adminKey == null) {
            plugin.getDebugLogger().error("Cannot track invoice - no wallet key for " + player.getName());
            return;
        }

        String walletId = getWalletId(player);
        if (walletId == null) {
            plugin.getDebugLogger().error("Failed to get wallet ID for " + player.getName());
            return;
        }

        plugin.getDebugLogger().info("Wallet ID: " + walletId);

        WalletWebSocket ws = activeConnections.computeIfAbsent(walletId,
            id -> createWebSocketConnection(id, adminKey));

        ws.addTrackedInvoice(paymentHash);

        plugin.getDebugLogger().info("=== TRACKING COMPLETE ===");
        plugin.getDebugLogger().info("Pending invoices: " + pendingInvoices.size());
        plugin.getDebugLogger().info("Active connections: " + activeConnections.size());
    }

    /**
     * Stop tracking an invoice
     */
    public void stopTracking(String paymentHash) {
        PendingInvoice removed = pendingInvoices.remove(paymentHash);
        if (removed != null) {
            plugin.getDebugLogger().debug("Stopped tracking invoice " + paymentHash);

            for (WalletWebSocket ws : activeConnections.values()) {
                ws.removeTrackedInvoice(paymentHash);
            }
        }
    }

    /**
     * Create WebSocket connection for a wallet
     */
    private WalletWebSocket createWebSocketConnection(String walletId, String adminKey) {
        WalletWebSocket walletWs = new WalletWebSocket(walletId, adminKey);
        walletWs.connect();
        return walletWs;
    }

    /**
     * Get wallet ID for a player
     */
    private String getWalletId(Player player) {
        try {
            return walletManager.getOrCreateWallet(player)
                .thenApply(json -> json.has("id") ? json.get("id").getAsString() : null)
                .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getDebugLogger().error("Failed to get wallet ID for " + player.getName(), e);
            return null;
        }
    }

    private void handlePaidInvoice(String paymentHash, JsonObject paymentData) {
        CompletableFuture<Void> processingFuture = new CompletableFuture<>();
        CompletableFuture<Void> existingFuture = processingInvoices.putIfAbsent(paymentHash, processingFuture);

        if (existingFuture != null) {
            plugin.getDebugLogger().debug("Invoice " + paymentHash + " already being processed");
            return; // Already being processed
        }

        try {
            PendingInvoice pending = pendingInvoices.remove(paymentHash);
            if (pending == null) {
                plugin.getDebugLogger().debug("Payment notification for non-tracked invoice: " + paymentHash);
                processingInvoices.remove(paymentHash);
                processingFuture.complete(null);
                return;
            }

            totalProcessed.incrementAndGet();
            plugin.getDebugLogger().info(" Invoice PAID (WebSocket): " + paymentHash +
                " (" + pending.amountSats + " sats) for " + pending.playerName);

            // NEW: Record this payment to prevent external detector from flagging it
            recentlyProcessedPayments.put(pending.playerUuid, pending.amountSats);

            Player player = Bukkit.getPlayer(pending.playerUuid);

            if (player != null && player.isOnline()) {
                walletManager.fetchBalanceFromLNbits(player)
                    .thenAccept(newBalance -> {
                        // Update last known balance
                        lastKnownBalances.put(pending.playerUuid, newBalance);

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            player.sendMessage("§a§l INVOICE PAID");
                            player.sendMessage("§7Amount: §f" + pending.amountSats + " sats");
                            if (pending.memo != null && !pending.memo.isEmpty()) {
                                player.sendMessage("§7Memo: §f" + pending.memo);
                            }
                            player.sendMessage("§7New balance: §a" + newBalance + " sats");
                            player.sendMessage("§8(Detected via WebSocket)"); // Added for clarity

                            try {
                                player.playSound(player.getLocation(), "entity.experience_orb.pickup", 1.0f, 1.0f);
                            } catch (Exception e) {
                                // Ignore
                            }
                        });
                    })
                    .exceptionally(ex -> {
                        plugin.getDebugLogger().error("Failed to fetch balance after payment", ex);
                        return null;
                    })
                    .whenComplete((v, ex) -> {
                        processingInvoices.remove(paymentHash);
                        processingFuture.complete(null);
                    });
            } else {
                scheduleOfflineNotification(pending);
                processingInvoices.remove(paymentHash);
                processingFuture.complete(null);
            }

        } catch (Exception e) {
            plugin.getDebugLogger().error("Error handling paid invoice", e);
            processingInvoices.remove(paymentHash);
            processingFuture.completeExceptionally(e);
        }
    }

    /**
     * Schedule notification for offline player
     */
    private void scheduleOfflineNotification(PendingInvoice pending) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getConfig().set("pending_notifications." + pending.playerUuid + "." +
                System.currentTimeMillis(),
                "Invoice paid: " + pending.amountSats + " sats" +
                    (pending.memo != null && !pending.memo.isEmpty() ? " - " + pending.memo : ""));
            plugin.saveConfig();
        });
    }

    /**
     * Clean up expired invoices
     */
    private void cleanupExpiredInvoices() {
        long now = System.currentTimeMillis();
        long expiryTime = invoiceExpiryMinutes * 60 * 1000;

        pendingInvoices.entrySet().removeIf(entry -> {
            PendingInvoice pending = entry.getValue();
            long age = now - pending.createdTime;

            if (age > expiryTime) {
                totalExpired.incrementAndGet();
                plugin.getDebugLogger().debug("Invoice " + entry.getKey() +
                    " expired after " + (age / 60000) + " minutes");
                notifyExpired(pending);

                for (WalletWebSocket ws : activeConnections.values()) {
                    ws.removeTrackedInvoice(entry.getKey());
                }

                return true;
            }
            return false;
        });
    }

    public Map<String, PendingInvoice> getAllPendingInvoices() {
        return new ConcurrentHashMap<>(pendingInvoices);
    }

    /**
     * Notify player of expired invoice
     */
    private void notifyExpired(PendingInvoice pending) {
        Player player = Bukkit.getPlayer(pending.playerUuid);
        if (player != null && player.isOnline()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage("§c⚠ Your invoice for " + pending.amountSats + " sats has expired.");
                player.sendMessage("§7Please create a new invoice if needed.");
            });
        }
    }

    // Metrics
    public int getPendingCount() { return pendingInvoices.size(); }
    public int getTotalProcessed() { return totalProcessed.get(); }
    public int getTotalExpired() { return totalExpired.get(); }
    public int getActiveConnections() { return activeConnections.size(); }
    public int getReconnectAttempts() { return reconnectAttempts.get(); }
    public int getExternalPaymentsDetected() { return externalPaymentsDetected.get(); }

    public int getPendingCountForPlayer(Player player) {
        return (int) pendingInvoices.values().stream()
            .filter(p -> p.playerUuid.equals(player.getUniqueId()))
            .count();
    }

    /**
     * Shutdown gracefully
     */
    public void shutdown() {
        plugin.getDebugLogger().info("Shutting down WebSocketInvoiceMonitor...");

        for (WalletWebSocket ws : activeConnections.values()) {
            ws.close();
        }
        activeConnections.clear();

        try {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }

        plugin.getDebugLogger().info("WebSocketInvoiceMonitor stopped");
    }

    // ================================================================
    // WalletWebSocket class
    // ================================================================
    private class WalletWebSocket {
        private final String walletId;
        private final String adminKey;
        private final ConcurrentHashMap<String, Boolean> trackedInvoices;

        private WebSocket webSocket;
        private int reconnectAttempt = 0;
        private volatile boolean intentionallyClosed = false;

        public WalletWebSocket(String walletId, String adminKey) {
            this.walletId = walletId;
            this.adminKey = adminKey;
            this.trackedInvoices = new ConcurrentHashMap<>();
        }

        public void addTrackedInvoice(String paymentHash) {
            trackedInvoices.put(paymentHash, true);
            plugin.getDebugLogger().debug("WS tracking invoice: " + paymentHash + " (total: " + trackedInvoices.size() + ")");
        }

        public void removeTrackedInvoice(String paymentHash) {
            trackedInvoices.remove(paymentHash);
            plugin.getDebugLogger().debug("WS stopped tracking: " + paymentHash + " (remaining: " + trackedInvoices.size() + ")");

            if (trackedInvoices.isEmpty()) {
                plugin.getDebugLogger().info("No more tracked invoices for wallet " + walletId + " - closing WebSocket");
                close();
                activeConnections.remove(walletId);
            }
        }

        public void connect() {
            if (webSocket != null && !webSocket.isOutputClosed()) {
                plugin.getDebugLogger().debug("WebSocket already connected for wallet " + walletId);
                return;
            }

            tryConnectVariants();
        }

        private void tryConnectVariants() {
            // Variant 1: wallet-specific path
            String walletUrl = wsBaseUrl + "/" + walletId;
            try {
                plugin.getDebugLogger().info("Attempting WebSocket connection to: " + walletUrl);
                webSocket = httpClient.newWebSocketBuilder()
                    .header("X-Api-Key", adminKey)
                    .buildAsync(URI.create(walletUrl), new WebSocketListener(walletUrl))
                    .get(30, TimeUnit.SECONDS);

                reconnectAttempt = 0;
                plugin.getDebugLogger().info("✓ WebSocket connected (wallet-specific) for wallet: " + walletId);
                return;
            } catch (Exception e) {
                plugin.getDebugLogger().debug("Wallet-specific WebSocket failed: " + e.getMessage());
            }

            // Variant 2: base path
            try {
                plugin.getDebugLogger().info("Attempting WebSocket connection to base: " + wsBaseUrl);
                webSocket = httpClient.newWebSocketBuilder()
                    .header("X-Api-Key", adminKey)
                    .buildAsync(URI.create(wsBaseUrl), new WebSocketListener(wsBaseUrl))
                    .get(30, TimeUnit.SECONDS);

                reconnectAttempt = 0;
                plugin.getDebugLogger().info("✓ WebSocket connected (base) for wallet: " + walletId);
                return;
            } catch (Exception e) {
                plugin.getDebugLogger().error("WebSocket connection FAILED for wallet " + walletId + " on both paths", e);
                scheduleReconnect();
            }
        }

        private void scheduleReconnect() {
            if (intentionallyClosed || reconnectAttempt >= maxReconnectAttempts) {
                plugin.getDebugLogger().warning("WebSocket reconnect abandoned for wallet " + walletId);
                return;
            }

            long delay = (long) (reconnectInitialDelayMs * Math.pow(reconnectBackoffMultiplier, reconnectAttempt));
            reconnectAttempt++;
            reconnectAttempts.incrementAndGet();

            plugin.getDebugLogger().info("Scheduling WebSocket reconnect #" + reconnectAttempt + " in " + delay + "ms");
            scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        }

        public void close() {
            intentionallyClosed = true;
            if (webSocket != null) {
                try {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Closing");
                } catch (Exception e) {
                    // ignore
                }
            }
        }

        private class WebSocketListener implements WebSocket.Listener {
            private final StringBuilder messageBuffer = new StringBuilder();
            private final String connectedUrl;

            public WebSocketListener(String connectedUrl) {
                this.connectedUrl = connectedUrl;
            }

            @Override
            public void onOpen(WebSocket webSocket) {
                plugin.getDebugLogger().info("✓ WebSocket OPEN for wallet: " + walletId + " (URL: " + connectedUrl + ")");
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                messageBuffer.append(data);

                if (last) {
                    String message = messageBuffer.toString();
                    messageBuffer.setLength(0);

                    plugin.getDebugLogger().debug("WebSocket message received (" + message.length() + " chars)");

                    try {
                        handleMessage(message);
                    } catch (Exception e) {
                        plugin.getDebugLogger().error("Error handling WebSocket message", e);
                    }
                }

                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                plugin.getDebugLogger().info("WebSocket closed: " + statusCode + " - " + reason + " for wallet: " + walletId);
                if (!intentionallyClosed && !trackedInvoices.isEmpty()) {
                    scheduleReconnect();
                }
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                plugin.getDebugLogger().error("WebSocket error for wallet: " + walletId, error);
                if (!intentionallyClosed && !trackedInvoices.isEmpty()) {
                    scheduleReconnect();
                }
            }

            private void handleMessage(String message) {
                plugin.getDebugLogger().info("═══════════════════════════════════════");
                plugin.getDebugLogger().info("RAW WEBSOCKET MESSAGE:");
                plugin.getDebugLogger().info(message);
                plugin.getDebugLogger().info("Tracked invoices: " + trackedInvoices.keySet());
                plugin.getDebugLogger().info("═══════════════════════════════════════");
                
                try {
                    JsonObject json = JsonParser.parseString(message).getAsJsonObject();
                    plugin.getDebugLogger().info("JSON KEYS: " + json.keySet());
                    
                    String paymentHash = extractPaymentHash(json);
                    
                    if (paymentHash == null) {
                        plugin.getDebugLogger().debug("No payment identifier found in message");
                        return;
                    }
                    
                    plugin.getDebugLogger().info("Found payment hash: " + paymentHash);
                    
                    if (!trackedInvoices.containsKey(paymentHash)) {
                        plugin.getDebugLogger().debug("Payment " + paymentHash + " not tracked by this connection");
                        return;
                    }
                    
                    plugin.getDebugLogger().info("TRACKED PAYMENT FOUND: " + paymentHash);
                    
                    boolean isPaid = checkPaymentStatus(json);
                    
                    if (isPaid) {
                        plugin.getDebugLogger().info(" PAYMENT CONFIRMED VIA WEBSOCKET");
                        plugin.getDebugLogger().info("Payment Hash: " + paymentHash);
                        
                        handlePaidInvoice(paymentHash, json);
                        removeTrackedInvoice(paymentHash);
                    } else {
                        plugin.getDebugLogger().info("Payment not settled yet for: " + paymentHash);
                    }
                    
                } catch (JsonSyntaxException e) {
                    plugin.getDebugLogger().error("Invalid JSON from WebSocket: " + message, e);
                } catch (Exception e) {
                    plugin.getDebugLogger().error("Error handling WebSocket message", e);
                    e.printStackTrace();
                }
            }

            private String extractPaymentHash(JsonObject json) {
                JsonObject payload = json;
                if (json.has("data") && json.get("data").isJsonObject()) {
                    payload = json.getAsJsonObject("data");
                    plugin.getDebugLogger().debug("Found wrapped data object");
                }
                
                String[] fields = {"checking_id", "payment_hash", "hash", "r_hash", "id"};
                
                for (String field : fields) {
                    if (payload.has(field) && !payload.get(field).isJsonNull()) {
                        try {
                            String value = payload.get(field).getAsString();
                            if (value != null && !value.isEmpty()) {
                                plugin.getDebugLogger().info("Found hash in field '" + field + "': " + value);
                                return value;
                            }
                        } catch (Exception e) {
                            plugin.getDebugLogger().debug("Failed to read field '" + field + "': " + e.getMessage());
                        }
                    }
                }
                
                plugin.getDebugLogger().debug("No payment hash found. Available fields: " + payload.keySet());
                return null;
            }

            private boolean checkPaymentStatus(JsonObject json) {
                JsonObject payload = json.has("data") && json.get("data").isJsonObject() 
                    ? json.getAsJsonObject("data") 
                    : json;
                
                if (payload.has("pending")) {
                    JsonElement pending = payload.get("pending");
                    try {
                        if (pending.isJsonPrimitive()) {
                            JsonPrimitive prim = pending.getAsJsonPrimitive();
                            if (prim.isBoolean()) {
                                boolean isPending = prim.getAsBoolean();
                                plugin.getDebugLogger().info("Status from pending (boolean): " + !isPending);
                                return !isPending;
                            } else if (prim.isNumber()) {
                                int pendingInt = prim.getAsInt();
                                plugin.getDebugLogger().info("Status from pending (integer): " + (pendingInt == 0));
                                return pendingInt == 0;
                            }
                        }
                    } catch (Exception e) {
                        plugin.getDebugLogger().debug("Failed to read pending field: " + e.getMessage());
                    }
                }
                
                if (payload.has("paid")) {
                    try {
                        boolean paid = payload.get("paid").getAsBoolean();
                        plugin.getDebugLogger().info("Status from paid field: " + paid);
                        return paid;
                    } catch (Exception e) {
                        plugin.getDebugLogger().debug("Failed to read paid field: " + e.getMessage());
                    }
                }
                
                if (payload.has("status")) {
                    try {
                        String status = payload.get("status").getAsString().toLowerCase();
                        boolean isPaid = status.equals("paid") || status.equals("settled") || status.equals("complete");
                        plugin.getDebugLogger().info("Status from status field: '" + status + "' = " + isPaid);
                        return isPaid;
                    } catch (Exception e) {
                        plugin.getDebugLogger().debug("Failed to read status field: " + e.getMessage());
                    }
                }
                
                plugin.getDebugLogger().warning("No status indicator found. Available fields: " + payload.keySet());
                return false;
            }
        }
    }

    public static class PendingInvoice {
        public final UUID playerUuid;
        public final String playerName;
        public final String paymentHash;
        public final long amountSats;
        public final String memo;
        public final long createdTime;

        public PendingInvoice(UUID playerUuid, String playerName, String paymentHash,
                              long amountSats, String memo, long createdTime) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.paymentHash = paymentHash;
            this.amountSats = amountSats;
            this.memo = memo;
            this.createdTime = createdTime;
        }

        public long getAgeMinutes() {
            return (System.currentTimeMillis() - createdTime) / 60000;
        }
    }
}