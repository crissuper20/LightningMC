package com.crissuper20.lightning.commands;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.managers.WebSocketInvoiceMonitor;
import com.crissuper20.lightning.managers.LNService;
import com.crissuper20.lightning.managers.WalletManager;
import com.crissuper20.lightning.util.RateLimiter;
import com.crissuper20.lightning.util.QRMapGenerator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /invoice command - Create Lightning invoices
 * 
 * Enhanced with:
 * - Rate limiting
 * - Metrics tracking
 * - Transaction limits
 * - Better error handling
 * - QR code generation
 * - Clickable copy button (Adventure API)
 * - WebSocket-based real-time payment notifications
 */
public class InvoiceCommand implements CommandExecutor {

    private final LightningPlugin plugin;
    private final LNService lnService;
    private final WalletManager walletManager;
    private final WebSocketInvoiceMonitor invoiceMonitor;
    private final RateLimiter rateLimiter;

    public InvoiceCommand(LightningPlugin plugin) {
        this.plugin = plugin;
        this.lnService = plugin.getLnService();
        this.walletManager = plugin.getWalletManager();
        this.invoiceMonitor = plugin.getInvoiceMonitor();
        
        // Rate limit from config
        int limit = plugin.getConfig().getInt("rate_limits.invoices_per_minute", 5);
        this.rateLimiter = RateLimiter.perMinute(limit);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        // Check rate limit
        RateLimiter.RateLimitResult rateCheck = rateLimiter.check(player.getUniqueId());
        if (!rateCheck.allowed) {
            player.sendMessage("§cYou're creating invoices too quickly!");
            player.sendMessage("§7" + rateCheck.getRetryMessage());
            return true;
        }

        // Validate arguments
        if (args.length < 1) {
            player.sendMessage("§cUsage: /invoice <amount> [memo]");
            player.sendMessage("§7Example: /invoice 1000");
            player.sendMessage("§7Example: /invoice 1000 Coffee donation");
            return true;
        }

        // Parse amount
        long amountSats;
        try {
            amountSats = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cInvalid amount: " + args[0]);
            player.sendMessage("§7Amount must be a whole number (sats)");
            return true;
        }

        // Validate amount limits
        long minAmount = plugin.getConfig().getLong("limits.min_invoice_amount", 1);
        long maxAmount = plugin.getConfig().getLong("limits.max_invoice_amount", 100000);

        if (amountSats < minAmount) {
            player.sendMessage("§cAmount too small. Minimum: " + minAmount + " sats");
            return true;
        }

        if (amountSats > maxAmount) {
            player.sendMessage("§cAmount too large. Maximum: " + formatSats(maxAmount));
            return true;
        }

        // Check max wallet balance limit (if set)
        long maxBalance = plugin.getConfig().getLong("limits.max_wallet_balance", 0);
        if (maxBalance > 0) {
            walletManager.getBalance(player).thenAccept(currentBalance -> {
                if (currentBalance + amountSats > maxBalance) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.sendMessage("§cThis would exceed your wallet limit!");
                        player.sendMessage("§7Current: " + formatSats(currentBalance));
                        player.sendMessage("§7Max: " + formatSats(maxBalance));
                    });
                }
            });
        }

        // Parse memo
        String memo = "";
        if (args.length > 1) {
            memo = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            if (memo.length() > 100) {
                memo = memo.substring(0, 97) + "...";
            }
        }

        // Ensure player has wallet
        final String finalMemo = memo;
        if (!walletManager.hasWallet(player)) {
            player.sendMessage("§eCreating your Lightning wallet...");
            
            walletManager.getOrCreateWallet(player)
                .thenAccept(wallet -> {
                    if (wallet.has("id")) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            player.sendMessage("§aWallet created! Creating invoice...");
                            createInvoice(player, amountSats, finalMemo);
                        });
                    } else {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            player.sendMessage("§cFailed to create wallet. Please try again later.");
                        });
                    }
                })
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.sendMessage("§cError creating wallet: " + ex.getMessage());
                    });
                    return null;
                });
            
            return true;
        }

        // Create invoice
        player.sendMessage("§eCreating invoice for " + formatSats(amountSats) + "...");
        createInvoice(player, amountSats, memo);

        return true;
    }

    /**
     * Create and track a Lightning invoice (with WebSocket monitoring)
     */
    private void createInvoice(Player player, long amountSats, String memo) {
        try (var timer = plugin.getMetrics().startTiming("invoice_create")) {
            
            String finalMemo = memo.isEmpty() ? player.getName() + " deposit" : memo;
            
            lnService.createInvoiceForPlayer(player, amountSats, finalMemo)
                .thenAccept(response -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        handleInvoiceResponse(player, amountSats, finalMemo, response);
                    });
                })
                .exceptionally(ex -> {
                    plugin.getMetrics().recordApiError();
                    
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.sendMessage("§cFailed to create invoice!");
                        player.sendMessage("§7Error: " + ex.getMessage());
                        plugin.getDebugLogger().error("Invoice creation failed for " + player.getName(), ex);
                    });
                    return null;
                });
                
        }
    }

    /**
     * Handle the invoice creation response
     */
    private void handleInvoiceResponse(Player player, long amountSats, String memo, 
                                      LNService.LNResponse<LNService.Invoice> response) {
        if (!response.success) {
            player.sendMessage("§cFailed to create invoice!");
            player.sendMessage("§7Error: " + response.error);
            plugin.getMetrics().recordError("invoice_create", response.error);
            return;
        }

        LNService.Invoice invoice = response.data;
        
        // Record metrics
        plugin.getMetrics().recordInvoiceCreated(player.getUniqueId(), amountSats);

        // Start WebSocket monitoring for real-time notifications
        if (invoiceMonitor != null) {
            invoiceMonitor.trackInvoice(player, invoice.paymentHash, amountSats, memo);
            plugin.getDebugLogger().debug("Invoice " + invoice.paymentHash + 
                " now being monitored via WebSocket");
        }

        // Display invoice to player
        displayInvoice(player, invoice, amountSats, memo);
    }

    /**
     * Display invoice details to player with QR code map and clickable copy button
     */
    private void displayInvoice(Player player, LNService.Invoice invoice, long amountSats, String memo) {
        player.sendMessage("§a§l⚡ INVOICE CREATED!");
        player.sendMessage("");
        player.sendMessage("§eAmount: §f" + formatSats(amountSats));
        
        if (memo != null && !memo.isEmpty()) {
            player.sendMessage("§eMemo: §f" + memo);
        }
        
        player.sendMessage("");
        player.sendMessage("§7Generating QR code map...");
        
        // Generate QR code map
        try {
            boolean qrSuccess = QRMapGenerator.giveMap(player, invoice.bolt11);
            
            if (qrSuccess) {
                player.sendMessage("§a✓ QR code map added to inventory!");
                plugin.getDebugLogger().debug("QR map generated for invoice: " + invoice.paymentHash);
            } else {
                player.sendMessage("§c✗ Failed to generate QR code map");
                plugin.getDebugLogger().warning("QR generation failed for " + player.getName());
            }
        } catch (Exception e) {
            player.sendMessage("§c✗ Error generating QR code");
            plugin.getDebugLogger().error("QR generation error for " + player.getName(), e);
        }
        
        player.sendMessage("");
        player.sendMessage("§7Invoice (click to copy):");
        
        // Create clickable copy button using Adventure API
        Component invoiceComponent = Component.text()
            .append(Component.text("[Copy Invoice]", NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.copyToClipboard(invoice.bolt11))
                .hoverEvent(HoverEvent.showText(
                    Component.text("Click to copy invoice to clipboard", NamedTextColor.GRAY)
                ))
            )
            .build();
        
        player.sendMessage(invoiceComponent);
        
        // Also show the invoice text for manual copying
        player.sendMessage("");
        player.sendMessage("§8" + truncateInvoice(invoice.bolt11));
        player.sendMessage("");
        player.sendMessage("§7Payment Hash:");
        player.sendMessage("§8" + invoice.paymentHash.substring(0, 32) + "...");
        player.sendMessage("");
        player.sendMessage("§a You'll be notified instantly when paid!");
        
        plugin.getDebugLogger().info("Created invoice for " + player.getName() + ": " + 
                                    amountSats + " sats, hash=" + invoice.paymentHash);
    }

    /**
     * Truncate invoice for display (show first and last parts)
     */
    private String truncateInvoice(String invoice) {
        if (invoice.length() <= 80) {
            return invoice;
        }
        
        String start = invoice.substring(0, 40);
        String end = invoice.substring(invoice.length() - 40);
        return start + "..." + end;
    }

    private String formatSats(long sats) {
        if (sats >= 100_000_000) {
            return String.format("%.8f BTC", sats / 100_000_000.0);
        }
        return String.format("%,d sats", sats);
    }
}