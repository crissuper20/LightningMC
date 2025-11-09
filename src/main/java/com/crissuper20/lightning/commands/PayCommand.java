package com.crissuper20.lightning.commands;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.managers.LNService;
import com.crissuper20.lightning.managers.WalletManager;
import com.crissuper20.lightning.util.RateLimiter;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /pay command - Pay Lightning invoices
 * 
 * Enhanced with:
 * - Invoice validation
 * - Network checking
 * - Better error messages
 * - Comprehensive debugging
 */
public class PayCommand implements CommandExecutor {

    private final LightningPlugin plugin;
    private final LNService lnService;
    private final WalletManager walletManager;
    private final RateLimiter rateLimiter;

    public PayCommand(LightningPlugin plugin) {
        this.plugin = plugin;
        this.lnService = plugin.getLnService();
        this.walletManager = plugin.getWalletManager();
        
        int limit = plugin.getConfig().getInt("rate_limits.payments_per_minute", 10);
        this.rateLimiter = RateLimiter.perMinute(limit);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        // Permission check
        if (!player.hasPermission("lightning.pay")) {
            player.sendMessage("§cYou don't have permission to send payments.");
            return true;
        }

        // Rate limit check
        RateLimiter.RateLimitResult rateCheck = rateLimiter.check(player.getUniqueId());
        if (!rateCheck.allowed) {
            player.sendMessage("§cYou're sending payments too quickly!");
            player.sendMessage("§7" + rateCheck.getRetryMessage());
            return true;
        }

        // Validate arguments
        if (args.length < 1) {
            player.sendMessage("§cUsage: /pay <lightning_invoice>");
            player.sendMessage("§7Example: /pay lntb1000n1...");
            player.sendMessage("§7Paste the full Lightning invoice (bolt11)");
            return true;
        }

        // Get and clean invoice
        String bolt11 = args[0].trim();
        
        // Log for debugging
        plugin.getLogger().info("=== Payment Attempt ===");
        plugin.getLogger().info("Player: " + player.getName());
        plugin.getLogger().info("Invoice length: " + bolt11.length());
        plugin.getLogger().info("Invoice prefix: " + bolt11.substring(0, Math.min(15, bolt11.length())));
        
        // Validate invoice format
        String invoiceLower = bolt11.toLowerCase();
        if (!invoiceLower.startsWith("lnbc") && 
            !invoiceLower.startsWith("lntb") && 
            !invoiceLower.startsWith("lnbcrt")) {
            player.sendMessage("§cInvalid Lightning invoice format!");
            player.sendMessage("§7Expected: lnbc... (mainnet) or lntb... (testnet)");
            player.sendMessage("§7Received: " + bolt11.substring(0, Math.min(15, bolt11.length())));
            plugin.getLogger().warning("Invalid invoice format from " + player.getName());
            return true;
        }
        // Check wallet exists
        if (!walletManager.hasWallet(player)) {
            player.sendMessage("§cYou don't have a wallet yet!");
            player.sendMessage("§7Create an invoice first with §e/invoice §7to setup your wallet.");
            return true;
        }

        // Decode invoice to get amount (optional, for display)
        player.sendMessage("§eProcessing payment...");
        plugin.getLogger().info("Payment validation passed, sending to LNbits...");

        // Send payment
        sendPayment(player, bolt11);

        return true;
    }


