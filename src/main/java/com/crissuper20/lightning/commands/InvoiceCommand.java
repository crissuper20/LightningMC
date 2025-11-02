package com.crissuper20.lightning.commands;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.clients.LNClient;
import com.crissuper20.lightning.clients.LNClient.Invoice;
import com.crissuper20.lightning.util.QRMapGenerator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

public class InvoiceCommand implements CommandExecutor {
    private final LightningPlugin plugin;

    public InvoiceCommand(LightningPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // DEBUG: Log that command was triggered
        plugin.getDebugLogger().debug("/invoice command triggered");
        plugin.getDebugLogger().debug("Sender: " + sender.getName());
        plugin.getDebugLogger().debug("Label: " + label);
        plugin.getDebugLogger().debug("Args length: " + args.length);
        plugin.getDebugLogger().debug("Args: " + String.join(", ", args));
        
        if (!(sender instanceof Player)) {
            plugin.getDebugLogger().debug("Sender is not a player, rejecting");
            sender.sendMessage(LightningPlugin.formatError("This command can only be used by players."));
            return true;
        }

        Player player = (Player) sender;

        // Validate arguments (amount and memo)
        if (args.length < 2) {
            plugin.getDebugLogger().warning("Not enough arguments provided: " + args.length + " (need at least 2)");
            player.sendMessage(LightningPlugin.formatError("Usage: /invoice <amount> <memo>"));
            return true;
        }
        
        plugin.getDebugLogger().debug("Arguments validated, proceeding with invoice creation");

        // Ensure amount is a positive integer with no decimals or special characters
        String amountStr = args[0].trim();
        if (!amountStr.matches("\\d+")) {
            player.sendMessage(LightningPlugin.formatError("Invalid amount specified. Use a positive integer."));
            return true;
        }

        long amount;
        try {
            amount = Long.parseLong(amountStr);
            if (amount <= 0) {
                player.sendMessage(LightningPlugin.formatError("Amount must be greater than zero."));
                return true;
            }
        } catch (NumberFormatException e) {
            player.sendMessage(LightningPlugin.formatError("Invalid amount specified."));
            return true;
        }

        // Allow memo to be multiple words
        String memo = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        // Create invoice asynchronously
        CompletableFuture<LNClient.LNResponse<Invoice>> futureInvoice = 
            plugin.getLnService().createInvoiceAsync(amount, memo);

        player.sendMessage(LightningPlugin.formatMessage("§7Creating invoice..."));

        futureInvoice.thenAccept(response -> {
            // All Bukkit interactions must run on the main server thread guh
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (response.success) {
                    Invoice invoice = response.data;
                    String paymentRequest = invoice.getBolt11();
                    String paymentHash = invoice.getPaymentHash();
                    
                    player.sendMessage(LightningPlugin.formatMessage("§aInvoice created successfully!"));
                    plugin.getDebugLogger().debug("Invoice created for " + player.getName() + 
                        ": " + amount + " sats, hash=" + paymentHash);

                    // ⭐ REGISTER INVOICE WITH MONITOR - THIS WAS MISSING!
                    plugin.getInvoiceMonitor().trackInvoice(player, paymentHash, amount, memo);

                    // Always show the invoice text in chat
                    player.sendMessage(LightningPlugin.formatMessage("§7Invoice: §f" + paymentRequest));

                    // Try to generate and give QR code map using QRMapGenerator
                    // This will try QRMapRenderer first, then fall back to QRMap shim
                    try {
                        // Attempt 1: Try to get an ItemStack directly
                        ItemStack mapItem = QRMapGenerator.generate(player, paymentRequest);
                        
                        if (mapItem != null) {
                            // Success - ItemStack was returned and added to inventory
                            player.sendMessage(LightningPlugin.formatMessage("§aQR code map created and given to you."));
                            plugin.getDebugLogger().debug("QR map successfully created via ItemStack return");
                        } else {
                            // Attempt 2: Try side-effect giving (giveMap adds to inventory directly)
                            boolean success = QRMapGenerator.giveMap(player, paymentRequest);
                            
                            if (success) {
                                player.sendMessage(LightningPlugin.formatMessage("§aQR code map created and given to you."));
                                plugin.getDebugLogger().debug("QR map successfully created via giveMap");
                            } else {
                                // Both methods failed - warn but invoice text is already shown
                                player.sendMessage(LightningPlugin.formatError("§eQR generation failed (invoice shown above)."));
                                plugin.getDebugLogger().warning("QR generation failed for invoice: " + paymentRequest);
                            }
                        }
                    } catch (Exception e) {
                        // Handle any unexpected errors during QR generation
                        player.sendMessage(LightningPlugin.formatError("§eQR generation error (invoice shown above)."));
                        plugin.getDebugLogger().error("Unexpected error during QR generation for " + player.getName(), e);
                    }
                    
                } else {
                    player.sendMessage(LightningPlugin.formatError("Failed to create invoice: " + response.error));
                    plugin.getDebugLogger().warning("Invoice creation failed for " + player.getName() + 
                        ": " + response.error + " (status: " + response.statusCode + ")");
                }
            });
        }).exceptionally(e -> {
            // Log and inform player on main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.sendMessage(LightningPlugin.formatError("An error occurred while creating the invoice."));
            });
            plugin.getDebugLogger().error("Error creating invoice for " + player.getName(), e);
            return null;
        });

        return true;
    }
}