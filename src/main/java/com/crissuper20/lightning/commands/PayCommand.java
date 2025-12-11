package com.crissuper20.lightning.commands;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.managers.LNService;
import com.crissuper20.lightning.managers.WalletManager;
import com.crissuper20.lightning.util.QRMapRenderer;
import com.crissuper20.lightning.util.RateLimiter;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.regex.Pattern;

/**
 * /pay command - Pay Lightning invoices using QR maps
 * 
 * Usage:
 *   /pay - Process payment from QR map in main hand
 */
public class PayCommand implements CommandExecutor {

    private final LightningPlugin plugin;
    private final LNService lnService;
    private final WalletManager walletManager;
    private final RateLimiter rateLimiter;
    
    // Regex patterns for invoice validation
    private static final Pattern INVOICE_PATTERN = Pattern.compile(
        "^(lightning:)?(lnbc|lntb|lnbcrt)[0-9]+[a-z0-9]+$",
        Pattern.CASE_INSENSITIVE
    );

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

        // Check wallet exists
        if (!walletManager.hasWallet(player)) {
            player.sendMessage("§cYou don't have a wallet yet!");
            player.sendMessage("§7Create an invoice first with §e/invoice §7to setup your wallet.");
            return true;
        }

        // Get item in main hand
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        
        if (heldItem == null || heldItem.getType() != Material.FILLED_MAP) {
            player.sendMessage("§c✗ No payment QR map found!");
            player.sendMessage("");
            player.sendMessage("§eHow to pay:");
            player.sendMessage("§71. Get a Lightning invoice QR code");
            player.sendMessage("§72. Hold the QR map in your main hand");
            player.sendMessage("§73. Type §f/pay");
            player.sendMessage("");
            player.sendMessage("§7The QR map must be a Minecraft map item");
            player.sendMessage("§7with a Lightning invoice QR code.");
            return true;
        }

        // Check if this is a Lightning map
        if (!QRMapRenderer.isLightningMap(plugin, heldItem)) {
            player.sendMessage("§c✗ This map doesn't contain Lightning data!");
            player.sendMessage("§7Make sure you're holding a Lightning invoice QR map.");
            return true;
        }

        // Check map type
        String mapType = QRMapRenderer.getMapType(plugin, heldItem);
        if (!"payment".equals(mapType)) {
            player.sendMessage("§c✗ This is a " + mapType + " QR map, not a payment invoice!");
            player.sendMessage("§7You need a Lightning payment invoice QR map.");
            return true;
        }

        // Rate limit check
        RateLimiter.RateLimitResult rateCheck = rateLimiter.check(player.getUniqueId());
        if (!rateCheck.allowed) {
            player.sendMessage("§cYou're sending payments too quickly!");
            player.sendMessage("§7" + rateCheck.getRetryMessage());
            return true;
        }

        // Read invoice from map NBT
        String bolt11 = QRMapRenderer.readInvoiceFromMap(plugin, heldItem);
        
        if (bolt11 == null || bolt11.isEmpty()) {
            player.sendMessage("§c✗ Failed to read invoice from map!");
            player.sendMessage("§7This map may be corrupted or invalid.");
            return true;
        }

        // Clean and validate invoice
        final String finalBolt11 = cleanInvoice(bolt11);
        
        // Debug logging
        plugin.getDebugLogger().debug("=== PAY COMMAND DEBUG ===");
        plugin.getDebugLogger().debug("Player: " + player.getName());
        plugin.getDebugLogger().debug("Invoice from map: " + finalBolt11);
        plugin.getDebugLogger().debug("Invoice length: " + finalBolt11.length());
        
        // Validate invoice format
        if (!validateInvoice(finalBolt11)) {
            player.sendMessage("§c✗ Invalid Lightning invoice format!");
            player.sendMessage("§7Received prefix: " + finalBolt11.substring(0, Math.min(15, finalBolt11.length())));
            
            plugin.getDebugLogger().warn("Invoice validation failed for " + player.getName());
            return true;
        }

        // Show invoice preview
        player.sendMessage("§eReading invoice from QR map...");
        player.sendMessage("§7Invoice: §f" + finalBolt11.substring(0, Math.min(30, finalBolt11.length())) + "...");
        
        // Check balance before attempting payment
        player.sendMessage("§eChecking balance...");
        
        walletManager.getBalance(player).thenAccept(balance -> {
            if (balance <= 0) {
                plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
                    p.sendMessage("§c✗ Insufficient balance!");
                    p.sendMessage("§7Your balance: 0 sats");
                    p.sendMessage("§7Create an invoice with §e/invoice §7to deposit funds.");
                });
                return;
            }
            
