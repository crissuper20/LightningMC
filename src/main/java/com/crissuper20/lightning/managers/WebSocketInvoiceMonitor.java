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
 * Replaces polling with real-time WebSocket connections for instant payment notifications.
 */
public class WebSocketInvoiceMonitor {
    
    private final LightningPlugin plugin;
    private final WalletManager walletManager;
    
    // Track pending invoices
    private final ConcurrentHashMap<String, PendingInvoice> pendingInvoices;
    
    // Track active WebSocket connections per wallet
    private final ConcurrentHashMap<String, WalletWebSocket> activeConnections;
    
    // Prevent duplicate processing
    private final ConcurrentHashMap<String, CompletableFuture<Void>> processingInvoices;
    
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
    
    // Metrics
    private final AtomicInteger totalProcessed = new AtomicInteger(0);
    private final AtomicInteger totalExpired = new AtomicInteger(0);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    
    public WebSocketInvoiceMonitor(LightningPlugin plugin) {
        this.plugin = plugin;
        this.walletManager = plugin.getWalletManager();
        this.pendingInvoices = new ConcurrentHashMap<>();
        this.activeConnections = new ConcurrentHashMap<>();
        this.processingInvoices = new ConcurrentHashMap<>();
        
        // Load configuration
        String host = plugin.getConfig().getString("lnbits.host");
        boolean useHttps = plugin.getConfig().getBoolean("lnbits.use_https", true);
        this.wsBaseUrl = (useHttps ? "wss://" : "ws://") + host + "/api/v1/ws";
        
        this.invoiceExpiryMinutes = plugin.getConfig().getLong("invoice_monitor.expiry_minutes", 60);
        this.maxReconnectAttempts = plugin.getConfig().getInt("websocket.max_reconnect_attempts", 5);
        this.reconnectInitialDelayMs = plugin.getConfig().getLong("websocket.reconnect_initial_delay_ms", 1000);
        this.reconnectBackoffMultiplier = plugin.getConfig().getDouble("websocket.reconnect_backoff_multiplier", 2.0);
        
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
        
        plugin.getDebugLogger().info("WebSocketInvoiceMonitor initialized:");
        plugin.getDebugLogger().info("  WebSocket URL: " + wsBaseUrl);
        plugin.getDebugLogger().info("  Invoice expiry: " + invoiceExpiryMinutes + " minutes");
        plugin.getDebugLogger().info("  Max reconnect attempts: " + maxReconnectAttempts);
    }
    
