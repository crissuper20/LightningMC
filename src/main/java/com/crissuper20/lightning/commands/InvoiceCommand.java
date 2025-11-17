package com.crissuper20.lightning.commands;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.managers.LNService;
import com.crissuper20.lightning.managers.WalletManager;
import com.crissuper20.lightning.util.QRMapRenderer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Invoice Command - Create Lightning invoices (standard or split payment)
 * 
 * Usage:
 *   /invoice <amount> [memo]        - Create standard invoice
 *   /invoice split                  - Get split payment book
 *   /invoice split confirm          - Create split invoice from book
 */
public class InvoiceCommand implements CommandExecutor, TabCompleter {

    private final LightningPlugin plugin;
    private final WalletManager walletManager;
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();
    
    // Track split configurations per payment hash
    private final Map<String, SplitConfig> pendingSplits = new HashMap<>();
    
    // Patterns for parsing split config
    private static final Pattern SPLIT_PATTERN = Pattern.compile("^([a-zA-Z0-9_]+)\\s*:\\s*(\\d+)\\s*$");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("^amount\\s*:\\s*(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);

    public InvoiceCommand(LightningPlugin plugin) {
        this.plugin = plugin;
        this.walletManager = plugin.getWalletManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.formatError("This command can only be used by players."));
            return true;
        }

        Player player = (Player) sender;

        // Ensure player has a wallet
        if (!walletManager.hasWallet(player)) {
            player.sendMessage(plugin.formatError("You don't have a wallet yet!"));
            player.sendMessage(plugin.formatMessage("§7Use §e/wallet create§7 to create one."));
            return true;
        }

        // Check for split invoice subcommands
        if (args.length > 0 && args[0].equalsIgnoreCase("split")) {
            if (!player.hasPermission("lightning.invoice.split")) {
                player.sendMessage(plugin.formatError("You don't have permission to create split invoices."));
                return true;
            }
            
            if (args.length > 1 && args[1].equalsIgnoreCase("confirm")) {
                return handleSplitConfirm(player);
            } else {
                return handleSplitBook(player);
            }
        }

