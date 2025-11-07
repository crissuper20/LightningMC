package com.crissuper20.lightning.commands;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.managers.WalletManager;
import com.google.gson.JsonObject;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

public class WalletCommand implements CommandExecutor {
    
    private final LightningPlugin plugin; 
    private final WalletManager walletManager;

    public WalletCommand(LightningPlugin plugin, WalletManager walletManager) { 
        this.plugin = plugin;
        this.walletManager = walletManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.formatError("This command can only be used by players."));
            return true;
        }

        Player player = (Player) sender;

        // If player has a wallet, we fetch info; otherwise, we create one.
        if (walletManager.hasWallet(player)) {
            player.sendMessage(plugin.formatMessage("§7Fetching wallet information..."));
            displayWalletInfo(player);
        } else {
            // If player has no wallet, create one asynchronously
            player.sendMessage(plugin.formatMessage("§7Creating your Lightning wallet... please wait."));
            
            walletManager.getOrCreateWallet(player).thenAccept(wallet -> {
                if (wallet.has("id")) {
                    String walletId = wallet.get("id").getAsString();
                    // Schedule message back to the main Bukkit thread
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        player.sendMessage(plugin.formatSuccess("§6Wallet created successfully!"));
                        player.sendMessage(plugin.formatMessage("§7Wallet ID: §f" + walletId));
                        player.sendMessage(plugin.formatMessage("§7Use §f/wallet §7to check your info"));
                    });
                } else {
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.sendMessage(plugin.formatError("Failed to create your wallet. Check logs."))
                    );
                }
            }).exceptionally(ex -> {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    player.sendMessage(plugin.formatError("Error creating wallet: " + ex.getMessage()))
                );
                plugin.getDebugLogger().error("Wallet creation failed for " + player.getName(), ex);
                return null;
            });
        }

        return true;
    }
    
    /**
     * This method chains the asynchronous operations (getOrCreateWallet and getBalance)
     * to ensure the balance is only formatted AFTER it is fetched.
     */
    private void displayWalletInfo(Player player) {
        
        CompletableFuture<JsonObject> walletFuture = walletManager.getOrCreateWallet(player);
        CompletableFuture<Long> balanceFuture = walletManager.getBalance(player);
        
        // Combine the results of the two futures using thenCombine
        walletFuture.thenCombine(balanceFuture, (wallet, balance) -> {
            
            String adminKey = walletManager.getPlayerAdminKey(player);
            String walletId = wallet.get("id").getAsString();
            String formattedBalance = walletManager.formatBalance(balance); // **FIX 3: 'long' is passed directly**

            // Schedule final messages back to the main Bukkit thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.sendMessage(plugin.formatMessage("§6=== Your Lightning Wallet ==="));
                player.sendMessage(plugin.formatMessage("§7Wallet ID: §f" + walletId));
                player.sendMessage(plugin.formatMessage("§7Admin Key: §f" + adminKey.substring(0, 8) + "..."));
                player.sendMessage(plugin.formatMessage("§7Balance: §f" + formattedBalance));
                player.sendMessage(plugin.formatMessage("§6========================"));
            });
            return null; // The combined future doesn't need to return a value here
            
        }).exceptionally(ex -> {
            // Handle any exception that occurred during either wallet fetch or balance fetch
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.sendMessage(plugin.formatError("Error fetching wallet data: " + ex.getMessage()));
            });
            plugin.getDebugLogger().error("Wallet info display failed for " + player.getName(), ex);
            return null;
        });
    }
}