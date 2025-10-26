package com.crissuper20.lightning.commands;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.managers.WalletManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

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
            sender.sendMessage(LightningPlugin.formatError("This command can only be used by players."));
            return true;
        }

        Player player = (Player) sender;

        // Check if player already has a wallet
        if (walletManager.hasWallet(player)) {
            String walletId = walletManager.getWalletId(player);
            player.sendMessage(LightningPlugin.formatMessage("§6=== Your Lightning Wallet ==="));
            player.sendMessage(LightningPlugin.formatMessage("§7Wallet ID: §f" + walletId));
            // TODO: Add balance and other wallet info here
            player.sendMessage(LightningPlugin.formatMessage("§6========================"));
            return true;
        }

        // Create new wallet
        try {
            String walletId = walletManager.createWallet(player);
            player.sendMessage(LightningPlugin.formatSuccess("§6New Wallet Created!"));
            player.sendMessage(LightningPlugin.formatMessage("§7Wallet ID: §f" + walletId));
            player.sendMessage(LightningPlugin.formatMessage("§7Use §f/wallet §7to view your wallet info"));
        } catch (Exception e) {
            player.sendMessage(LightningPlugin.formatError("Failed to create wallet: " + e.getMessage()));
            plugin.getDebugLogger().error("Wallet creation failed", e);
        }

        return true;
    }
}