package com.crissuper20.lightning.commands;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.managers.LNService;
import com.crissuper20.lightning.managers.WalletManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

public class BalanceCommand implements CommandExecutor {
    private final LightningPlugin plugin;
    private final WalletManager walletManager;

    public BalanceCommand(LightningPlugin plugin) {
        this.plugin = plugin;
        this.walletManager = plugin.getWalletManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(LightningPlugin.formatError("This command can only be used by players."));
            return true;
        }

        Player player = (Player) sender;
        plugin.getDebugLogger().debug("Fetching balance for " + player.getName());

        if (!walletManager.hasWallet(player)) {
            player.sendMessage(LightningPlugin.formatMessage("§7Use §f/wallet §7to create a Lightning wallet first."));
            return true;
        }

        String walletId = walletManager.getWalletId(player);
        
        // Use async API to fetch balance
        CompletableFuture<LNService.LNResponse<Long>> futureBalance = plugin.getLnService().getBalanceAsync(walletId);
        
        // Send a temporary message to inform the player that the balance is being fetched
        player.sendMessage(LightningPlugin.formatMessage("§7Fetching your balance..."));

        futureBalance.thenAccept(response -> {
            if (response.success) {
                long balance = response.data;
                player.sendMessage(LightningPlugin.formatMessage("§7Balance: §f" + balance + " §7sats"));
            } else {
                player.sendMessage(LightningPlugin.formatError("Could not fetch balance: " + response.error));
            }
        }).exceptionally(e -> {
            player.sendMessage(LightningPlugin.formatError("An error occurred while fetching balance."));
            plugin.getDebugLogger().error("Error fetching balance for " + player.getName(), e);
            return null;
        });

        return true;
    }
}
