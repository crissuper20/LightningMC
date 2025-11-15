package com.crissuper20.lightning.commands;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.managers.LNService;
import com.crissuper20.lightning.managers.WalletManager;
import com.crissuper20.lightning.util.QRMapRenderer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /splitinvoice command - Create split payment invoices using books
 * 
 * Usage:
 *   /splitinvoice - Give book and quill for configuration
 *   /splitinvoice confirm - Create invoice from signed book
 * 
 * Book format (first page only):
 *   player1: 30
 *   player2: 20
 *   player3: 50
 *   amount: 1000
 * 
 * Rules:
 * - Percentages must sum to exactly 100
 * - All players must have wallets
 * - Amount must be positive
 * - Only first page is read
 */
public class SplitInvoiceCommand implements CommandExecutor {

    private final LightningPlugin plugin;
    private final WalletManager walletManager;
    private final LNService lnService;
    
    // Track split configurations per payment hash
    private final Map<String, SplitConfig> pendingSplits = new HashMap<>();
    
    // Pattern for parsing lines: "playername: 50"
    private static final Pattern SPLIT_PATTERN = Pattern.compile("^([a-zA-Z0-9_]+)\\s*:\\s*(\\d+)\\s*$");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("^amount\\s*:\\s*(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);

    public SplitInvoiceCommand(LightningPlugin plugin) {
        this.plugin = plugin;
        this.walletManager = plugin.getWalletManager();
        this.lnService = plugin.getLnService();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("lightning.splitinvoice")) {
            player.sendMessage("§cYou don't have permission to create split invoices.");
            return true;
        }

        if (!walletManager.hasWallet(player)) {
            player.sendMessage("§cYou need a wallet first!");
            player.sendMessage("§7Use §e/invoice §7to create one.");
            return true;
        }

        // Check if player wants to confirm
        if (args.length > 0 && args[0].equalsIgnoreCase("confirm")) {
            return handleConfirm(player);
        }

        // Default: Give book and quill
        return handleGiveBook(player);
    }

    /**
     * Give player a book and quill with instructions
     */
    private boolean handleGiveBook(Player player) {
        // Create book and quill
        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        BookMeta bookMeta = (BookMeta) book.getItemMeta();
        
        if (bookMeta != null) {
            // Add instructions and template
            bookMeta.addPage(
                "§0§lSplit Payment Invoice\n\n" +
                "§0Write your split on the next page:\n\n" +
                "§0Format:\n" +
                "§0player1: 30\n" +
                "§0player2: 20\n" +
                "§0player3: 50\n" +
                "§0amount: 1000\n\n" +
                "§0Then sign it and\n" +
                "§0use: §r§l/splitinvoice confirm"
            );
            
            // Template page
            bookMeta.addPage(
                "§0player1: 50\n" +
                "§0player2: 50\n" +
                "§0amount: 1000"
            );
            
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
        player.sendMessage("§75. Run §e/splitinvoice confirm");
        player.sendMessage("");
        player.sendMessage("§c⚠ Percentages must add up to 100!");

        return true;
    }

    /**
     * Confirm and create split invoice from signed book
     */
    private boolean handleConfirm(Player player) {
        // Find any signed book in inventory
        ItemStack book = findSignedBook(player);
        
        if (book == null) {
            player.sendMessage("§c✗ No signed book found!");
            player.sendMessage("§7Please sign your split invoice book first.");
            player.sendMessage("§7Use §e/splitinvoice §7to get a new one.");
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

        // Parse the book (first page after instructions)
        String configPage = bookMeta.getPageCount() >= 2 ? bookMeta.getPage(2) : bookMeta.getPage(1);
        
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

    /**
     * Find any signed book in player inventory
     */
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

    /**
     * Parse split configuration from book page
     */
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

    /**
     * Validate all recipients exist and have wallets
     * Returns error message or null if valid
     */
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

    /**
     * Create the split invoice
     */
    private void createSplitInvoice(Player creator, SplitParseResult config) {
        String memo = "Split payment - " + config.splits.size() + " recipients";
        
        lnService.createInvoiceForPlayer(creator, config.amount, memo)
            .thenAccept(response -> {
                if (!response.success || response.data == null) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        creator.sendMessage("§c✗ Failed to create invoice!");
                    });
                    return;
                }

                LNService.Invoice invoice = response.data;
                String paymentRequest = invoice.bolt11;
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

                // Track invoice for payment monitoring
                plugin.getInvoiceMonitor().trackInvoice(creator, paymentHash, config.amount, memo);

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
                    creator.sendMessage("§7Invoice: §f" + paymentRequest.substring(0, Math.min(50, paymentRequest.length())) + "...");
                    creator.sendMessage("");
                    creator.sendMessage("§7When paid, funds will automatically");
                    creator.sendMessage("§7distribute to all recipients.");

                    // Generate QR code
                    boolean qrSuccess = QRMapRenderer.createMapForPlayer(plugin, creator, paymentRequest, "payment");
                    if (qrSuccess) {
                        creator.sendMessage("");
                        creator.sendMessage("§a✓ QR code added to inventory!");
                    }
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

        plugin.getDebugLogger().info("Processing split payment: " + paymentHash);

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
                plugin.getDebugLogger().info("Split payment completed: " + paymentHash);
                
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
        return lnService.createInvoiceForPlayer(toPlayer, amount, memo)
            .thenCompose(invoiceResponse -> {
                if (!invoiceResponse.success) {
                    return CompletableFuture.completedFuture(false);
                }

                String bolt11 = invoiceResponse.data.bolt11;
                
                // Pay invoice from creator's wallet (internal transfer)
                return lnService.payInvoiceForPlayer(fromPlayer, bolt11)
                    .thenApply(payResponse -> payResponse.success);
            })
            .exceptionally(ex -> {
                plugin.getDebugLogger().error("Split transfer failed: " + fromUuid + " -> " + toPlayer.getName(), ex);
                return false;
            });
    }

    public Map<String, SplitConfig> getPendingSplits() {
        synchronized (pendingSplits) {
            return new HashMap<>(pendingSplits);
        }
    }

    // ================================================================
    // Data classes
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