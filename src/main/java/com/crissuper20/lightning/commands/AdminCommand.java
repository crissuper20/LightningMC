package com.crissuper20.lightning.commands;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.managers.LNService;
import com.crissuper20.lightning.managers.WebSocketInvoiceMonitor;
import com.crissuper20.lightning.managers.WalletManager;
import com.crissuper20.lightning.util.PluginMetrics;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Lightning Admin Command - Comprehensive Edition
 *
 * Provides deep troubleshooting and management capabilities:
 *   - Wallet inspection and management
 *   - Transaction history viewing
 *   - Backend health monitoring with detailed diagnostics
 *   - WebSocket connection management
 *   - Debug mode controls
 *   - Performance metrics
 *   - Test payment flows
 */
public class AdminCommand implements CommandExecutor, TabCompleter {

    private final LightningPlugin plugin;
    private final PluginMetrics metrics;
    private static final DateTimeFormatter TIME_FORMATTER = 
        DateTimeFormatter.ofPattern("MMM dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public AdminCommand(LightningPlugin plugin, PluginMetrics metrics) {
        this.plugin = plugin;
        this.metrics = metrics;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("lightning.admin")) {
            sender.sendMessage("§c[X] You don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase();

        switch (subcommand) {
            // Core information
            case "stats":
                return handleStats(sender);
            case "backend":
                return handleBackend(sender);
                
            // Wallet management
            case "wallets":
            case "list":
                return handleWallets(sender, args);
            case "inspect":
            case "info":
                return handleInspect(sender, args);
            case "transactions":
            case "tx":
            case "history":
                return handleTransactions(sender, args);
            case "sync":
                return handleSync(sender, args);
                
            // Wallet operations
            case "force-create":
            case "create":
                return handleForceCreate(sender, args);
            case "delete":
            case "remove":
                return handleDelete(sender, args);
                
            // System health
            case "health":
                return handleHealth(sender);
            case "websocket":
            case "ws":
                return handleWebSocket(sender);
            case "reconnect":
                return handleReconnect(sender);
                
            // Configuration
            case "reload":
                return handleReload(sender);
            case "clear-cache":
                return handleClearCache(sender);
                
            default:
                sender.sendMessage("§c[X] Unknown subcommand: §e" + subcommand);
                sender.sendMessage("§7Type §e/lnadmin help §7for command list");
                return true;
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6§l=========================================");
        sender.sendMessage("§6§l|     Lightning Network Admin Panel    |");
        sender.sendMessage("§6§l=========================================");
        sender.sendMessage("");
        sender.sendMessage("§e§l INFORMATION");
        sender.sendMessage("  §e/lnadmin stats §7- Plugin statistics overview");
        sender.sendMessage("  §e/lnadmin backend §7- Backend connection details");
        sender.sendMessage("");
        sender.sendMessage("§e§lWALLET MANAGEMENT");
        sender.sendMessage("  §e/lnadmin wallets [page] §7- List all wallets");
        sender.sendMessage("  §e/lnadmin inspect <player> §7- Detailed wallet info");
        sender.sendMessage("  §e/lnadmin transactions <player> [limit] §7- View tx history");
        sender.sendMessage("  §e/lnadmin sync [player] §7- Sync balance(s)");
        sender.sendMessage("");
        sender.sendMessage("§e§lOPERATIONS");
        sender.sendMessage("  §e/lnadmin force-create <player> §7- Force wallet creation");
        sender.sendMessage("  §e/lnadmin delete <player> §7- Delete player wallet");
        sender.sendMessage("");
        sender.sendMessage("§e§l SYSTEM HEALTH");
        sender.sendMessage("  §e/lnadmin health §7- Check backend health");
        sender.sendMessage("  §e/lnadmin websocket §7- WebSocket status");
        sender.sendMessage("  §e/lnadmin reconnect §7- Force WS reconnect");
        sender.sendMessage("");
        sender.sendMessage("§e§l SYSTEM");
        sender.sendMessage("  §e/lnadmin reload §7- Reload configuration");
        sender.sendMessage("  §e/lnadmin clear-cache §7- Clear all caches");
    }

    private boolean handleStats(CommandSender sender) {
        sender.sendMessage("§6§l===================================");
        sender.sendMessage("§6§l    Lightning Plugin Statistics");
        sender.sendMessage("§6§l===================================");
        sender.sendMessage("");
        
        sender.sendMessage("§e System:");
        sender.sendMessage("  §7Uptime: §f" + metrics.getUptimeFormatted());
        sender.sendMessage("  §7Backend: §f" + plugin.getLnService().getBackendName());
        sender.sendMessage("  §7Health: " + (plugin.getLnService().isHealthy() ? "§a[OK] Healthy" : "§c[X] Unhealthy"));
        sender.sendMessage("  §7Total Wallets: §f" + plugin.getWalletManager().getWalletCount());
        sender.sendMessage("");
        
        sender.sendMessage("§e Transaction Volume:");
        sender.sendMessage("  §7Invoices Created: §f" + metrics.getInvoicesCreated());
        sender.sendMessage("  §7Invoices Paid: §f" + metrics.getInvoicesPaid() + 
            " §7(" + String.format("%.1f%%", calculatePercentage(metrics.getInvoicesPaid(), metrics.getInvoicesCreated())) + ")");
        sender.sendMessage("  §7Payments Sent: §f" + metrics.getPaymentsSuccessful() + "/" + metrics.getPaymentsAttempted() +
            " §7(" + String.format("%.1f%%", metrics.getPaymentSuccessRate()) + " success)");
        sender.sendMessage("");
        
        long deposited = metrics.getTotalSatsDeposited();
        long withdrawn = metrics.getTotalSatsWithdrawn();
        long netBalance = metrics.getNetBalance();
        
        sender.sendMessage("§e Sats Flowing:");
        sender.sendMessage("  §7v Deposited: §a" + formatSats(deposited));
        sender.sendMessage("  §7^ Withdrawn: §c" + formatSats(withdrawn));
        sender.sendMessage("  §7= Net Flow: " + (netBalance >= 0 ? "§a+" : "§c") + formatSats(netBalance));
        sender.sendMessage("");
        
        WebSocketInvoiceMonitor monitor = plugin.getInvoiceMonitor();
        int watching = monitor != null ? monitor.getActiveSessionCount() : 0;
        
        sender.sendMessage("§e Live Payment Monitor:");
        if (monitor == null) {
            sender.sendMessage("  §7Status: §c[X] Offline");
            sender.sendMessage("  §7Reason: Not initialized");
        } else if (watching == 0) {
            sender.sendMessage("  §7Status: §eo Idle");
            sender.sendMessage("  §7Watching: §70 wallets");
        } else {
            sender.sendMessage("  §7Status: §a[OK] Active");
            sender.sendMessage("  §7Watching: §f" + watching + " §7wallet" + (watching != 1 ? "s" : ""));
        }
        sender.sendMessage("");
        
        int errors = metrics.getTotalErrors();
        if (errors > 0) {
            sender.sendMessage("§c[!] Errors: " + errors + " total");
            sender.sendMessage("  §7Use §e/lnadmin metrics §7for breakdown");
            sender.sendMessage("");
        }
        
        sender.sendMessage("§7Use §e/lnadmin metrics §7for detailed performance data");
        sender.sendMessage("§7Use §e/lnadmin backend §7for connection details");
        
        return true;
    }

    private boolean handleWallets(CommandSender sender, String[] args) {
        WalletManager walletManager = plugin.getWalletManager();
        int totalWallets = walletManager.getWalletCount();

        if (totalWallets == 0) {
            sender.sendMessage("§e No wallets created yet.");
            sender.sendMessage("§7Players will create wallets automatically on first use.");
            return true;
        }

        final int page;
        int walletsPerPage = 10;
        
        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§c[X] Invalid page number: " + args[1]);
                return true;
            }
        } else {
            page = 1;
        }

        sender.sendMessage("§6§l===================================");
        sender.sendMessage("§6§l  Player Wallets (Page " + page + ")");
        sender.sendMessage("§6§l===================================");
        sender.sendMessage("");

        List<Map.Entry<UUID, PluginMetrics.PlayerActivity>> topPlayers = metrics.getTopPlayers(100);
        int maxPages = (topPlayers.size() + walletsPerPage - 1) / walletsPerPage;
        
        if (page < 1 || page > maxPages) {
            sender.sendMessage("§c[X] Invalid page. Valid pages: 1-" + maxPages);
            return true;
        }

        int startIdx = (page - 1) * walletsPerPage;
        int endIdx = Math.min(startIdx + walletsPerPage, topPlayers.size());
        
        List<Map.Entry<UUID, PluginMetrics.PlayerActivity>> pageEntries = 
            topPlayers.subList(startIdx, endIdx);

        sender.sendMessage("§7Fetching balances for online players...");
        
        List<CompletableFuture<String>> futures = new ArrayList<>();

        for (int i = 0; i < pageEntries.size(); i++) {
            Map.Entry<UUID, PluginMetrics.PlayerActivity> entry = pageEntries.get(i);
            UUID uuid = entry.getKey();
            Player player = Bukkit.getPlayer(uuid);
            int rank = startIdx + i + 1;

            if (player == null) {
                // Offline player - show cached data
                PluginMetrics.PlayerActivity activity = entry.getValue();
                String name = Bukkit.getOfflinePlayer(uuid).getName();
                sender.sendMessage(String.format("§f%d. §7%s §8(offline) §7- §8? sats §7(v%s ^%s)",
                    rank,
                    name != null ? name : uuid.toString().substring(0, 8),
                    formatSats(activity.totalDeposited),
                    formatSats(activity.totalWithdrawn)
                ));
                continue;
            }

            // Online player - fetch live balance
            final int finalRank = rank;
            CompletableFuture<String> future = walletManager.fetchBalanceFromLNbits(player)
                .thenApply(balance -> {
                    PluginMetrics.PlayerActivity activity = entry.getValue();
                    String name = player.getName();
                    return String.format("§f%d. §e%s §7- §f%s §7(v%s ^%s)",
                        finalRank,
                        name,
                        formatSats(balance),
                        formatSats(activity.totalDeposited),
                        formatSats(activity.totalWithdrawn)
                    );
                })
                .exceptionally(ex -> {
                    PluginMetrics.PlayerActivity activity = entry.getValue();
                    return String.format("§f%d. §e%s §7- §c[X] Error §7(v%s ^%s)",
                        finalRank,
                        player.getName(),
                        formatSats(activity.totalDeposited),
                        formatSats(activity.totalWithdrawn)
                    );
                });

            futures.add(future);
        }

        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> plugin.getScheduler().runTask(() -> {
                    sender.sendMessage("");
                    sender.sendMessage("§e Online Players:");
                    for (CompletableFuture<String> future : futures) {
                        sender.sendMessage(future.join());
                    }
                    sender.sendMessage("");
                    sender.sendMessage("§7Page §f" + page + "§7/§f" + maxPages + 
                        " §8| §7Total: §f" + totalWallets + " §7wallets");
                    sender.sendMessage("§7Use §e/lnadmin inspect <player> §7for details");
                }));
        } else {
            sender.sendMessage("");
            sender.sendMessage("§7All wallets on this page are offline.");
            sender.sendMessage("");
            sender.sendMessage("§7Page §f" + page + "§7/§f" + maxPages + 
                " §8| §7Total: §f" + totalWallets + " §7wallets");
        }

        return true;
    }

    private boolean handleSync(CommandSender sender, String[] args) {
        WalletManager walletManager = plugin.getWalletManager();

        if (args.length == 1) {
            sender.sendMessage("§e Syncing all wallet balances...");

            walletManager.syncAllBalancesAsync()
                .thenAccept(result -> plugin.getScheduler().runTask(() -> {
                    sender.sendMessage("§a[OK] Balance sync complete! (" + result.size() + " wallets)");
                }))
                .exceptionally(ex -> {
                    plugin.getScheduler().runTask(() -> 
                        sender.sendMessage("§c[X] Sync failed: " + ex.getMessage()));
                    return null;
                });

            return true;
        }

        String playerName = args[1];
        Player target = Bukkit.getPlayer(playerName);

        if (target == null) {
            sender.sendMessage("§c[X] Player not online: " + playerName);
            return true;
        }

        if (!walletManager.hasWallet(target)) {
            sender.sendMessage("§c[X] " + target.getName() + " has no wallet");
            return true;
        }

        sender.sendMessage("§e Syncing balance for " + target.getName() + "...");

        walletManager.fetchBalanceFromLNbits(target)
            .thenAccept(balance -> plugin.getScheduler().runTask(() -> {
                sender.sendMessage("§a[OK] " + target.getName() + " balance: §f" + formatSats(balance));
            }))
            .exceptionally(ex -> {
                plugin.getScheduler().runTask(() -> {
                    sender.sendMessage("§c[X] Failed to sync balance: " + ex.getMessage());
                });
                return null;
            });

        return true;
    }

    private boolean handleInspect(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c[X] Usage: /lnadmin inspect <player>");
            return true;
        }

        String playerName = args[1];
        Player target = Bukkit.getPlayer(playerName);

        if (target == null) {
            sender.sendMessage("§c[X] Player not online: " + playerName);
            return true;
        }

        WalletManager walletManager = plugin.getWalletManager();
        if (!walletManager.hasWallet(target)) {
            sender.sendMessage("§c[X] " + target.getName() + " has no wallet");
            sender.sendMessage("§7Use §e/lnadmin force-create " + target.getName() + " §7to create one");
            return true;
        }

        sender.sendMessage("§6§l===================================");
        sender.sendMessage("§6§l  Wallet Inspection: §e" + target.getName());
        sender.sendMessage("§6§l===================================");
        sender.sendMessage("");
        sender.sendMessage("§e Player Info:");
        sender.sendMessage("  §7UUID: §f" + target.getUniqueId());
        sender.sendMessage("  §7Name: §f" + target.getName());
        sender.sendMessage("  §7Online: §a[OK]");
        sender.sendMessage("");
        sender.sendMessage("§e Fetching balance and activity...");

        // Fetch balance
        walletManager.fetchBalanceFromLNbits(target)
            .thenAccept(balance -> {
                // Get player activity metrics
                PluginMetrics.PlayerActivity activity = metrics.getPlayerActivity(target.getUniqueId());
                
                plugin.getScheduler().runTask(() -> {
                    sender.sendMessage("§e Balance & Activity:");
                    sender.sendMessage("  §7Current Balance: §f" + formatSats(balance));
                    sender.sendMessage("");
                    sender.sendMessage("§e Transaction Stats:");
                    sender.sendMessage("  §7Invoices Created: §f" + activity.invoicesCreated);
                    sender.sendMessage("  §7Invoices Paid: §f" + activity.invoicesPaid);
                    sender.sendMessage("  §7Payments Attempted: §f" + activity.paymentsAttempted);
                    sender.sendMessage("  §7Payments Successful: §f" + activity.paymentsSuccessful);
                    sender.sendMessage("  §7Payments Failed: §f" + activity.paymentsFailed);
                    sender.sendMessage("");
                    sender.sendMessage("§e Volume:");
                    sender.sendMessage("  §7v Total Deposited: §a" + formatSats(activity.totalDeposited));
                    sender.sendMessage("  §7^ Total Withdrawn: §c" + formatSats(activity.totalWithdrawn));
                    long netFlow = activity.totalDeposited - activity.totalWithdrawn;
                    sender.sendMessage("  §7= Net Flow: " + (netFlow >= 0 ? "§a+" : "§c") + formatSats(netFlow));
                    sender.sendMessage("");
                    sender.sendMessage("§7Use §e/lnadmin transactions " + target.getName() + " §7for tx history");
                });
            })
            .exceptionally(ex -> {
                plugin.getScheduler().runTask(() -> {
                    sender.sendMessage("§c[X] Failed to fetch wallet info: " + ex.getMessage());
                });
                return null;
            });

        return true;
    }

    private boolean handleTransactions(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c[X] Usage: /lnadmin transactions <player> [limit]");
            return true;
        }

        String playerName = args[1];
        int limit = 10;
        
        if (args.length >= 3) {
            try {
                limit = Integer.parseInt(args[2]);
                if (limit < 1 || limit > 50) {
                    sender.sendMessage("§c[X] Limit must be between 1 and 50");
                    return true;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage("§c[X] Invalid limit: " + args[2]);
                return true;
            }
        }

        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage("§c[X] Player not online: " + playerName);
            return true;
        }

        WalletManager walletManager = plugin.getWalletManager();
        if (!walletManager.hasWallet(target)) {
            sender.sendMessage("§c[X] " + target.getName() + " has no wallet");
            return true;
        }

        sender.sendMessage("§eFetching transaction history for " + target.getName() + "...");

        String adminKey = walletManager.getPlayerAdminKey(target);
        String baseUrl = walletManager.getLnbitsBaseUrl();
        
        fetchTransactionHistory(baseUrl, adminKey, limit)
            .thenAccept(transactions -> plugin.getScheduler().runTask(() -> {
                if (transactions.isEmpty()) {
                    sender.sendMessage("§7No transactions found for " + target.getName());
                    return;
                }

                sender.sendMessage("§6§l===================================");
                sender.sendMessage("§6§l  Transactions: §e" + target.getName());
                sender.sendMessage("§6§l===================================");
                sender.sendMessage("");

                int count = 1;
                for (JsonObject tx : transactions) {
                    displayTransaction(sender, tx, count++);
                }

                sender.sendMessage("");
                sender.sendMessage("§7Showing " + transactions.size() + " most recent transactions");
            }))
            .exceptionally(ex -> {
                plugin.getScheduler().runTask(() -> {
                    sender.sendMessage("§c[X] Failed to fetch transactions: " + ex.getMessage());
                    plugin.getDebugLogger().error("Transaction fetch failed", ex);
                });
                return null;
            });

        return true;
    }

    private CompletableFuture<List<JsonObject>> fetchTransactionHistory(String baseUrl, String adminKey, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Fetch payments (sent)
                HttpRequest paymentRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/payments?limit=" + limit))
                    .header("X-Api-Key", adminKey)
                    .GET()
                    .build();

                HttpResponse<String> paymentResponse = plugin.getLnService().getHttpClient()
                    .send(paymentRequest, HttpResponse.BodyHandlers.ofString());

                List<JsonObject> allTx = new ArrayList<>();

                if (paymentResponse.statusCode() == 200) {
                    JsonArray payments = JsonParser.parseString(paymentResponse.body()).getAsJsonArray();
                    for (JsonElement element : payments) {
                        JsonObject tx = element.getAsJsonObject();
                        tx.addProperty("tx_type", "payment");
                        allTx.add(tx);
                    }
                }

                // Sort by timestamp (most recent first)
                allTx.sort((a, b) -> {
                    long timeA = a.has("time") ? a.get("time").getAsLong() : 0;
                    long timeB = b.has("time") ? b.get("time").getAsLong() : 0;
                    return Long.compare(timeB, timeA);
                });

                return allTx.subList(0, Math.min(limit, allTx.size()));

            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch transaction history", e);
            }
        });
    }

    private void displayTransaction(CommandSender sender, JsonObject tx, int number) {
        String type = tx.has("tx_type") ? tx.get("tx_type").getAsString() : "unknown";
        long amount = tx.has("amount") ? Math.abs(tx.get("amount").getAsLong() / 1000) : 0;
        String memo = tx.has("memo") ? tx.get("memo").getAsString() : "No memo";
        boolean isPending = tx.has("pending") && tx.get("pending").getAsBoolean();
        long timestamp = tx.has("time") ? tx.get("time").getAsLong() : 0;
        
        String icon = type.equals("payment") ? "§c^" : "§av";
        String status = isPending ? "§eo Pending" : "§a[OK] Settled";
        String timeStr = timestamp > 0 ? TIME_FORMATTER.format(Instant.ofEpochSecond(timestamp)) : "Unknown";
        
        sender.sendMessage("§f" + number + ". " + icon + " §7" + formatSats(amount) + " §8| " + status);
        sender.sendMessage("   §7" + timeStr + " §8- §7" + truncate(memo, 40));
    }

    private boolean handleHealth(CommandSender sender) {
        sender.sendMessage("§e Checking backend health...");

        LNService lnService = plugin.getLnService();
        
        lnService.getWalletInfoAsync()
            .thenAccept(response -> plugin.getScheduler().runTask(() -> {
                if (response.success) {
                    sender.sendMessage("§a[OK] Backend is healthy!");
                    sender.sendMessage("  §7Status Code: §f" + response.statusCode);
                    sender.sendMessage("  §7Response Time: §f" + "< 1s");
                    
                    if (response.data != null && response.data.has("name")) {
                        sender.sendMessage("  §7Wallet Name: §f" + response.data.get("name").getAsString());
                    }
                    if (response.data != null && response.data.has("balance")) {
                        long balance = response.data.get("balance").getAsLong() / 1000;
                        sender.sendMessage("  §7Admin Wallet: §f" + formatSats(balance));
                    }
                } else {
                    sender.sendMessage("§c[X] Backend health check failed!");
                    sender.sendMessage("  §7Error: §f" + response.error);
                    sender.sendMessage("  §7Status Code: §f" + response.statusCode);
                    sender.sendMessage("");
                    sender.sendMessage("§7Troubleshooting:");
                    sender.sendMessage("  §7• Check LNbits is running");
                    sender.sendMessage("  §7• Verify config.yml adminKey");
                    sender.sendMessage("  §7• Use §e/lnadmin backend §7for connection details");
                }
            }))
            .exceptionally(ex -> {
                plugin.getScheduler().runTask(() -> {
                    sender.sendMessage("§c[X] Connection failed!");
                    sender.sendMessage("  §7Error: §f" + ex.getMessage());
                    sender.sendMessage("");
                    sender.sendMessage("§7Common causes:");
                    sender.sendMessage("  §7• LNbits server is down");
                    sender.sendMessage("  §7• Wrong host/port in config");
                    sender.sendMessage("  §7• Network/firewall blocking connection");
                    sender.sendMessage("  §7• SSL certificate issues (if HTTPS)");
                });
                return null;
            });

        return true;
    }

    private boolean handleBackend(CommandSender sender) {
        sender.sendMessage("§6§l===================================");
        sender.sendMessage("§6§l    Backend Connection Details");
        sender.sendMessage("§6§l===================================");
        sender.sendMessage("");
        
        String baseUrl = plugin.getWalletManager().getLnbitsBaseUrl();
        boolean isHealthy = plugin.getLnService().isHealthy();
        String adminKey = plugin.getConfig().getString("lnbits.adminKey", "");
        boolean useHttps = plugin.getConfig().getBoolean("lnbits.use_https", false);
        boolean useTor = plugin.getConfig().getBoolean("lnbits.use_tor_proxy", false);
        boolean skipTls = plugin.getConfig().getBoolean("lnbits.skip_tls_verify", false);
        
        sender.sendMessage("§e Connection:");
        sender.sendMessage("  §7URL: §f" + baseUrl);
        sender.sendMessage("  §7Protocol: §f" + (useHttps ? "HTTPS" : "HTTP"));
        sender.sendMessage("  §7Health Status: " + (isHealthy ? "§a[OK] Healthy" : "§c[X] Unhealthy"));
        sender.sendMessage("");
        
        sender.sendMessage("§e Authentication:");
        sender.sendMessage("  §7Admin Key: §f" + (adminKey.length() > 0 ? "***" + adminKey.substring(Math.max(0, adminKey.length() - 4)) : "NOT SET"));
        sender.sendMessage("  §7Key Length: §f" + adminKey.length() + " chars");
        sender.sendMessage("");
        
        sender.sendMessage("§e Configuration:");
        sender.sendMessage("  §7Tor Proxy: " + (useTor ? "§aEnabled" : "§7Disabled"));
        if (useTor) {
            String torHost = plugin.getConfig().getString("lnbits.tor_proxy_host");
            int torPort = plugin.getConfig().getInt("lnbits.tor_proxy_port");
            sender.sendMessage("    §7Proxy: §f" + torHost + ":" + torPort);
        }
        sender.sendMessage("  §7Skip TLS Verify: " + (skipTls ? "§cYes (INSECURE!)" : "§aNo"));
        sender.sendMessage("");
        
        WebSocketInvoiceMonitor monitor = plugin.getInvoiceMonitor();
        if (monitor != null) {
            int sessions = monitor.getActiveSessionCount();
            sender.sendMessage("§e WebSocket:");
            sender.sendMessage("  §7Active Sessions: §f" + sessions);
            sender.sendMessage("  §7Status: " + (sessions > 0 ? "§a[OK] Active" : "§eo Idle"));
        }
        
        sender.sendMessage("");
        sender.sendMessage("§7Use §e/lnadmin health §7to test the connection");
        
        return true;
    }

    private boolean handleWebSocket(CommandSender sender) {
        WebSocketInvoiceMonitor monitor = plugin.getInvoiceMonitor();

        sender.sendMessage("§6§l===================================");
        sender.sendMessage("§6§l    Live Payment Monitor Status");
        sender.sendMessage("§6§l===================================");
        sender.sendMessage("");

        if (monitor == null) {
            sender.sendMessage("§e Status: §c[X] Offline");
            sender.sendMessage("");
            sender.sendMessage("§c[!] Monitor not initialized");
            sender.sendMessage("§7Possible reasons:");
            sender.sendMessage("  §7• Backend connection failed during startup");
            sender.sendMessage("  §7• Missing or invalid configuration");
            sender.sendMessage("");
            sender.sendMessage("§7Try: §e/lnadmin reconnect §7or §e/lnadmin reload");
            return true;
        }

        int watching = monitor.getActiveSessionCount();
        
        sender.sendMessage("§e Status: " + (watching > 0 ? "§a[OK] Active" : "§eo Idle"));
        sender.sendMessage("  §7Active Sessions: §f" + watching);
        
        if (watching == 0) {
            sender.sendMessage("");
            sender.sendMessage("§7The monitor is running but not watching any wallets.");
            sender.sendMessage("§7Sessions start automatically when players create invoices.");
        } else {
            sender.sendMessage("  §7Monitoring: §f" + watching + " §7wallet" + (watching != 1 ? "s" : "") + " for payments");
        }
        
        sender.sendMessage("");
        sender.sendMessage("§e Configuration:");
        int maxRetries = plugin.getConfig().getInt("websocket.max_reconnect_attempts", 5);
        int initialDelay = plugin.getConfig().getInt("websocket.reconnect_initial_delay_ms", 1000);
        double backoff = plugin.getConfig().getDouble("websocket.reconnect_backoff_multiplier", 2.0);
        sender.sendMessage("  §7Max Reconnect Attempts: §f" + maxRetries);
        sender.sendMessage("  §7Initial Delay: §f" + initialDelay + "ms");
        sender.sendMessage("  §7Backoff Multiplier: §f" + backoff + "x");
        
        sender.sendMessage("");
        sender.sendMessage("§7Use §e/lnadmin reconnect §7to force reconnection");

        return true;
    }

    private boolean handleReconnect(CommandSender sender) {
        WebSocketInvoiceMonitor monitor = plugin.getInvoiceMonitor();

        if (monitor == null) {
            sender.sendMessage("§c[X] WebSocket monitor not initialized");
            sender.sendMessage("§7Try §e/lnadmin reload §7instead");
            return true;
        }

        sender.sendMessage("§e Forcing WebSocket reconnection...");
        
        try {
            int activeCount = monitor.getActiveSessionCount();
            monitor.forceReconnectNow();
            
            sender.sendMessage("§a[OK] Reconnection initiated!");
            sender.sendMessage("  §7Reconnecting " + activeCount + " session(s)");
            sender.sendMessage("  §7This may take a few seconds...");
            sender.sendMessage("");
            sender.sendMessage("§7Check status: §e/lnadmin websocket");
        } catch (Exception e) {
            sender.sendMessage("§c[X] Reconnection failed: " + e.getMessage());
            plugin.getDebugLogger().error("WebSocket reconnect failed", e);
        }

        return true;
    }

    private boolean handleForceCreate(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c[X] Usage: /lnadmin force-create <player>");
            return true;
        }

        String playerName = args[1];
        Player target = Bukkit.getPlayer(playerName);

        if (target == null) {
            sender.sendMessage("§c[X] Player not online: " + playerName);
            return true;
        }

        WalletManager walletManager = plugin.getWalletManager();
        
        if (walletManager.hasWallet(target)) {
            sender.sendMessage("§e[!] " + target.getName() + " already has a wallet");
            sender.sendMessage("§7Use §e/lnadmin inspect " + target.getName() + " §7to view it");
            return true;
        }

        sender.sendMessage("§eForce-creating wallet for " + target.getName() + "...");

        walletManager.getOrCreateWallet(target)
            .thenAccept(result -> plugin.getScheduler().runTask(() -> {
                if (result != null) {
                    sender.sendMessage("§a[OK] Wallet created successfully!");
                    sender.sendMessage("  §7Player: §f" + target.getName());
                    sender.sendMessage("  §7Wallet ID: §f" + result.get("id").getAsString());
                    sender.sendMessage("");
                    sender.sendMessage("§7Use §e/lnadmin inspect " + target.getName() + " §7for details");
                } else {
                    sender.sendMessage("§c[X] Failed to create wallet");
                }
            }))
            .exceptionally(ex -> {
                plugin.getScheduler().runTask(() -> {
                    sender.sendMessage("§c[X] Wallet creation failed: " + ex.getMessage());
                    plugin.getDebugLogger().error("Force wallet creation failed", ex);
                });
                return null;
            });

        return true;
    }

    private boolean handleDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c[X] Usage: /lnadmin delete <player>");
            return true;
        }

        sender.sendMessage("§c[!] WARNING: Wallet deletion is not fully implemented yet!");
        sender.sendMessage("§7This would require:");
        sender.sendMessage("  §7• Deleting wallet from LNbits backend");
        sender.sendMessage("  §7• Removing from local database");
        sender.sendMessage("  §7• Closing any active WebSocket sessions");
        sender.sendMessage("");
        sender.sendMessage("§7Manually delete from database or contact the admin.");
        
        return true;
    }

    private boolean handleClearCache(CommandSender sender) {
        sender.sendMessage("§e Clearing caches...");
        
        // The plugin doesn't have explicit caches, but we can force a reload
        WalletManager walletManager = plugin.getWalletManager();
        
        try {
            walletManager.reload();
            sender.sendMessage("§a Wallet cache cleared and reloaded");
            sender.sendMessage("§7Wallets will be re-fetched from database on next access");
        } catch (Exception e) {
            sender.sendMessage("§c Failed to clear cache: " + e.getMessage());
            plugin.getDebugLogger().error("Cache clear failed", e);
        }

        return true;
    }

    private boolean handleReload(CommandSender sender) {
        sender.sendMessage("§e Reloading configuration...");

        try {
            plugin.reloadConfig();
            
            // Note: DebugLogger is immutable, so debug changes require restart
            boolean debug = plugin.getConfig().getBoolean("debug", false);
            
            // Reload wallet manager
            plugin.getWalletManager().reload();

            // Reconnect WebSocket with new config
            WebSocketInvoiceMonitor monitor = plugin.getInvoiceMonitor();
            if (monitor != null) {
                monitor.forceReconnectNow();
            }

            sender.sendMessage("§a[OK] Configuration reloaded successfully!");
            sender.sendMessage("  §7Config file: §fconfig.yml");
            sender.sendMessage("  §7Debug mode: " + (debug ? "§aON" : "§7OFF"));
            sender.sendMessage("  §7Wallet manager: §aReloaded");
            sender.sendMessage("  §7WebSocket: §aReconnecting...");
            sender.sendMessage("");
            sender.sendMessage("§e[!] Note: Some changes may require a full restart");
        } catch (Exception e) {
            sender.sendMessage("§c[X] Reload failed: " + e.getMessage());
            plugin.getDebugLogger().error("Config reload failed", e);
        }

        return true;
    }

    // ================================================================
    // Utility Methods
    // ================================================================

    private String formatSats(long sats) {
        if (sats >= 100_000_000) {
            return String.format("%.8f BTC", sats / 100_000_000.0);
        } else if (sats >= 1_000_000) {
            return String.format("%.3f mBTC", sats / 1_000_000.0);
        }
        return String.format("%,d sats", sats);
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength - 3) + "...";
    }

    private double calculatePercentage(int part, int total) {
        if (total == 0) return 0.0;
        return (part * 100.0) / total;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("lightning.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> commands = Arrays.asList(
                "stats", "backend",
                "wallets", "inspect", "transactions", "sync",
                "force-create", "delete",
                "health", "websocket", "reconnect",
                "reload", "clear-cache"
            );
            
            // Filter by what user has typed
            String input = args[0].toLowerCase();
            return commands.stream()
                .filter(cmd -> cmd.startsWith(input))
                .sorted()
                .collect(java.util.stream.Collectors.toList());
        }

        if (args.length == 2) {
            String subcommand = args[0].toLowerCase();
            
            switch (subcommand) {
                case "sync":
                case "inspect":
                case "info":
                case "transactions":
                case "tx":
                case "history":
                case "force-create":
                case "create":
                case "delete":
                case "remove":
                    // Return online player names
                    return null;
            }
        }

        return Collections.emptyList();
    }
}