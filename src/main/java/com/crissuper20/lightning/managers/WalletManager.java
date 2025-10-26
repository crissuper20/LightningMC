package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class WalletManager {
    private final LightningPlugin plugin;
    private final File walletFile;
    private FileConfiguration walletConfig;
    private final Map<UUID, String> playerWallets;

    public WalletManager(LightningPlugin plugin) {
        this.plugin = plugin;
        this.walletFile = new File(plugin.getDataFolder(), "wallets.yml");
        this.playerWallets = new HashMap<>();
        loadWallets();
    }

    private void loadWallets() {
        if (!walletFile.exists()) {
            plugin.saveResource("wallets.yml", false);
        }
        walletConfig = YamlConfiguration.loadConfiguration(walletFile);
        
        // Load existing wallets into memory
        if (walletConfig.contains("wallets")) {
            for (String uuidStr : walletConfig.getConfigurationSection("wallets").getKeys(false)) {
                UUID uuid = UUID.fromString(uuidStr);
                String walletId = walletConfig.getString("wallets." + uuidStr);
                playerWallets.put(uuid, walletId);
            }
        }
    }

    private void saveWallets() {
        try {
            walletConfig.save(walletFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save wallet data: " + e.getMessage());
        }
    }

    public String getWalletId(Player player) {
        return playerWallets.get(player.getUniqueId());
    }

    public boolean hasWallet(Player player) {
        return playerWallets.containsKey(player.getUniqueId());
    }

    public void assignWallet(Player player, String walletId) {
        UUID uuid = player.getUniqueId();
        playerWallets.put(uuid, walletId);
        walletConfig.set("wallets." + uuid.toString(), walletId);
        saveWallets();
    }

    public String createWallet(Player player) {
        // This will be implemented based on your LNbits API integration
        // For now, we'll just create a placeholder wallet ID
        // This should be replaced with actual LNbits wallet creation
        String walletId = "wallet_" + System.currentTimeMillis();
        assignWallet(player, walletId);
        return walletId;
    }
}