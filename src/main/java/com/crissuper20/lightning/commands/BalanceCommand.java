package com.crissuper20.lightning.commands;

import com.crissuper20.lightning.LightningPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BalanceCommand implements CommandExecutor {
    private final LightningPlugin plugin;

    public BalanceCommand(LightningPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;
        plugin.getDebugLogger().debug("Fetching balance for " + player.getName());

        // TODO: fetch from LNService
        player.sendMessage("lightning balance.");

        return true;
    }
}