        // Standard invoice creation
        return handleStandardInvoice(player, args);
    }

    // ================================================================
    // Standard Invoice
    // ================================================================

    private boolean handleStandardInvoice(Player player, String[] args) {
        // Validate arguments
        if (args.length < 1) {
            player.sendMessage(plugin.formatError("Usage: /invoice <amount> [memo]"));
            player.sendMessage(plugin.formatMessage("§7Example: §e/invoice 1000 Coffee donation"));
            player.sendMessage(plugin.formatMessage("§7Split payment: §e/invoice split"));
            return true;
        }

        // Parse amount
        long amountSats;
        try {
            amountSats = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.formatError("Invalid amount: " + args[0]));
            return true;
        }

        // Validate amount
        long minAmount = plugin.getConfig().getLong("limits.min_invoice_sats", 1);
        long maxAmount = plugin.getConfig().getLong("limits.max_invoice_sats", 1_000_000);

        if (amountSats < minAmount) {
            player.sendMessage(plugin.formatError("Minimum invoice amount: " + minAmount + " sats"));
            return true;
        }

        if (amountSats > maxAmount) {
            player.sendMessage(plugin.formatError("Maximum invoice amount: " + maxAmount + " sats"));
            return true;
        }

        // Build memo (final for lambda)
        final String memo;
        if (args.length > 1) {
            StringBuilder memoBuilder = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                if (i > 1) memoBuilder.append(" ");
                memoBuilder.append(args[i]);
            }
            memo = memoBuilder.toString();
        } else {
            memo = "Deposit for " + player.getName();
        }

        // Show processing message
        player.sendMessage(plugin.formatMessage("§eCreating invoice for §f" + formatSats(amountSats) + "§e..."));

        // Create invoice asynchronously using player's wallet
        plugin.getLnService().createInvoiceForPlayer(player, amountSats, memo)
            .thenAccept(response -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (response.success && response.data != null) {
                        handleStandardInvoiceCreated(player, response.data, amountSats, memo);
                    } else {
                        player.sendMessage(plugin.formatError("Failed to create invoice: " + response.error));
                        plugin.getDebugLogger().error("Invoice creation failed for " + player.getName() + ": " + response.error);
                    }
                });
            })
            .exceptionally(ex -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(plugin.formatError("An error occurred while creating the invoice."));
                    plugin.getDebugLogger().error("Invoice creation exception for " + player.getName(), ex);
                });
                return null;
            });

        return true;
    }

    private void handleStandardInvoiceCreated(Player player, LNService.Invoice invoice, long amountSats, String memo) {
        // Record metrics
        plugin.getMetrics().recordInvoiceCreated(player.getUniqueId(), amountSats);

        // Send success message
        player.sendMessage("");
        player.sendMessage(plugin.formatSuccess("§a§l✓ Invoice Created!"));
        player.sendMessage("");
        player.sendMessage("§eAmount: §f" + formatSats(amountSats));
        player.sendMessage("§eMemo: §f" + memo);
        player.sendMessage("");

        // Create clickable payment request (using bolt11)
        String bolt11 = invoice.bolt11;
        
        if (bolt11 != null && !bolt11.isEmpty()) {
            Component paymentRequest = Component.text("[Click to Copy Payment Request]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.copyToClipboard(bolt11))
                .hoverEvent(HoverEvent.showText(
                    Component.text("Click to copy payment request", NamedTextColor.GRAY)
                        .append(Component.newline())
                        .append(Component.text("Use in any Lightning wallet", NamedTextColor.GRAY))
                ));

            player.sendMessage(paymentRequest);
            player.sendMessage("");

            try {
                boolean qrCreated = QRMapRenderer.createMapForPlayer(plugin, player, bolt11, "payment");
                if (qrCreated) {
                    player.sendMessage("§a✓ QR code map added to your inventory.");
                } else {
                    player.sendMessage("§cCould not add QR map to your inventory. Make sure you have space.");
                }
            } catch (Exception e) {
                plugin.getDebugLogger().error("Failed to create QR code map for invoice", e);
                player.sendMessage("§cFailed to generate the QR map. Use the payment request above instead.");
            }
            player.sendMessage("");
        }

        // Show payment hash for reference
        String paymentHash = invoice.paymentHash;
        
        if (paymentHash != null && !paymentHash.isEmpty()) {
            player.sendMessage("§7Payment Hash: §f" + 
                (paymentHash.length() > 16 ? paymentHash.substring(0, 16) + "..." : paymentHash));
        }

        // Show expiry time
        int expirySeconds = plugin.getConfig().getInt("lnbits.invoice_expiry_seconds", 3600);
        int expiryMinutes = expirySeconds / 60;
        player.sendMessage("§7Expires in: §f" + expiryMinutes + " minutes");
        player.sendMessage("");

        // Inform about WebSocket monitoring
        player.sendMessage("§7✓ Payment monitoring active via WebSocket");
        player.sendMessage("§7Your balance will update automatically when paid!");
        player.sendMessage("");

        // Debug logging
        plugin.getDebugLogger().info(String.format(
            "Invoice created: player=%s, amount=%d sats, hash=%s",
            player.getName(),
            amountSats,
            paymentHash != null ? paymentHash.substring(0, 8) : "unknown"
        ));
    }

    // ================================================================
    // Split Invoice - Book Creation
    // ================================================================

    private boolean handleSplitBook(Player player) {
        // Create book and quill
        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        BookMeta bookMeta = (BookMeta) book.getItemMeta();
        
        if (bookMeta != null) {
            List<Component> pages = new ArrayList<>();

            pages.add(LEGACY_SERIALIZER.deserialize(
                "§0§lSplit Payment Invoice\n\n" +
                "§0Write your split on the next page:\n\n" +
                "§0Format:\n" +
                "§0player1: 30\n" +
                "§0player2: 20\n" +
                "§0player3: 50\n" +
                "§0amount: 1000\n\n" +
                "§0Then sign it and\n" +
                "§0use: §r§l/invoice split confirm"
            ));

            pages.add(LEGACY_SERIALIZER.deserialize(
                "§0player1: 50\n" +
                "§0player2: 50\n" +
                "§0amount: 1000"
            ));

            bookMeta.pages(pages);
            book.setItemMeta(bookMeta);
        }

        // Give book to player
        PlayerInventory inv = player.getInventory();
        if (inv.firstEmpty() == -1) {
            player.sendMessage("§cYour inventory is full! Make space and try again.");
            return true;
        }

        inv.addItem(book);
        
        player.sendMessage("§a✓ Split invoice book given!");
        player.sendMessage("");
        player.sendMessage("§eInstructions:");
        player.sendMessage("§71. Open the book");
        player.sendMessage("§72. Write split configuration on page 2");
        player.sendMessage("§7   Format: §fplayername: percentage");
        player.sendMessage("§7   Example: §fAlice: 60");
        player.sendMessage("§73. Add §famount: <sats> §7at the end");
        player.sendMessage("§74. Sign the book (any title works)");
        player.sendMessage("§75. Run §e/invoice split confirm");
        player.sendMessage("");
        player.sendMessage("§c⚠ Percentages must add up to 100!");

        return true;
    }

    // ================================================================
    // Split Invoice - Confirmation
    // ================================================================

    private boolean handleSplitConfirm(Player player) {
        // Find any signed book in inventory
        ItemStack book = findSignedBook(player);
        
        if (book == null) {
            player.sendMessage("§c✗ No signed book found!");
            player.sendMessage("§7Please sign your split invoice book first.");
            player.sendMessage("§7Use §e/invoice split §7to get a new one.");
            return true;
        }

        BookMeta bookMeta = (BookMeta) book.getItemMeta();
        if (bookMeta == null || !bookMeta.hasPages()) {
            player.sendMessage("§c✗ Invalid book!");
            return true;
        }

        // Verify book is signed by this player
        if (!bookMeta.hasAuthor() || !bookMeta.getAuthor().equals(player.getName())) {
            player.sendMessage("§c✗ You can only use books you signed yourself!");
            return true;
        }

        // Parse the book (page 2 for config, or page 1 if only one page)
        List<Component> pages = bookMeta.pages();
        if (pages.isEmpty()) {
            player.sendMessage("§c✗ Invalid book content!");
            return true;
        }

        Component configComponent = pages.size() >= 2 ? pages.get(1) : pages.get(0);
        String configPage = LEGACY_SERIALIZER.serialize(configComponent);
        
        player.sendMessage("§eParsing split configuration...");
        
        SplitParseResult result = parseSplitConfig(configPage);
        
        if (!result.success) {
            player.sendMessage("§c✗ " + result.error);
            player.sendMessage("");
            player.sendMessage("§7Expected format:");
            player.sendMessage("§fplayer1: 50");
            player.sendMessage("§fplayer2: 50");
            player.sendMessage("§famount: 1000");
            return true;
        }

        // Validate all players and wallets
        player.sendMessage("§eValidating recipients...");
        
        String validationError = validateRecipients(player, result);
        if (validationError != null) {
            player.sendMessage("§c✗ " + validationError);
            return true;
        }

        // Remove the book from inventory
        player.getInventory().remove(book);
        player.sendMessage("§7Book consumed.");
        
        // Show confirmation
        player.sendMessage("");
        player.sendMessage("§a✓ Configuration valid!");
        player.sendMessage("§7Total amount: §f" + result.amount + " sats");
        player.sendMessage("§7Recipients:");
        for (SplitEntry entry : result.splits) {
            long splitAmount = (result.amount * entry.percent) / 100;
            player.sendMessage("§7  " + entry.playerName + ": §f" + entry.percent + "% §7(§f" + splitAmount + " sats§7)");
        }
        player.sendMessage("");
        player.sendMessage("§eCreating split invoice...");

        // Create the invoice
        createSplitInvoice(player, result);

        return true;
    }

    // ================================================================
    // Split Invoice - Creation
    // ================================================================

    private void createSplitInvoice(Player creator, SplitParseResult config) {
        String memo = "Split payment - " + config.splits.size() + " recipients";
        
        // Create invoice using player's wallet
        plugin.getLnService().createInvoiceForPlayer(creator, config.amount, memo)
            .thenAccept(response -> {
                if (!response.success || response.data == null) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        creator.sendMessage("§c✗ Failed to create invoice!");
                        plugin.getDebugLogger().error("Split invoice creation failed: " + response.error);
                    });
                    return;
                }

                LNService.Invoice invoice = response.data;
                String bolt11 = invoice.bolt11;
                String paymentHash = invoice.paymentHash;

                // Register split configuration
                SplitConfig splitConfig = new SplitConfig(
                    creator.getUniqueId(), 
                    config.amount, 
                    config.splits, 
                    memo
                );
                synchronized (pendingSplits) {
                    pendingSplits.put(paymentHash, splitConfig);
                }

                // Record metrics
                plugin.getMetrics().recordInvoiceCreated(creator.getUniqueId(), config.amount);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    creator.sendMessage("§a§l✓ SPLIT INVOICE CREATED!");
                    creator.sendMessage("");
                    creator.sendMessage("§7Amount: §f" + config.amount + " sats");
                    creator.sendMessage("§7Recipients: §f" + config.splits.size());
                    creator.sendMessage("");
                    creator.sendMessage("§eSplit breakdown:");
                    for (SplitEntry entry : config.splits) {
                        long splitAmount = (config.amount * entry.percent) / 100;
                        creator.sendMessage("§7  " + entry.playerName + ": §f" + 
                            entry.percent + "% §7(§f" + splitAmount + " sats§7)");
                    }
                    creator.sendMessage("");
                    
                    if (bolt11 != null && !bolt11.isEmpty()) {
                        creator.sendMessage("§7Invoice: §f" + bolt11.substring(0, Math.min(50, bolt11.length())) + "...");
                        creator.sendMessage("");
                        
                        // Generate QR code
                        try {
                            boolean qrSuccess = QRMapRenderer.createMapForPlayer(plugin, creator, bolt11, "payment");
                            if (qrSuccess) {
                                creator.sendMessage("§a✓ QR code added to inventory!");
                                creator.sendMessage("");
                            }
                        } catch (Exception e) {
                            plugin.getDebugLogger().warn("Failed to create QR code: " + e.getMessage());
                        }
                    }
                    
                    creator.sendMessage("§7✓ Payment monitoring active via WebSocket");
                    creator.sendMessage("§7When paid, funds will automatically");
                    creator.sendMessage("§7distribute to all recipients.");
                });
            })
            .exceptionally(ex -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    creator.sendMessage("§c✗ Error creating split invoice: " + ex.getMessage());
                    plugin.getDebugLogger().error("Split invoice creation failed", ex);
                });
                return null;
            });
    }

    // ================================================================
    // Split Invoice - Payment Processing
    // ================================================================

    /**
     * Process split payment when invoice is paid
     * Called by WebSocketInvoiceMonitor
     */
    public void processSplitPayment(String paymentHash, long amountReceived) {
        SplitConfig config;
        synchronized (pendingSplits) {
            config = pendingSplits.remove(paymentHash);
        }
        
        if (config == null) {
            return; // Not a split invoice
        }

        plugin.getDebugLogger().info("Processing split payment: " + paymentHash.substring(0, 8));

        // Distribute payments to each recipient
        List<CompletableFuture<Boolean>> transfers = new ArrayList<>();

        for (SplitEntry entry : config.splits) {
            long splitAmount = (config.totalAmount * entry.percent) / 100;
            
            Player recipient = Bukkit.getPlayer(entry.playerUuid);
            if (recipient == null || !recipient.isOnline()) {
                plugin.getDebugLogger().warn("Split recipient offline: " + entry.playerName);
                // TODO: Queue payment for when they come online
                continue;
            }

            // Transfer funds to recipient
            CompletableFuture<Boolean> transfer = transferFunds(
                config.creatorUuid, 
                recipient, 
                splitAmount, 
                "Split: " + config.memo
            );
            transfers.add(transfer);
        }

        // Wait for all transfers to complete
        CompletableFuture.allOf(transfers.toArray(new CompletableFuture[0]))
            .thenRun(() -> {
                plugin.getDebugLogger().info("Split payment completed: " + paymentHash.substring(0, 8));
                
                // Notify creator
                Player creator = Bukkit.getPlayer(config.creatorUuid);
                if (creator != null && creator.isOnline()) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        creator.sendMessage("§a§l✓ SPLIT PAYMENT DISTRIBUTED!");
                        creator.sendMessage("§7Total: §f" + config.totalAmount + " sats");
                        creator.sendMessage("§7Recipients: §f" + config.splits.size());
                    });
                }

                // Notify all recipients
                for (SplitEntry entry : config.splits) {
                    Player recipient = Bukkit.getPlayer(entry.playerUuid);
                    if (recipient != null && recipient.isOnline()) {
                        long splitAmount = (config.totalAmount * entry.percent) / 100;
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            recipient.sendMessage("§a§l⚡ SPLIT PAYMENT RECEIVED!");
                            recipient.sendMessage("§7Amount: §f+" + splitAmount + " sats §7(" + entry.percent + "%)");
                            recipient.sendMessage("§7From: §f" + config.memo);
                            
                            try {
                                recipient.playSound(recipient.getLocation(), "entity.experience_orb.pickup", 1.0f, 1.0f);
                            } catch (Exception ignored) {}
                        });
                    }
                }
            });
    }

    /**
     * Transfer funds between wallets using internal LNbits transfer
     */
    private CompletableFuture<Boolean> transferFunds(UUID fromUuid, Player toPlayer, long amount, String memo) {
        Player fromPlayer = Bukkit.getPlayer(fromUuid);
        if (fromPlayer == null) {
            return CompletableFuture.completedFuture(false);
        }

        // Create invoice on recipient's wallet
        return plugin.getLnService().createInvoiceForPlayer(toPlayer, amount, memo)
            .thenCompose(invoiceResponse -> {
                if (!invoiceResponse.success || invoiceResponse.data == null) {
                    return CompletableFuture.completedFuture(false);
                }

                String bolt11 = invoiceResponse.data.bolt11;
                
                // Pay invoice from creator's wallet (internal transfer)
                return plugin.getLnService().payInvoiceForPlayer(fromPlayer, bolt11)
                    .thenApply(payResponse -> payResponse.success);
            })
            .exceptionally(ex -> {
                plugin.getDebugLogger().error("Split transfer failed: " + fromUuid + " -> " + toPlayer.getName(), ex);
                return false;
            });
    }

    // ================================================================
    // Split Invoice - Parsing & Validation
    // ================================================================

    private ItemStack findSignedBook(Player player) {
        PlayerInventory inv = player.getInventory();
        
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() == Material.WRITTEN_BOOK) {
                BookMeta meta = (BookMeta) item.getItemMeta();
                if (meta != null && meta.hasAuthor()) {
                    return item;
                }
            }
        }
        
        return null;
    }

    private SplitParseResult parseSplitConfig(String page) {
        // Strip Minecraft formatting codes
        String cleanPage = page.replaceAll("§[0-9a-fk-or]", "");
        
        List<SplitEntry> splits = new ArrayList<>();
        Long amount = null;
        int totalPercent = 0;
        
        String[] lines = cleanPage.split("\\n");
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            // Check if it's the amount line
            Matcher amountMatcher = AMOUNT_PATTERN.matcher(line);
            if (amountMatcher.matches()) {
                try {
                    amount = Long.parseLong(amountMatcher.group(1));
                    if (amount <= 0) {
                        return SplitParseResult.error("Amount must be positive!");
                    }
                } catch (NumberFormatException e) {
                    return SplitParseResult.error("Invalid amount: " + amountMatcher.group(1));
                }
                continue;
            }
            
            // Check if it's a split line
            Matcher splitMatcher = SPLIT_PATTERN.matcher(line);
            if (splitMatcher.matches()) {
                String playerName = splitMatcher.group(1);
                int percent;
                
                try {
                    percent = Integer.parseInt(splitMatcher.group(2));
                    if (percent <= 0 || percent > 100) {
                        return SplitParseResult.error("Invalid percentage for " + playerName + ": " + percent);
                    }
                } catch (NumberFormatException e) {
                    return SplitParseResult.error("Invalid percentage: " + splitMatcher.group(2));
                }
                
                splits.add(new SplitEntry(playerName, percent));
                totalPercent += percent;
                continue;
            }
            
            // Unknown line format
            return SplitParseResult.error("Invalid line format: " + line);
        }
        
        // Validate
        if (splits.isEmpty()) {
            return SplitParseResult.error("No recipients specified!");
        }
        
        if (amount == null) {
            return SplitParseResult.error("Amount not specified!");
        }
        
        if (totalPercent != 100) {
            return SplitParseResult.error("Percentages must add up to 100! (current: " + totalPercent + "%)");
        }
        
        return new SplitParseResult(splits, amount);
    }

    private String validateRecipients(Player creator, SplitParseResult result) {
        for (SplitEntry entry : result.splits) {
            Player recipient = Bukkit.getPlayer(entry.playerName);
            
            if (recipient == null) {
                return "Player not found: " + entry.playerName + " (must be online)";
            }
            
            if (!walletManager.hasWallet(recipient)) {
                return entry.playerName + " doesn't have a wallet yet!";
            }
            
            entry.playerUuid = recipient.getUniqueId(); // Store UUID
        }
        
        return null; // All valid
    }

    // ================================================================
    // Public API
    // ================================================================

    public Map<String, SplitConfig> getPendingSplits() {
        synchronized (pendingSplits) {
            return new HashMap<>(pendingSplits);
        }
    }

    public boolean isSplitInvoice(String paymentHash) {
        synchronized (pendingSplits) {
            return pendingSplits.containsKey(paymentHash);
        }
    }

    // ================================================================
    // Utilities
    // ================================================================

    private String formatSats(long sats) {
        if (sats >= 100_000_000) {
            return String.format("%.8f BTC", sats / 100_000_000.0);
        }
        return String.format("%,d sats", sats);
    }

    // ================================================================
    // Tab Completion
    // ================================================================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("split");
            suggestions.add("1000");
            suggestions.add("5000");
            suggestions.add("10000");
            suggestions.add("21000");
            suggestions.add("100000");
            return suggestions;
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("split")) {
                return Collections.singletonList("confirm");
            } else {
                return Collections.singletonList("<memo>");
            }
        }

        return Collections.emptyList();
    }

    // ================================================================
    // Data Classes
    // ================================================================

    private static class SplitParseResult {
        final boolean success;
        final String error;
        final List<SplitEntry> splits;
        final Long amount;

        private SplitParseResult(List<SplitEntry> splits, Long amount) {
            this.success = true;
            this.error = null;
            this.splits = splits;
            this.amount = amount;
        }

        private SplitParseResult(String error) {
            this.success = false;
            this.error = error;
            this.splits = null;
            this.amount = null;
        }

        static SplitParseResult error(String error) {
            return new SplitParseResult(error);
        }
    }

    private static class SplitEntry {
        final String playerName;
        final int percent;
        UUID playerUuid; // Filled during validation

        SplitEntry(String playerName, int percent) {
            this.playerName = playerName;
            this.percent = percent;
        }
    }

    public static class SplitConfig {
        public final UUID creatorUuid;
        public final long totalAmount;
        public final List<SplitEntry> splits;
        public final String memo;

        public SplitConfig(UUID creatorUuid, long totalAmount, List<SplitEntry> splits, String memo) {
            this.creatorUuid = creatorUuid;
            this.totalAmount = totalAmount;
            this.splits = splits;
            this.memo = memo;
        }
    }
}