    /**
     * Send the payment via LNService
     */
    private void sendPayment(Player player, String bolt11) {
        try (var timer = plugin.getMetrics().startTiming("payment_send")) {
            
            plugin.getMetrics().recordPaymentAttempt(player.getUniqueId());
            
            lnService.payInvoiceForPlayer(player, bolt11)
                .thenAccept(response -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        handlePaymentResponse(player, bolt11, response);
                    });
                })
                .exceptionally(ex -> {
                    plugin.getMetrics().recordApiError();
                    
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.sendMessage("§c✗ Payment failed!");
                        player.sendMessage("§7Error: " + ex.getMessage());
                        plugin.getLogger().severe("Payment exception for " + player.getName() + ": " + ex.getMessage());
                        plugin.getDebugLogger().error("Payment error details", ex);
                    });
                    return null;
                });
        }
    }

    /**
     * Handle payment response from LNbits
     */
    private void handlePaymentResponse(Player player, String bolt11, LNService.LNResponse<JsonObject> response) {
        plugin.getLogger().info("=== Payment Response ===");
        plugin.getLogger().info("Success: " + response.success);
        plugin.getLogger().info("Status Code: " + response.statusCode);
        
        if (!response.success) {
            // Log full error details
            plugin.getLogger().severe("Payment failed for " + player.getName());
            plugin.getLogger().severe("Error: " + response.error);
            plugin.getLogger().severe("Status: " + response.statusCode);
            
            // Parse error message
            String errorMsg = response.error;
            if (errorMsg == null) {
                errorMsg = "Unknown error (status " + response.statusCode + ")";
            }
            
            // User-friendly error messages
            player.sendMessage("§c✗ Payment failed!");
            
            if (errorMsg.contains("Bolt11 decoding failed")) {
                player.sendMessage("§7Invalid invoice format or corrupted invoice");
                player.sendMessage("§7Please check:");
                player.sendMessage("§7  • Invoice is complete (no missing characters)");
                player.sendMessage("§7  • Invoice is for the correct network (testnet)");
                player.sendMessage("§7  • Invoice is not expired");
            } else if (errorMsg.contains("insufficient balance") || errorMsg.contains("not enough")) {
                player.sendMessage("§7Insufficient balance");
                walletManager.getBalance(player).thenAccept(balance -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.sendMessage("§7Your balance: " + formatSats(balance));
                    });
                });
            } else if (errorMsg.contains("route") || errorMsg.contains("path")) {
                player.sendMessage("§7No payment route found");
                player.sendMessage("§7The recipient may be offline or unreachable");
            } else if (errorMsg.contains("timeout")) {
                player.sendMessage("§7Payment timed out");
                player.sendMessage("§7Try again in a moment");
            } else {
                player.sendMessage("§7" + errorMsg);
            }
            
            plugin.getMetrics().recordError("payment_failed", errorMsg);
            return;
        }

        // Payment successful
        JsonObject paymentData = response.data;
        plugin.getLogger().info("Payment successful!");
        plugin.getLogger().info("Response data: " + paymentData);
        
        // Extract payment details
        String paymentHash = paymentData.has("payment_hash") ? 
            paymentData.get("payment_hash").getAsString() : "unknown";
        
        long amountPaid = 0;
        if (paymentData.has("amount")) {
            amountPaid = Math.abs(paymentData.get("amount").getAsLong());
        }
        
        plugin.getMetrics().recordPaymentSuccess(player.getUniqueId(), amountPaid);
        
        // Notify player
        player.sendMessage("§a§l✓ PAYMENT SENT!");
        player.sendMessage("");
        if (amountPaid > 0) {
            player.sendMessage("§7Amount: §f" + formatSats(amountPaid));
        }
        player.sendMessage("§7Payment Hash: §f" + paymentHash.substring(0, 16) + "...");
        player.sendMessage("");
        player.sendMessage("§aPayment completed successfully!");
        
        // Fetch updated balance
        walletManager.fetchBalanceFromLNbits(player)
            .thenAccept(newBalance -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§7New balance: §a" + formatSats(newBalance));
                });
            })
            .exceptionally(ex -> {
                plugin.getDebugLogger().error("Failed to fetch balance after payment", ex);
                return null;
            });
        
        plugin.getLogger().info("Payment completed successfully for " + player.getName() + 
            " (hash: " + paymentHash + ")");
    }

    private String formatSats(long sats) {
        if (sats >= 100_000_000) {
            return String.format("%.8f BTC", sats / 100_000_000.0);
        }
        return String.format("%,d sats", sats);
    }
}