            // Proceed with payment - remove map now, restore on failure
            plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
                p.sendMessage("§eProcessing payment...");
                p.sendMessage("§7Current balance: " + formatSats(balance));
                
                // Remove the map and store it - will be restored on failure
                ItemStack mapToConsume = p.getInventory().getItemInMainHand().clone();
                p.getInventory().setItemInMainHand(null);
                
                sendPayment(p, finalBolt11, mapToConsume);
            });
        }).exceptionally(ex -> {
            plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
                p.sendMessage("§cFailed to check balance. Please try again.");
                plugin.getDebugLogger().error("Balance check failed", ex);
            });
            return null;
        });

        return true;
    }

    /**
     * Clean invoice string - remove common formatting issues
     */
    private String cleanInvoice(String raw) {
        return raw
            .trim()
            .replaceAll("\\s+", "")           // Remove all whitespace
            .replaceAll("lightning:", "")      // Remove lightning: prefix
            .replaceAll("[\\r\\n]+", "")       // Remove line breaks
            .toLowerCase();                     // Normalize case
    }

    /**
     * Validate invoice format
     */
    private boolean validateInvoice(String invoice) {
        if (invoice == null || invoice.isEmpty()) {
            return false;
        }
        
        // Check length (minimum ~100 chars for a valid invoice)
        if (invoice.length() < 100) {
            plugin.getDebugLogger().debug("Invoice too short: " + invoice.length() + " chars");
            return false;
        }
        
        // Check pattern
        if (!INVOICE_PATTERN.matcher(invoice).matches()) {
            plugin.getDebugLogger().debug("Invoice pattern mismatch");
            return false;
        }
        
        return true;
    }

    /**
     * Send the payment via LNService
     * @param player The player sending the payment
     * @param bolt11 The Lightning invoice to pay
     * @param mapToConsume The QR map to remove on success, or restore on failure
     */
    private void sendPayment(Player player, String bolt11, ItemStack mapToConsume) {
        try (var timer = plugin.getMetrics().startTiming("payment_send")) {
            
            plugin.getMetrics().recordPaymentAttempt(player.getUniqueId());
            
            // Extra debug logging before API call
            plugin.getDebugLogger().debug("=== SENDING PAYMENT ===");
            plugin.getDebugLogger().debug("Final bolt11: " + bolt11);
            plugin.getDebugLogger().debug("Bolt11 length: " + bolt11.length());
            plugin.getDebugLogger().debug("Player has wallet: " + walletManager.hasWallet(player));
            
            lnService.payInvoiceForPlayer(player, bolt11)
                .thenAccept(response -> {
                    plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
                        handlePaymentResponse(p, bolt11, response, mapToConsume);
                    });
                })
                .exceptionally(ex -> {
                    plugin.getMetrics().recordApiError();
                    
                    plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
                        p.sendMessage("§c✗ Payment failed!");
                        p.sendMessage("§7Error: " + ex.getMessage());
                        
                        // Give the map back to the player
                        restoreMap(p, mapToConsume);
                        
                        // Detailed error logging
                        plugin.getLogger().severe("=== PAYMENT EXCEPTION ===");
                        plugin.getLogger().severe("Player: " + p.getName());
                        plugin.getLogger().severe("Invoice: " + bolt11.substring(0, Math.min(50, bolt11.length())));
                        plugin.getLogger().severe("Exception: " + ex.getClass().getName());
                        plugin.getLogger().severe("Message: " + ex.getMessage());
                        plugin.getDebugLogger().error("Full stack trace:", ex);
                    });
                    return null;
                });
        }
    }

    /**
     * Handle payment response from LNbits
     * @param player The player who sent the payment
     * @param bolt11 The Lightning invoice that was paid
     * @param response The response from LNbits
     * @param mapToConsume The QR map to consume on success
     */
    private void handlePaymentResponse(Player player, String bolt11, LNService.LNResponse<JsonObject> response, ItemStack mapToConsume) {
        plugin.getDebugLogger().debug("=== PAYMENT RESPONSE ===");
        plugin.getDebugLogger().debug("Success: " + response.success);
        plugin.getDebugLogger().debug("Status Code: " + response.statusCode);
        plugin.getDebugLogger().debug("Error: " + response.error);
        if (response.data != null) {
            plugin.getDebugLogger().debug("Response data: " + response.data.toString());
        }
        
        if (!response.success) {
            // Payment failed - give the map back
            restoreMap(player, mapToConsume);
            handlePaymentError(player, response);
            return;
        }

        // Payment successful - map already removed
        JsonObject paymentData = response.data;
        
        // Extract payment details
        String paymentHash = paymentData.has("payment_hash") ? 
            paymentData.get("payment_hash").getAsString() : "unknown";
        
        long amountPaid = 0;
        if (paymentData.has("amount")) {
            amountPaid = Math.abs(paymentData.get("amount").getAsLong()) / 1000; // Convert msats to sats
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

        try {
            player.playSound(player.getLocation(), "entity.experience_orb.pickup", 1.0f, 1.5f);
        } catch (Exception ignored) {}
        
        // Fetch updated balance
        walletManager.fetchBalanceFromLNbits(player)
            .thenAccept(newBalance -> {
                plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
                    p.sendMessage("§7New balance: §a" + formatSats(newBalance));
                });
            })
            .exceptionally(ex -> {
                plugin.getDebugLogger().error("Failed to fetch balance after payment", ex);
                return null;
            });
        
        plugin.getLogger().info(" Payment completed: " + player.getName() + 
            " → " + amountPaid + " sats (hash: " + paymentHash.substring(0, 16) + "...)");
    }

    /**
     * Handle payment errors with detailed messages
     */
    private void handlePaymentError(Player player, LNService.LNResponse<JsonObject> response) {
        String errorMsg = response.error != null ? response.error : "Unknown error";
        
        plugin.getLogger().severe("=== PAYMENT ERROR ===");
        plugin.getLogger().severe("Player: " + player.getName());
        plugin.getLogger().severe("Status: " + response.statusCode);
        plugin.getLogger().severe("Error: " + errorMsg);
        
        player.sendMessage("§c Payment failed!");
        
        if (errorMsg.toLowerCase().contains("bolt11") || 
            errorMsg.toLowerCase().contains("decode") ||
            errorMsg.toLowerCase().contains("invalid invoice")) {
            
            player.sendMessage("§7Invalid invoice format");
            player.sendMessage("§7Please check the QR code and try again");
            
        } else if (errorMsg.toLowerCase().contains("insufficient") || 
                   errorMsg.toLowerCase().contains("balance")) {
            
            player.sendMessage("§7Insufficient balance");
            walletManager.getBalance(player).thenAccept(balance -> {
                plugin.getScheduler().runTaskForPlayer(player.getUniqueId(), p -> {
                    p.sendMessage("§7Your balance: " + formatSats(balance));
                    p.sendMessage("§7Deposit more with §e/invoice");
                });
            });
            
        } else if (errorMsg.toLowerCase().contains("route") || 
                   errorMsg.toLowerCase().contains("path") ||
                   errorMsg.toLowerCase().contains("no path")) {
            
            player.sendMessage("§7No payment route found");
            player.sendMessage("§7The recipient may be offline or unreachable");
            player.sendMessage("§7Try again in a moment");
            
        } else if (errorMsg.toLowerCase().contains("timeout")) {
            
            player.sendMessage("§7Payment timed out");
            player.sendMessage("§7The Lightning network may be congested");
            player.sendMessage("§7Try again in a moment");
            
        } else if (errorMsg.toLowerCase().contains("expired")) {
            
            player.sendMessage("§7Invoice has expired");
            player.sendMessage("§7Request a new invoice from the recipient");
            
        } else {
            // Generic error
            player.sendMessage("§7" + errorMsg);
            player.sendMessage("");
            player.sendMessage("§7If this persists, contact an admin");
            player.sendMessage("§7Error code: " + response.statusCode);
        }
        
        plugin.getMetrics().recordPaymentFailure(player.getUniqueId(), errorMsg);
    }

    /**
     * Restore the QR map to the player after a failed payment.
     * Tries to put it back in the main hand, or adds to inventory if hand is occupied.
     */
    private void restoreMap(Player player, ItemStack map) {
        if (map == null) return;
        
        ItemStack currentHand = player.getInventory().getItemInMainHand();
        if (currentHand == null || currentHand.getType() == Material.AIR) {
            // Main hand is empty, put the map back there
            player.getInventory().setItemInMainHand(map);
            player.sendMessage("§7QR map returned to your hand.");
        } else {
            // Main hand is occupied, add to inventory
            java.util.HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(map);
            if (overflow.isEmpty()) {
                player.sendMessage("§7QR map returned to your inventory.");
            } else {
                // Inventory full, drop at player's feet
                player.getWorld().dropItemNaturally(player.getLocation(), map);
                player.sendMessage("§7QR map dropped at your feet (inventory full).");
            }
        }
    }

    private String formatSats(long sats) {
        if (sats >= 100_000_000) {
            return String.format("%.8f BTC", sats / 100_000_000.0);
        }
        return String.format("%,d sats", sats);
    }
}