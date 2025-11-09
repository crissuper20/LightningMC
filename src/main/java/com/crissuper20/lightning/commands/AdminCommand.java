    package com.crissuper20.lightning.commands;

    import com.crissuper20.lightning.LightningPlugin;
    import com.crissuper20.lightning.managers.WebSocketInvoiceMonitor;
    import com.crissuper20.lightning.managers.WalletManager;
    import com.crissuper20.lightning.util.PluginMetrics;
    import org.bukkit.Bukkit;
    import org.bukkit.command.Command;
    import org.bukkit.command.CommandExecutor;
    import org.bukkit.command.CommandSender;
    import org.bukkit.command.TabCompleter;
    import org.bukkit.entity.Player;

    import java.util.*;
    import java.util.concurrent.CompletableFuture;

    /**
     * Lightning Admin Command - WebSocket Edition
     * 
     * Commands:
     *   /lnadmin stats - Show plugin statistics
     *   /lnadmin wallets - List all player wallets
     *   /lnadmin sync [player] - Sync wallet balance(s)
     *   /lnadmin invoices - Show pending invoices
     *   /lnadmin health - Check backend health
     *   /lnadmin websocket - Show WebSocket connection status
     *   /lnadmin reload - Reload configuration
     */
    public class AdminCommand implements CommandExecutor, TabCompleter {
        
        private final LightningPlugin plugin;
        private final PluginMetrics metrics;
        
        public AdminCommand(LightningPlugin plugin, PluginMetrics metrics) {
            this.plugin = plugin;
            this.metrics = metrics;
        }
        
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            // Permission check
            if (!sender.hasPermission("lightning.admin")) {
                sender.sendMessage("§cYou don't have permission to use this command.");
                return true;
            }
            
            if (args.length == 0) {
                sendHelp(sender);
                return true;
            }
            
            String subcommand = args[0].toLowerCase();
            
            switch (subcommand) {
                case "stats":
                    return handleStats(sender);
                case "wallets":
                    return handleWallets(sender);
                case "sync":
                    return handleSync(sender, args);
                case "invoices":
                    return handleInvoices(sender);
                case "health":
                    return handleHealth(sender);
                case "websocket":
                case "ws":
                    return handleWebSocket(sender);
                case "reload":
                    return handleReload(sender);
                case "metrics":
                    return handleMetrics(sender);
                default:
                    sender.sendMessage("§cUnknown subcommand: " + subcommand);
                    sendHelp(sender);
                    return true;
            }
        }
        
        private void sendHelp(CommandSender sender) {
            sender.sendMessage("§6§l=== Lightning Admin Commands ===");
            sender.sendMessage("§e/lnadmin stats §7- Show plugin statistics");
            sender.sendMessage("§e/lnadmin wallets §7- List all player wallets");
            sender.sendMessage("§e/lnadmin sync [player] §7- Sync wallet balance(s)");
            sender.sendMessage("§e/lnadmin invoices §7- Show pending invoices");
            sender.sendMessage("§e/lnadmin health §7- Check backend health");
            sender.sendMessage("§e/lnadmin websocket §7- Show WebSocket status");
            sender.sendMessage("§e/lnadmin metrics §7- Show detailed metrics");
            sender.sendMessage("§e/lnadmin reload §7- Reload configuration");
        }
        
        private boolean handleStats(CommandSender sender) {
            sender.sendMessage("§6§l=== Lightning Plugin Stats ===");
            sender.sendMessage("");
            sender.sendMessage("§eUptime: §f" + metrics.getUptimeFormatted());
            sender.sendMessage("§eBackend: §f" + plugin.getLnService().getBackendName() + " (WebSocket)");
            sender.sendMessage("§eWallets: §f" + plugin.getWalletManager().getWalletCount());
            sender.sendMessage("");
            sender.sendMessage("§6Transactions:");
            sender.sendMessage("§e  Invoices Created: §f" + metrics.getInvoicesCreated());
            sender.sendMessage("§e  Invoices Paid: §f" + metrics.getInvoicesPaid());
            sender.sendMessage("§e  Payments: §f" + metrics.getPaymentsSuccessful() + "/" + metrics.getPaymentsAttempted() + 
                            " §7(" + String.format("%.1f%%", metrics.getPaymentSuccessRate()) + " success)");
            sender.sendMessage("");
            sender.sendMessage("§6Volume:");
            sender.sendMessage("§e  Deposited: §f" + formatSats(metrics.getTotalSatsDeposited()));
            sender.sendMessage("§e  Withdrawn: §f" + formatSats(metrics.getTotalSatsWithdrawn()));
            sender.sendMessage("§e  Net: §f" + formatSats(metrics.getNetBalance()));
            sender.sendMessage("");
            sender.sendMessage("§6Errors:");
            sender.sendMessage("§e  Total: §f" + metrics.getTotalErrors());
            
            WebSocketInvoiceMonitor monitor = plugin.getInvoiceMonitor();
            if (monitor != null) {
                sender.sendMessage("");
                sender.sendMessage("§6WebSocket Invoice Monitor:");
                sender.sendMessage("§e  Pending: §f" + monitor.getPendingCount());
                sender.sendMessage("§e  Processed: §f" + monitor.getTotalProcessed());
                sender.sendMessage("§e  Expired: §f" + monitor.getTotalExpired());
                sender.sendMessage("§e  Active Connections: §f" + monitor.getActiveConnections());
                sender.sendMessage("§e  Reconnect Attempts: §f" + monitor.getReconnectAttempts());
            }
            
            return true;
        }
        
        private boolean handleWallets(CommandSender sender) {
            WalletManager walletManager = plugin.getWalletManager();
            int count = walletManager.getWalletCount();

            sender.sendMessage("§6§l=== Player Wallets (" + count + ") ===");

            if (count == 0) {
                sender.sendMessage("§7No wallets created yet.");
                return true;
            }

            List<Map.Entry<UUID, PluginMetrics.PlayerActivity>> topPlayers = metrics.getTopPlayers(10);
            
            sender.sendMessage("");
            sender.sendMessage("§eTop Players by Volume (Fetching Balances...)");
            sender.sendMessage("§7Please wait a moment for the network calls to complete.");
            
            List<CompletableFuture<String>> balanceFutures = new ArrayList<>();

            for (Map.Entry<UUID, PluginMetrics.PlayerActivity> entry : topPlayers) {
                UUID uuid = entry.getKey();
                Player player = Bukkit.getPlayer(uuid);
                
                if (player == null) {
                    if (!walletManager.hasWallet(uuid)) continue;

                    // Fetch the balance asynchronously
                    CompletableFuture<String> future = walletManager.fetchBalanceFromLNbits(player)
                        .thenApply(balance -> {
                            PluginMetrics.PlayerActivity activity = entry.getValue();
                            String name = Bukkit.getOfflinePlayer(uuid).getName();
                            
                            return String.format("§f%s §7- §f%s §7(↓%s ↑%s)", 
                                name != null ? name : uuid.toString().substring(0, 8),
                                formatSats(balance),
                                formatSats(activity.totalDeposited),
                                formatSats(activity.totalWithdrawn)
                            );
                        })
                        .exceptionally(ex -> {
                            PluginMetrics.PlayerActivity activity = entry.getValue();
                            String name = Bukkit.getOfflinePlayer(uuid).getName();
                            return String.format("§f%s §7- §cFailed to fetch balance §7(↓%s ↑%s)", 
                                name != null ? name : uuid.toString().substring(0, 8),
                                formatSats(activity.totalDeposited),
                                formatSats(activity.totalWithdrawn)
                            );
                        });
                    
                    balanceFutures.add(future);
                }
            }
            
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                balanceFutures.toArray(new CompletableFuture[0])
            );

            allFutures.thenRun(() -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage("§6§l=== Top Players Balances ===");
                    
                    int rank = 1;
                    for (CompletableFuture<String> future : balanceFutures) {
                        String line = future.join();
                        sender.sendMessage(String.format("§f%d. %s", rank++, line));
                    }

                    sender.sendMessage("");
                    sender.sendMessage("§7Use §e/lnadmin sync [player]§7 to manually update a balance");
                });
            }).exceptionally(ex -> {
                plugin.getLogger().severe("Error processing wallet list: " + ex.getMessage());
                return null;
            });

            return true;
        }

        private boolean handleSync(CommandSender sender, String[] args) {
            WalletManager walletManager = plugin.getWalletManager();
            
            if (args.length == 1) {
                // Sync all wallets
                sender.sendMessage("§eSyncing all wallet balances...");
                
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    walletManager.syncAllBalances();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        sender.sendMessage("§aBalance sync complete!");
                    });
                });
                
                return true;
            }
            
            // Sync specific player
            String playerName = args[1];
            Player target = Bukkit.getPlayer(playerName);
            
            if (target == null) {
                sender.sendMessage("§cPlayer not found: " + playerName);
                return true;
            }
            
            if (!walletManager.hasWallet(target)) {
                sender.sendMessage("§c" + target.getName() + " has no wallet.");
                return true;
            }
            
            sender.sendMessage("§eSyncing balance for " + target.getName() + "...");
            
            walletManager.fetchBalanceFromLNbits(target)
                .thenAccept(balance -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        sender.sendMessage("§a" + target.getName() + " balance: §f" + formatSats(balance));
                    });
                })
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        sender.sendMessage("§cFailed to sync balance: " + ex.getMessage());
                    });
                    return null;
                });
            
            return true;
        }
        
        private boolean handleInvoices(CommandSender sender) {
            WebSocketInvoiceMonitor monitor = plugin.getInvoiceMonitor();
            
            if (monitor == null) {
                sender.sendMessage("§cWebSocket invoice monitor not initialized.");
                return true;
            }
            
            Map<String, WebSocketInvoiceMonitor.PendingInvoice> pending = monitor.getAllPendingInvoices();
            
            sender.sendMessage("§6§l=== Pending Invoices (" + pending.size() + ") ===");
            
            if (pending.isEmpty()) {
                sender.sendMessage("§7No pending invoices.");
                return true;
            }
            
            sender.sendMessage("");
            pending.forEach((hash, invoice) -> {
                Player p = Bukkit.getPlayer(invoice.playerUuid);
                String name = p != null ? p.getName() : invoice.playerName;
                
                sender.sendMessage(String.format("§e%s §7- §f%s §7(%dm ago)", 
                    name,
                    formatSats(invoice.amountSats),
                    invoice.getAgeMinutes()
                ));
                sender.sendMessage("  §7Hash: §f" + hash.substring(0, 16) + "...");
                if (invoice.memo != null && !invoice.memo.isEmpty()) {
                    sender.sendMessage("  §7Memo: §f" + invoice.memo);
                }
            });
            
            return true;
        }
        
        private boolean handleHealth(CommandSender sender) {
            sender.sendMessage("§eChecking backend health...");
            
            plugin.getLnService().getWalletInfoAsync()
                .thenAccept(response -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (response.success) {
                            sender.sendMessage("§a✓ Backend is healthy!");
                            sender.sendMessage("§7Status: §f" + response.statusCode);
                        } else {
                            sender.sendMessage("§c✗ Backend health check failed!");
                            sender.sendMessage("§7Error: §f" + response.error);
                            sender.sendMessage("§7Status: §f" + response.statusCode);
                        }
                    });
                })
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        sender.sendMessage("§c✗ Connection failed!");
                        sender.sendMessage("§7Error: §f" + ex.getMessage());
                    });
                    return null;
                });
            
            return true;
        }
        
        private boolean handleWebSocket(CommandSender sender) {
            WebSocketInvoiceMonitor monitor = plugin.getInvoiceMonitor();
            
            if (monitor == null) {
                sender.sendMessage("§cWebSocket monitor not initialized.");
                return true;
            }
            
            sender.sendMessage("§6§l=== WebSocket Status ===");
            sender.sendMessage("");
            sender.sendMessage("§eActive Connections: §f" + monitor.getActiveConnections());
            sender.sendMessage("§ePending Invoices: §f" + monitor.getPendingCount());
            sender.sendMessage("§eTotal Processed: §f" + monitor.getTotalProcessed());
            sender.sendMessage("§eTotal Expired: §f" + monitor.getTotalExpired());
            sender.sendMessage("§eReconnect Attempts: §f" + monitor.getReconnectAttempts());
            sender.sendMessage("");
            sender.sendMessage("§7WebSocket provides real-time payment notifications");
            sender.sendMessage("§7Connections are per-wallet and auto-managed");
            
            return true;
        }
        
        private boolean handleMetrics(CommandSender sender) {
            sender.sendMessage("§6§l=== Detailed Metrics ===");
            sender.sendMessage("");
            
            // Show operation timings
            Map<String, PluginMetrics.PerformanceMetric> timings = metrics.getAllTimings();
            
            if (!timings.isEmpty()) {
                sender.sendMessage("§eOperation Timings:");
                timings.forEach((op, metric) -> {
                    sender.sendMessage(String.format("§7  %s: §f%s", op, metric.toString()));
                });
                sender.sendMessage("");
            }
            
            // Show error breakdown
            Map<String, Integer> errors = metrics.getErrorBreakdown();
            if (errors.values().stream().anyMatch(v -> v > 0)) {
                sender.sendMessage("§eError Breakdown:");
                errors.forEach((type, count) -> {
                    if (count > 0) {
                        sender.sendMessage(String.format("§7  %s: §f%d", type, count));
                    }
                });
            }
            
            return true;
        }
        
        private boolean handleReload(CommandSender sender) {
            sender.sendMessage("§eReloading configuration...");
            
            try {
                plugin.reloadConfig();
                plugin.getWalletManager().reload();
                
                sender.sendMessage("§aConfiguration reloaded successfully!");
                sender.sendMessage("§7Note: WebSocket connections will reconnect automatically");
                sender.sendMessage("§7Some changes may require a restart to take effect.");
            } catch (Exception e) {
                sender.sendMessage("§cFailed to reload: " + e.getMessage());
                plugin.getDebugLogger().error("Config reload failed", e);
            }
            
            return true;
        }
        
        private String formatSats(long sats) {
            if (sats >= 100_000_000) {
                return String.format("%.8f BTC", sats / 100_000_000.0);
            }
            return String.format("%,d sats", sats);
        }
        
        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (!sender.hasPermission("lightning.admin")) {
                return Collections.emptyList();
            }
            
            if (args.length == 1) {
                return Arrays.asList("stats", "wallets", "sync", "invoices", "health", 
                                "websocket", "metrics", "reload");
            }
            
            if (args.length == 2 && args[0].equalsIgnoreCase("sync")) {
                return null; // Default to online player names
            }
            
            return Collections.emptyList();
        }
    }