    /**
     * Start periodic cleanup of expired invoices
     */
    private void startCleanupTask() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                cleanupExpiredInvoices();
            } catch (Exception e) {
                plugin.getDebugLogger().error("Cleanup task error", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }
    
    /**
     * Track an invoice and establish WebSocket connection
     */
public void trackInvoice(Player player, String paymentHash, long amountSats, String memo) {
    plugin.getLogger().info("=== TRACKING NEW INVOICE ===");
    plugin.getLogger().info("Player: " + player.getName());
    plugin.getLogger().info("Payment Hash: " + paymentHash);
    plugin.getLogger().info("Amount: " + amountSats + " sats");
    plugin.getLogger().info("Memo: " + memo);
    
    PendingInvoice pending = new PendingInvoice(
        player.getUniqueId(),
        player.getName(),
        paymentHash,
        amountSats,
        memo,
        System.currentTimeMillis()
    );
    
    pendingInvoices.put(paymentHash, pending);
    plugin.getLogger().info("Added to pending invoices (total: " + pendingInvoices.size() + ")");
    
    String adminKey = walletManager.getPlayerAdminKey(player);
    if (adminKey == null) {
        plugin.getLogger().severe("Cannot track invoice - no wallet key for " + player.getName());
        return;
    }
    plugin.getLogger().info("Got admin key: " + adminKey.substring(0, 8) + "...");
    
    plugin.getLogger().info("Getting wallet ID...");
    String walletId = getWalletId(player);
    
    if (walletId == null) {
        plugin.getLogger().severe("Failed to get wallet ID for " + player.getName());
        plugin.getLogger().severe("WebSocket will NOT be connected!");
        return;
    }
    
    plugin.getLogger().info("Got wallet ID: " + walletId);
    plugin.getLogger().info("Creating/getting WebSocket connection...");
    
    WalletWebSocket ws = activeConnections.computeIfAbsent(walletId, 
        id -> {
            plugin.getLogger().info("Creating NEW WebSocket for wallet: " + id);
            return createWebSocketConnection(id, adminKey);
        });
    
    ws.addTrackedInvoice(paymentHash);
    
    plugin.getLogger().info("Invoice added to WebSocket tracker");
    plugin.getLogger().info("Active connections: " + activeConnections.size());
    plugin.getLogger().info("Pending invoices: " + pendingInvoices.size());
    plugin.getLogger().info("=== TRACKING COMPLETE ===");
}
    
    /**
     * Stop tracking an invoice
     */
    public void stopTracking(String paymentHash) {
        PendingInvoice removed = pendingInvoices.remove(paymentHash);
        if (removed != null) {
            plugin.getDebugLogger().debug("Stopped tracking invoice " + paymentHash);
            
            // Remove from WebSocket tracker
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
        // Extract wallet ID from player's wallet data
        try {
            return walletManager.getOrCreateWallet(player)
                .thenApply(json -> json.has("id") ? json.get("id").getAsString() : null)
                .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getDebugLogger().error("Failed to get wallet ID for " + player.getName(), e);
            return null;
        }
    }
    
    /**
     * Handle a paid invoice (called from WebSocket listener)
     */
    private void handlePaidInvoice(String paymentHash, JsonObject paymentData) {
        // Check if already processing
        CompletableFuture<Void> processingFuture = new CompletableFuture<>();
        CompletableFuture<Void> existingFuture = processingInvoices.putIfAbsent(paymentHash, processingFuture);
        
        if (existingFuture != null) {
            // Already being processed
            return;
        }
        
        try {
            PendingInvoice pending = pendingInvoices.remove(paymentHash);
            if (pending == null) {
                // Not tracking this invoice (maybe already processed)
                return;
            }
            
            totalProcessed.incrementAndGet();
            plugin.getDebugLogger().info("Invoice PAID (WebSocket): " + paymentHash + 
                " (" + pending.amountSats + " sats) for " + pending.playerName);
            
            // Get player
            Player player = Bukkit.getPlayer(pending.playerUuid);
            
            if (player != null && player.isOnline()) {
                // Player is online - fetch fresh balance and notify
                walletManager.fetchBalanceFromLNbits(player)
                    .thenAccept(newBalance -> {
                        // Sync to main thread for Bukkit API
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            player.sendMessage("§a§l INVOICE PAID!");
                            player.sendMessage("§7Amount: §f" + pending.amountSats + " sats");
                            if (pending.memo != null && !pending.memo.isEmpty()) {
                                player.sendMessage("§7Memo: §f" + pending.memo);
                            }
                            player.sendMessage("§7New balance: §a" + newBalance + " sats");
                            
                            // Play sound effect
                            try {
                                player.playSound(player.getLocation(), "entity.experience_orb.pickup", 1.0f, 1.0f);
                            } catch (Exception e) {
                                // Ignore sound errors
                            }
                        });
                    })
                    .exceptionally(ex -> {
                        plugin.getDebugLogger().error("Failed to fetch balance after payment", ex);
                        return null;
                    });
            } else {
                // Player is offline
                plugin.getDebugLogger().info("Player " + pending.playerName + 
                    " is offline, will sync on next login");
                scheduleOfflineNotification(pending);
            }
            
        } finally {
            processingInvoices.remove(paymentHash);
            processingFuture.complete(null);
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
                
                // Remove from WebSocket trackers
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
    
    /**
     * Get metrics
     */
    public int getPendingCount() {
        return pendingInvoices.size();
    }
    
    public int getTotalProcessed() {
        return totalProcessed.get();
    }
    
    public int getTotalExpired() {
        return totalExpired.get();
    }
    
    public int getActiveConnections() {
        return activeConnections.size();
    }
    
    public int getReconnectAttempts() {
        return reconnectAttempts.get();
    }
    
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
        plugin.getDebugLogger().info("  Processed: " + totalProcessed.get() + " invoices");
        plugin.getDebugLogger().info("  Expired: " + totalExpired.get() + " invoices");
        plugin.getDebugLogger().info("  Pending: " + pendingInvoices.size() + " invoices");
        plugin.getDebugLogger().info("  Active connections: " + activeConnections.size());
        
        // Close all WebSocket connections
        for (WalletWebSocket ws : activeConnections.values()) {
            ws.close();
        }
        activeConnections.clear();
        
        // Shutdown scheduler
        try {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getDebugLogger().warning("Forcing WebSocketMonitor shutdown...");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        
        plugin.getDebugLogger().info("WebSocketInvoiceMonitor stopped");
    }
    
    // ================================================================
    // Inner Classes
    // ================================================================
    
    /**
     * Manages a WebSocket connection for a specific wallet
     */
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
        }
        
        public void removeTrackedInvoice(String paymentHash) {
            trackedInvoices.remove(paymentHash);
            
            // Close connection if no more invoices to track
            if (trackedInvoices.isEmpty()) {
                close();
                activeConnections.remove(walletId);
            }
        }
        
        public void connect() {
            if (webSocket != null && !webSocket.isOutputClosed()) {
                plugin.getLogger().info("WebSocket already connected for wallet: " + walletId);
                return; // Already connected
            }
            
            try {
                String url = wsBaseUrl + "/" + walletId;
                
                plugin.getLogger().info("=== Connecting WebSocket ===");
                plugin.getLogger().info("URL: " + url);
                plugin.getLogger().info("Wallet ID: " + walletId);
                plugin.getLogger().info("Tracking " + trackedInvoices.size() + " invoices");
                
                webSocket = httpClient.newWebSocketBuilder()
                    .header("X-Api-Key", adminKey)
                    .buildAsync(URI.create(url), new WebSocketListener())
                    .get(30, TimeUnit.SECONDS);
                
                reconnectAttempt = 0;
                plugin.getLogger().info("✓ WebSocket connection established for wallet: " + walletId);
                
            } catch (Exception e) {
                plugin.getLogger().severe("✗ WebSocket connection FAILED for " + walletId);
                plugin.getDebugLogger().error("WebSocket connection error details", e);
                scheduleReconnect();
            }
        }
        
        private void scheduleReconnect() {
            if (intentionallyClosed) {
                return;
            }
            
            if (reconnectAttempt >= maxReconnectAttempts) {
                plugin.getDebugLogger().error("Max reconnect attempts reached for wallet " + walletId);
                return;
            }
            
            long delay = (long) (reconnectInitialDelayMs * Math.pow(reconnectBackoffMultiplier, reconnectAttempt));
            reconnectAttempt++;
            reconnectAttempts.incrementAndGet();
            
            plugin.getDebugLogger().info("Scheduling WebSocket reconnect for " + walletId + 
                " in " + delay + "ms (attempt " + reconnectAttempt + ")");
            
            scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        }
        
        public void close() {
            intentionallyClosed = true;
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Closing");
                plugin.getDebugLogger().debug("WebSocket closed for wallet: " + walletId);
            }
        }
        
        /**
         * WebSocket event listener
         */
        private class WebSocketListener implements WebSocket.Listener {
            private final StringBuilder messageBuffer = new StringBuilder();
            
            @Override
            public void onOpen(WebSocket webSocket) {
                plugin.getLogger().info("✓ WebSocket CONNECTED for wallet: " + walletId);
                plugin.getDebugLogger().info("WebSocket tracking " + trackedInvoices.size() + " invoices");
                webSocket.request(1);
            }
            
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                messageBuffer.append(data);
                
                if (last) {
                    String message = messageBuffer.toString();
                    messageBuffer.setLength(0);
                    
                    // LOG ALL MESSAGES
                    plugin.getLogger().info("WebSocket message received: " + message);
                    plugin.getDebugLogger().info("Tracked invoices: " + trackedInvoices.keySet());
                    
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
                plugin.getDebugLogger().info("WebSocket closed for wallet " + walletId + 
                    ": " + statusCode + " - " + reason);
                
                if (!intentionallyClosed && !trackedInvoices.isEmpty()) {
                    scheduleReconnect();
                }
                
                return null;
            }
            
            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                plugin.getDebugLogger().error("WebSocket error for wallet " + walletId, error);
                
                if (!intentionallyClosed && !trackedInvoices.isEmpty()) {
                    scheduleReconnect();
                }
            }
            
            private void handleMessage(String message) {
                plugin.getLogger().info("Parsing WebSocket message: " + message);
                
                try {
                    JsonObject json = JsonParser.parseString(message).getAsJsonObject();
                    plugin.getLogger().info("Parsed JSON keys: " + json.keySet());
                    
                    // LNbits sends payment updates
                    if (json.has("payment_hash")) {
                        String paymentHash = json.get("payment_hash").getAsString();
                        plugin.getLogger().info("Payment hash from WebSocket: " + paymentHash);
                        
                        // Only process if we're tracking this invoice
                        if (trackedInvoices.containsKey(paymentHash)) {
                            plugin.getLogger().info("✓ Found tracked invoice: " + paymentHash);
                            
                            boolean paid = json.has("paid") && json.get("paid").getAsBoolean();
                            plugin.getLogger().info("Payment status - paid: " + paid);
                            
                            if (paid) {
                                plugin.getLogger().info("⚡ PAYMENT CONFIRMED via WebSocket: " + paymentHash);
                                handlePaidInvoice(paymentHash, json);
                                removeTrackedInvoice(paymentHash);
                            }
                        } else {
                            plugin.getLogger().info("⚠ Received payment for non-tracked invoice: " + paymentHash);
                        }
                    } else {
                        plugin.getLogger().info("WebSocket message has no payment_hash field");
                    }
                    
                } catch (JsonSyntaxException e) {
                    plugin.getDebugLogger().error("Invalid JSON from WebSocket: " + message, e);
                }
            }
        }
    }
    
    /**
     * Pending invoice data
     */
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