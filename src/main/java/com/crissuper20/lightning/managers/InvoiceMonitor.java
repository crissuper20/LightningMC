package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitors pending Lightning invoices and notifies players when they're paid.
 * 
 * This uses a polling approach:
 * - Checks each pending invoice every X seconds
 * - When paid, notifies the player
 * - Automatically removes expired/paid invoices
 */
public class InvoiceMonitor {
    
    private final LightningPlugin plugin;
    private final LNService lnService;
    private final WalletManager walletManager;
    
    // Track pending invoices
    private final Map<String, PendingInvoice> pendingInvoices;
    
    // Scheduler for periodic checks
    private final ScheduledExecutorService scheduler;
    
    // Configuration
    private static final long CHECK_INTERVAL_SECONDS = 3; // Check every 10 seconds
    private static final long INVOICE_EXPIRY_MINUTES = 60; // Remove after 1 hour
    
    public InvoiceMonitor(LightningPlugin plugin) {
        this.plugin = plugin;
        this.lnService = plugin.getLnService();
        this.walletManager = plugin.getWalletManager();
        this.pendingInvoices = new ConcurrentHashMap<>();
        
        // Create scheduler
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "InvoiceMonitor");
            t.setDaemon(true);
            return t;
        });
        
        // Start monitoring
        startMonitoring();
        
        plugin.getDebugLogger().info("InvoiceMonitor started (checking every " + CHECK_INTERVAL_SECONDS + "s)");
    }
    
    /**
     * Start the periodic monitoring task
     */
    private void startMonitoring() {
        scheduler.scheduleAtFixedRate(
            this::checkPendingInvoices,
            CHECK_INTERVAL_SECONDS, // Initial delay
            CHECK_INTERVAL_SECONDS, // Regular interval
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
     * Check all pending invoices
     */
    private void checkPendingInvoices() {
        if (pendingInvoices.isEmpty()) {
            return; // Nothing to check
        }
        
        plugin.getDebugLogger().debug("Checking " + pendingInvoices.size() + " pending invoices...");
        
        long now = System.currentTimeMillis();
        long expiryTime = INVOICE_EXPIRY_MINUTES * 60 * 1000;
        
        // Check each invoice
        pendingInvoices.forEach((paymentHash, pending) -> {
            // Remove if expired
            if (now - pending.createdTime > expiryTime) {
                plugin.getDebugLogger().debug("Invoice " + paymentHash + " expired, removing");
                pendingInvoices.remove(paymentHash);
                notifyExpired(pending);
                return;
            }
            
            // Check if paid
            checkInvoiceStatus(paymentHash, pending);
        });
    }
    
    /**
     * Check the status of a single invoice
     */
    private void checkInvoiceStatus(String paymentHash, PendingInvoice pending) {
        lnService.checkInvoiceAsync(paymentHash)
            .thenAccept(response -> {
                if (response.success && response.data) {
                    // Invoice is paid!
                    handlePaidInvoice(paymentHash, pending);
                }
                // If not paid yet, just keep waiting
            })
            .exceptionally(ex -> {
                plugin.getDebugLogger().error("Error checking invoice " + paymentHash, ex);
                return null;
            });
    }
    
    /**
     * Handle a paid invoice
     */
    private void handlePaidInvoice(String paymentHash, PendingInvoice pending) {
        plugin.getDebugLogger().info("Invoice PAID: " + paymentHash + " (" + pending.amountSats + " sats) for " + pending.playerName);
        
        // Remove from tracking
        pendingInvoices.remove(paymentHash);
        
        // Credit player's wallet
        Player player = Bukkit.getPlayer(pending.playerUuid);
        if (player != null && player.isOnline()) {
            // Player is online - notify and credit
            walletManager.addBalance(player, pending.amountSats);
            long newBalance = walletManager.getBalance(player);
            
            // Sync to main thread for Bukkit API calls
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage("§8§m");
                player.sendMessage("§a§lINVOICE PAID!");
                player.sendMessage("");
                player.sendMessage("§7Amount: §f" + pending.amountSats + " sats");
                if (pending.memo != null && !pending.memo.isEmpty()) {
                    player.sendMessage("§7Memo: §f" + pending.memo);
                }
                player.sendMessage("§7New balance: §f" + newBalance + " sats");
                player.sendMessage("§8§m");
                
                // Play sound effect (optional)
                player.playSound(player.getLocation(), "entity.experience_orb.pickup", 1.0f, 1.0f);
            });
            
        } else {
            // Player is offline - credit anyway, they'll see balance next login
            // Need to get Player object to credit
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player offlinePlayer = Bukkit.getPlayer(pending.playerUuid);
                if (offlinePlayer != null) {
                    walletManager.addBalance(offlinePlayer, pending.amountSats);
                    plugin.getDebugLogger().info("Credited " + pending.amountSats + " sats to offline player " + pending.playerName);
                }
            });
        }
    }
    
    /**
     * Notify player that their invoice expired
     */
    private void notifyExpired(PendingInvoice pending) {
        Player player = Bukkit.getPlayer(pending.playerUuid);
        if (player != null && player.isOnline()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage("§c⚠ Your invoice for " + pending.amountSats + " sats has expired.");
            });
        }
    }
    
    /**
     * Get count of pending invoices
     */
    public int getPendingCount() {
        return pendingInvoices.size();
    }
    
    /**
     * Get pending invoices for a specific player
     */
    public int getPendingCountForPlayer(Player player) {
        return (int) pendingInvoices.values().stream()
            .filter(p -> p.playerUuid.equals(player.getUniqueId()))
            .count();
    }
    
    /**
     * Shutdown the monitor
     */
    public void shutdown() {
        plugin.getDebugLogger().info("Shutting down InvoiceMonitor...");
        
        try {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
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
    private static class PendingInvoice {
        final UUID playerUuid;
        final String playerName;
        final String paymentHash;
        final long amountSats;
        final String memo;
        final long createdTime;
        
        PendingInvoice(UUID playerUuid, String playerName, String paymentHash, 
                      long amountSats, String memo, long createdTime) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.paymentHash = paymentHash;
            this.amountSats = amountSats;
            this.memo = memo;
            this.createdTime = createdTime;
        }
    }
}
