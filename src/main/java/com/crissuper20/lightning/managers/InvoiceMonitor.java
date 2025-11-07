package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Monitors pending Lightning invoices and notifies players when they're paid.
 * 
 * - Proper offline player handling
 * - Thread-safe invoice processing
 * - Exponential backoff for old invoices
 * - Configurable intervals
 */
public class InvoiceMonitor {
    
    private final LightningPlugin plugin;
    private final LNService lnService;
    private final WalletManager walletManager;
    
    // Track pending invoices with thread-safe access
    private final ConcurrentHashMap<String, PendingInvoice> pendingInvoices;
    
    // Track invoices currently being processed (prevents double-processing)
    private final ConcurrentHashMap<String, CompletableFuture<Void>> processingInvoices;
    
    // Scheduler for periodic checks
    private final ScheduledExecutorService scheduler;
    
    // Metrics
    private final AtomicInteger totalProcessed = new AtomicInteger(0);
    private final AtomicInteger totalExpired = new AtomicInteger(0);
    
    // Configuration (loaded from config.yml)
    private final long invoiceExpiryMinutes;
    private final long fastCheckIntervalSeconds; // For recent invoices
    private final long slowCheckIntervalSeconds; // For old invoices
    private final long slowCheckThresholdMinutes; // When to slow down
    
