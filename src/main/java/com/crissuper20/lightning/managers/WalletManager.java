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

/**
 * Manages player Lightning wallets and balances.
 * 
 * Structure in wallets.yml:
 * wallets:
 *   uuid:
 *     wallet_id: "wallet_123"
 *     balance: 1000
 */
public class WalletManager {
    
    private final LightningPlugin plugin;
    private final File walletFile;
    private FileConfiguration walletConfig;
    
    // In-memory cache for faster access
    private final Map<UUID, String> playerWallets;
    private final Map<UUID, Long> playerBalances;

    public WalletManager(LightningPlugin plugin) {
        this.plugin = plugin;
        this.walletFile = new File(plugin.getDataFolder(), "wallets.yml");
        this.playerWallets = new HashMap<>();
        this.playerBalances = new HashMap<>();
        
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
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    String walletId = walletConfig.getString("wallets." + uuidStr + ".wallet_id");
                    long balance = walletConfig.getLong("wallets." + uuidStr + ".balance", 0);
                    
                    if (walletId != null) {
                        playerWallets.put(uuid, walletId);
                        playerBalances.put(uuid, balance);
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid UUID in wallets.yml: " + uuidStr);
                }
            }
        }
        
        plugin.getDebugLogger().info("Loaded " + playerWallets.size() + " wallets from storage");
    }

    private void saveWallets() {
        try {
            walletConfig.save(walletFile);
            plugin.getDebugLogger().debug("Wallets saved to disk");
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save wallet data: " + e.getMessage());
            plugin.getDebugLogger().error("Wallet save failed", e);
        }
    }

    /**
     * Save a specific player's wallet data
     */
    private void savePlayerWallet(UUID uuid) {
        String uuidStr = uuid.toString();
        
        if (playerWallets.containsKey(uuid)) {
            walletConfig.set("wallets." + uuidStr + ".wallet_id", playerWallets.get(uuid));
            walletConfig.set("wallets." + uuidStr + ".balance", playerBalances.getOrDefault(uuid, 0L));
            saveWallets();
        }
    }

    // ========================================================================
    // Wallet ID Management
    // ========================================================================

    public String getWalletId(Player player) {
        return playerWallets.get(player.getUniqueId());
    }

    public boolean hasWallet(Player player) {
        return playerWallets.containsKey(player.getUniqueId());
    }

    public void assignWallet(Player player, String walletId) {
        UUID uuid = player.getUniqueId();
        playerWallets.put(uuid, walletId);
        
        // Initialize balance if not exists
        if (!playerBalances.containsKey(uuid)) {
            playerBalances.put(uuid, 0L);
        }
        
        savePlayerWallet(uuid);
        plugin.getDebugLogger().debug("Assigned wallet " + walletId + " to " + player.getName());
    }

    public String createWallet(Player player) {
        // Generate a unique wallet ID
        // In a real implementation, you might want to:
        // 1. Create a wallet via LNbits API
        // 2. Use the returned wallet ID
        // For now, this creates a local identifier
        String walletId = "wallet_" + player.getUniqueId().toString().substring(0, 8) + "_" + System.currentTimeMillis();
        
        assignWallet(player, walletId);
        plugin.getDebugLogger().info("Created new wallet for " + player.getName());
        
        return walletId;
    }

    // ========================================================================
    // Balance Management
    // ========================================================================

    /**
     * Get player's current balance in satoshis
     */
    public long getBalance(Player player) {
        return playerBalances.getOrDefault(player.getUniqueId(), 0L);
    }

    /**
     * Set player's balance (use with caution!)
     */
    public void setBalance(Player player, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Balance cannot be negative");
        }
        
        UUID uuid = player.getUniqueId();
        playerBalances.put(uuid, amount);
        savePlayerWallet(uuid);
        
        plugin.getDebugLogger().debug("Set balance for " + player.getName() + " to " + amount + " sats");
    }

    /**
     * Add to player's balance
     */
    public void addBalance(Player player, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        
        UUID uuid = player.getUniqueId();
        long currentBalance = playerBalances.getOrDefault(uuid, 0L);
        long newBalance = currentBalance + amount;
        
        playerBalances.put(uuid, newBalance);
        savePlayerWallet(uuid);
        
        plugin.getDebugLogger().debug("Added " + amount + " sats to " + player.getName() + " (new: " + newBalance + ")");
    }

    /**
     * Deduct from player's balance
     * @return true if successful, false if insufficient funds
     */
    public boolean deductBalance(Player player, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        
        UUID uuid = player.getUniqueId();
        long currentBalance = playerBalances.getOrDefault(uuid, 0L);
        
        if (currentBalance < amount) {
            plugin.getDebugLogger().debug("Insufficient balance for " + player.getName() + 
                " (has: " + currentBalance + ", needs: " + amount + ")");
            return false;
        }
        
        long newBalance = currentBalance - amount;
        playerBalances.put(uuid, newBalance);
        savePlayerWallet(uuid);
        
        plugin.getDebugLogger().debug("Deducted " + amount + " sats from " + player.getName() + " (new: " + newBalance + ")");
        return true;
    }

    /**
     * Transfer balance between players
     * @return true if successful, false if sender has insufficient funds
     */
    public boolean transfer(Player from, Player to, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        
        if (from.equals(to)) {
            throw new IllegalArgumentException("Cannot transfer to yourself");
        }
        
        // Check both have wallets
        if (!hasWallet(from) || !hasWallet(to)) {
            return false;
        }
        
        // Atomic transfer: deduct from sender, add to receiver
        UUID fromUuid = from.getUniqueId();
        UUID toUuid = to.getUniqueId();
        
        long fromBalance = playerBalances.getOrDefault(fromUuid, 0L);
        
        if (fromBalance < amount) {
            return false;
        }
        
        // Perform transfer
        playerBalances.put(fromUuid, fromBalance - amount);
        playerBalances.put(toUuid, playerBalances.getOrDefault(toUuid, 0L) + amount);
        
        // Save both wallets
        savePlayerWallet(fromUuid);
        savePlayerWallet(toUuid);
        
        plugin.getDebugLogger().info("Transfer: " + from.getName() + " → " + to.getName() + " (" + amount + " sats)");
        return true;
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Check if player has at least the specified amount
     */
    public boolean hasBalance(Player player, long amount) {
        return getBalance(player) >= amount;
    }

    /**
     * Format balance for display
     */
    public String formatBalance(long sats) {
        if (sats >= 100_000_000) {
            // Convert to BTC
            double btc = sats / 100_000_000.0;
            return String.format("%.8f BTC", btc);
        } else if (sats >= 1000) {
            // Show with thousands separator
            return String.format("%,d sats", sats);
        } else {
            return sats + " sats";
        }
    }

    /**
     * Get total number of registered wallets
     */
    public int getWalletCount() {
        return playerWallets.size();
    }

    /**
     * Get total sats across all wallets (for stats)
     */
    public long getTotalBalance() {
        return playerBalances.values().stream()
            .mapToLong(Long::longValue)
            .sum();
    }

    /**
     * Reload wallet data from disk (useful for /reload)
     */
    public void reload() {
        playerWallets.clear();
        playerBalances.clear();
        loadWallets();
        plugin.getDebugLogger().info("Wallet data reloaded");
    }
}
