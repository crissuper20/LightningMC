package com.crissuper20.lightning.commands;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.managers.WalletManager;
import com.crissuper20.lightning.util.RateLimiter;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /balance command - Show Lightning wallet balance
 * 
 * FIXED:
 * - Always fetches fresh balance from LNbits
 * - Added rate limiting
 * - Better error handling
 * - Shows balance in multiple formats
 */
public class BalanceCommand implements CommandExecutor {

    private final LightningPlugin plugin;
    private final WalletManager walletManager;
    private final RateLimiter rateLimiter;

    public BalanceCommand(LightningPlugin plugin) {
        this.plugin = plugin;
        this.walletManager = plugin.getWalletManager();
        
        // Rate limit: 20 balance checks per minute
        int limit = plugin.getConfig().getInt("rate_limits.balance_checks_per_minute", 20);
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
            player.sendMessage("§cPlease wait before checking balance again. " + rateCheck.getRetryMessage());
            return true;
        }

        // Check if player has wallet
        if (!walletManager.hasWallet(player)) {
            player.sendMessage("§eYou don't have a wallet yet. Creating one...");
            
            walletManager.getOrCreateWallet(player)
                .thenAccept(wallet -> {
                    if (wallet.has("id")) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            player.sendMessage("§aWallet created! Checking balance...");
                            // Now fetch balance
                            fetchAndDisplayBalance(player);
                        });
                    } else {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            player.sendMessage("§cFailed to create wallet. Please contact an admin.");
                        });
                    }
                })
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.sendMessage("§cError creating wallet: " + ex.getMessage());
                        plugin.getDebugLogger().error("Wallet creation failed for " + player.getName(), ex);
                    });
                    return null;
                });
            
            return true;
        }

        // Fetch and display balance
        player.sendMessage("§eChecking balance...");
        fetchAndDisplayBalance(player);

        return true;
    }

    /**
     * Fetch balance from LNbits and display to player
     */
    private void fetchAndDisplayBalance(Player player) {
        // Use timing context for metrics
        try (var timer = plugin.getMetrics().startTiming("balance_check")) {
            
            walletManager.fetchBalanceFromLNbits(player)
                .thenAccept(balance -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        displayBalance(player, balance);
                    });
                })
                .exceptionally(ex -> {
                    plugin.getMetrics().recordApiError();
                    
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.sendMessage("§cFailed to fetch balance from Lightning backend.");
                        player.sendMessage("§7Error: " + ex.getMessage());
                        plugin.getDebugLogger().error("Balance fetch failed for " + player.getName(), ex);
                    });
                    return null;
                });
                
        } // Timer automatically closed here
    }

    /**
     * Display balance to player in a nice format
     */
    private void displayBalance(Player player, long balanceSats) {
        player.sendMessage("§8§m                                    ");
        player.sendMessage("§6§l⚡ Your Lightning Wallet");
        player.sendMessage("");
        
        // Show in sats
        player.sendMessage("§eBalance: §f" + String.format("%,d", balanceSats) + " §7sats");
        
        // Also show in BTC if significant amount
        if (balanceSats >= 100_000) {
            double btc = balanceSats / 100_000_000.0;
            player.sendMessage("§7        ≈ " + String.format("%.8f", btc) + " BTC");
        }
        
        // Show in USD if we have exchange rate (future enhancement)
        // double usd = balanceSats * getExchangeRate();
        // player.sendMessage("§7        ≈ $" + String.format("%.2f", usd) + " USD");
        
        player.sendMessage("");
        player.sendMessage("§7Use §f/invoice <amount> §7to deposit");
        player.sendMessage("§7Use §f/pay <bolt11> §7to send payment");
        player.sendMessage("§8§m                                    ");
    }
}