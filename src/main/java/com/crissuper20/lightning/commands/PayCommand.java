package com.crissuper20.lightning.commands;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.managers.LNService;
import com.crissuper20.lightning.managers.WalletManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

/**
 * /pay <bolt11> 
 */
public class PayCommand implements CommandExecutor {
    
    private final LightningPlugin plugin;
    private final WalletManager walletManager;

    public PayCommand(LightningPlugin plugin) {
        this.plugin = plugin;
        this.walletManager = plugin.getWalletManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.formatError("This command can only be used by players."));
            return true;
        }

        Player player = (Player) sender;

        if (args.length < 1) {
            player.sendMessage(plugin.formatMessage("§cUsage: /pay <bolt11>"));
            return true;
        }

        String bolt11 = args[0];
        
        plugin.getDebugLogger().info("=== PAY COMMAND DEBUG ===");
        plugin.getDebugLogger().info("Raw arg[0]: [" + bolt11 + "]");
        plugin.getDebugLogger().info("Length: " + bolt11.length());
        plugin.getDebugLogger().info("Starts with: " + bolt11.substring(0, Math.min(20, bolt11.length())));
        plugin.getDebugLogger().info("Ends with: " + bolt11.substring(Math.max(0, bolt11.length() - 20)));
        plugin.getDebugLogger().info("Has whitespace: " + (bolt11.length() != bolt11.trim().length()));
        plugin.getDebugLogger().info("Contains newlines: " + bolt11.contains("\n"));
        plugin.getDebugLogger().info("Byte array length: " + bolt11.getBytes().length);
        plugin.getDebugLogger().info("========================");

        if (!bolt11.toLowerCase().startsWith("lnbc") && !bolt11.toLowerCase().startsWith("lntb")) {
            player.sendMessage(plugin.formatError("Invalid invoice format. Must start with 'lnbc' or 'lntb'"));
            return true;
        }

        if (!walletManager.hasWallet(player)) {
            player.sendMessage(plugin.formatMessage("§7Use §f/wallet §7to create a Lightning wallet first."));
            return true;
        }

        plugin.getDebugLogger().debug("Player " + player.getName() + " attempting to pay invoice");
        player.sendMessage(plugin.formatMessage("§7Processing payment..."));


        CompletableFuture<LNService.LNResponse<com.google.gson.JsonObject>> paymentFuture = 
            plugin.getLnService().payInvoiceForPlayer(player, bolt11);

        paymentFuture.thenAccept(response -> {
            if (response.success) {
                player.sendMessage(plugin.formatSuccess("§a Payment sent successfully!"));
                plugin.getDebugLogger().info("Player " + player.getName() + " successfully paid invoice");
            } else {
                player.sendMessage(plugin.formatError("Payment failed: " + response.error));
                plugin.getDebugLogger().debug("Payment failed for " + player.getName() + ": " + response.error);
            }
        }).exceptionally(ex -> {
            player.sendMessage(plugin.formatError("An error occurred while processing payment."));
            plugin.getDebugLogger().error("Payment error for " + player.getName(), ex);
            return null;
        });

        return true;
    }
}