    public InvoiceMonitor(LightningPlugin plugin) {
        this.plugin = plugin;
        this.lnService = plugin.getLnService();
        this.walletManager = plugin.getWalletManager();
        this.pendingInvoices = new ConcurrentHashMap<>();
        this.processingInvoices = new ConcurrentHashMap<>();
        
        // Load config with defaults
        this.invoiceExpiryMinutes = plugin.getConfig().getLong("invoice_monitor.expiry_minutes", 60);
        this.fastCheckIntervalSeconds = plugin.getConfig().getLong("invoice_monitor.fast_check_seconds", 3);
        this.slowCheckIntervalSeconds = plugin.getConfig().getLong("invoice_monitor.slow_check_seconds", 15);
        this.slowCheckThresholdMinutes = plugin.getConfig().getLong("invoice_monitor.slow_check_threshold_minutes", 10);
        
        // Create scheduler with better thread naming
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "InvoiceMonitor-Worker");
            t.setDaemon(true);
            return t;
        });
        
        // Start monitoring
        startMonitoring();
        
        plugin.getDebugLogger().info("InvoiceMonitor started:");
        plugin.getDebugLogger().info("  Fast check: every " + fastCheckIntervalSeconds + "s");
        plugin.getDebugLogger().info("  Slow check: every " + slowCheckIntervalSeconds + "s (after " + slowCheckThresholdMinutes + "m)");
        plugin.getDebugLogger().info("  Expiry: " + invoiceExpiryMinutes + " minutes");
    }
    
    /**
     * Start the periodic monitoring tasks
     */
    private void startMonitoring() {
        // Fast checker for recent invoices (every few seconds)
        scheduler.scheduleAtFixedRate(
            () -> checkPendingInvoices(false),
            fastCheckIntervalSeconds,
            fastCheckIntervalSeconds,
            TimeUnit.SECONDS
        );
        
        // Slow checker for old invoices (less frequent)
        scheduler.scheduleAtFixedRate(
            () -> checkPendingInvoices(true),
            slowCheckIntervalSeconds,
            slowCheckIntervalSeconds,
            TimeUnit.SECONDS
        );
    }
    
    /**
     * Register an invoice to be monitored
     */
    public void trackInvoice(Player player, String paymentHash, long amountSats, String memo) {
        PendingInvoice pending = new PendingInvoice(
            player.getUniqueId(),
            player.getName(),
            paymentHash,
            amountSats,
            memo,
            System.currentTimeMillis()
        );
        
        pendingInvoices.put(paymentHash, pending);
        
        plugin.getDebugLogger().debug("Now tracking invoice " + paymentHash + " for " + player.getName());
        plugin.getDebugLogger().info("Monitoring " + pendingInvoices.size() + " pending invoices");
    }
    
    /**
     * Stop tracking an invoice (if player cancels, etc.)
     */
    public void stopTracking(String paymentHash) {
        PendingInvoice removed = pendingInvoices.remove(paymentHash);
        if (removed != null) {
            plugin.getDebugLogger().debug("Stopped tracking invoice " + paymentHash);
        }
    }
    
    /**
     * Check all pending invoices with exponential backoff
     * 
     * @param slowCheckOnly If true, only check old invoices (exponential backoff)
     */
    private void checkPendingInvoices(boolean slowCheckOnly) {
        if (pendingInvoices.isEmpty()) {
            return;
        }
        
        long now = System.currentTimeMillis();
        long expiryTime = invoiceExpiryMinutes * 60 * 1000;
        long slowCheckThreshold = slowCheckThresholdMinutes * 60 * 1000;
        
        int checkedCount = 0;
        
        // Check each invoice
        for (Map.Entry<String, PendingInvoice> entry : pendingInvoices.entrySet()) {
            String paymentHash = entry.getKey();
            PendingInvoice pending = entry.getValue();
            long age = now - pending.createdTime;
            
            // Remove if expired
            if (age > expiryTime) {
                if (pendingInvoices.remove(paymentHash) != null) {
                    totalExpired.incrementAndGet();
                    plugin.getDebugLogger().debug("Invoice " + paymentHash + " expired after " + (age / 60000) + " minutes");
                    notifyExpired(pending);
                }
                continue;
            }
            
            // Exponential backoff: only check old invoices in slow mode
            boolean isOld = age > slowCheckThreshold;
            if (slowCheckOnly && !isOld) {
                continue; // Skip recent invoices in slow check
            }
            if (!slowCheckOnly && isOld) {
                continue; // Skip old invoices in fast check
            }
            
            // Check if already being processed
            if (processingInvoices.containsKey(paymentHash)) {
                continue;
            }
            
            checkedCount++;
            checkInvoiceStatus(paymentHash, pending);
        }
        
        if (checkedCount > 0) {
            plugin.getDebugLogger().debug("Checked " + checkedCount + " invoices (" + 
                (slowCheckOnly ? "slow" : "fast") + " mode)");
        }
    }
    
    /**
     * Check the status of a single invoice (thread-safe)
     */
    private void checkInvoiceStatus(String paymentHash, PendingInvoice pending) {
        // Mark as processing to prevent concurrent checks
        CompletableFuture<Void> processingFuture = new CompletableFuture<>();
        CompletableFuture<Void> existingFuture = processingInvoices.putIfAbsent(paymentHash, processingFuture);
        
        if (existingFuture != null) {
            // Already being processed by another thread
            return;
        }
        
        lnService.checkInvoiceAsync(paymentHash)
            .thenAccept(response -> {
                try {
                    if (response.success && response.data) {
                        // Invoice is paid! Remove FIRST to prevent double-processing
                        if (pendingInvoices.remove(paymentHash) != null) {
                            handlePaidInvoice(paymentHash, pending);
                        }
                    }
                } finally {
                    // Always clean up processing marker
                    processingInvoices.remove(paymentHash);
                    processingFuture.complete(null);
                }
            })
            .exceptionally(ex -> {
                plugin.getDebugLogger().error("Error checking invoice " + paymentHash, ex);
                processingInvoices.remove(paymentHash);
                processingFuture.completeExceptionally(ex);
                return null;
            });
    }
    
    /**
     * Handle a paid invoice
     * 
     * CRITICAL FIX: Does NOT call addBalance() anymore!
     * Instead, fetches fresh balance from LNbits which already includes the payment.
     */
    private void handlePaidInvoice(String paymentHash, PendingInvoice pending) {
        totalProcessed.incrementAndGet();
        plugin.getDebugLogger().info("Invoice PAID: " + paymentHash + " (" + pending.amountSats + " sats) for " + pending.playerName);
        
        // Get player (online or offline)
        Player player = Bukkit.getPlayer(pending.playerUuid);
        
        if (player != null && player.isOnline()) {
            // Player is online - fetch fresh balance and notify
            walletManager.fetchBalanceFromLNbits(player)
                .thenAccept(newBalance -> {
                    // Sync to main thread for Bukkit API calls
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.sendMessage("§a§l INVOICE PAID!");
                        player.sendMessage("");
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
                    plugin.getDebugLogger().error("Failed to fetch balance after payment for " + pending.playerName, ex);
                    return null;
                });
        } else {
            // Player is offline - schedule balance sync for next login
            plugin.getDebugLogger().info("Player " + pending.playerName + ", balance will sync on next login");
            
            // Store notification for next login
            scheduleOfflineNotification(pending);
        }
    }
    
    /**
     * Schedule a notification for when player logs back in
     */
    private void scheduleOfflineNotification(PendingInvoice pending) {
        // Store in plugin data folder
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getConfig().set("pending_notifications." + pending.playerUuid + "." + System.currentTimeMillis(), 
                "Invoice paid: " + pending.amountSats + " sats" + 
                (pending.memo != null && !pending.memo.isEmpty() ? " - " + pending.memo : ""));
            plugin.saveConfig();
        });
    }
    
    /**
     * Notify player that their invoice expired
     */
    private void notifyExpired(PendingInvoice pending) {
        Player player = Bukkit.getPlayer(pending.playerUuid);
        if (player != null && player.isOnline()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage("§c Your invoice for " + pending.amountSats + " sats has expired.");
                player.sendMessage("§7Please create a new invoice if you still want to.");
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
    
    public int getPendingCountForPlayer(Player player) {
        return (int) pendingInvoices.values().stream()
            .filter(p -> p.playerUuid.equals(player.getUniqueId()))
            .count();
    }
    
    public Map<String, PendingInvoice> getAllPendingInvoices() {
        return new ConcurrentHashMap<>(pendingInvoices);
    }
    
    /**
     * Shutdown the monitor gracefully
     */
    public void shutdown() {
        plugin.getDebugLogger().info("Shutting down InvoiceMonitor...");
        plugin.getDebugLogger().info("  Processed: " + totalProcessed.get() + " invoices");
        plugin.getDebugLogger().info("  Expired: " + totalExpired.get() + " invoices");
        plugin.getDebugLogger().info("  Pending: " + pendingInvoices.size() + " invoices");
        
        try {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getDebugLogger().warning("Forcing InvoiceMonitor shutdown...");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        
        plugin.getDebugLogger().info("InvoiceMonitor stopped");
    }
    
    /**
     * Data class for pending invoices
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