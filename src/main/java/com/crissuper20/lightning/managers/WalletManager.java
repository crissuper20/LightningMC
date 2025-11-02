package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages REAL LNbits wallets for each player.
 * Each player gets their own Lightning wallet with unique API keys.
 * 
 * Structure in wallets.yml:
 * wallets:
 *   player-uuid:
 *     wallet_id: "abc123..."
 *     admin_key: "key_abc..."      # Full control of player's wallet
 *     invoice_key: "inv_abc..."    # Read-only key
 *     wallet_name: "Player_Steve"
 *     created: 1698765432
 *     balance: 0                   # Cached balance (synced from LNbits)
 */
public class WalletManager {
    
    private final LightningPlugin plugin;
    private final File walletFile;
    private FileConfiguration walletConfig;
    
    // In-memory cache for faster access
    private final Map<UUID, PlayerWallet> playerWallets;
    
    // HTTP client for LNbits API calls
    private final HttpClient httpClient;
    
    // Gson for JSON parsing
    private final Gson gson;

    public WalletManager(LightningPlugin plugin) {
        this.plugin = plugin;
        this.walletFile = new File(plugin.getDataFolder(), "wallets.yml");
        this.playerWallets = new HashMap<>();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.gson = new Gson();
        
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
                    String path = "wallets." + uuidStr + ".";
                    
                    PlayerWallet wallet = new PlayerWallet(
                        walletConfig.getString(path + "wallet_id"),
                        walletConfig.getString(path + "admin_key"),
                        walletConfig.getString(path + "invoice_key"),
                        walletConfig.getString(path + "wallet_name"),
                        walletConfig.getLong(path + "created"),
                        walletConfig.getLong(path + "balance", 0)
                    );
                    
                    playerWallets.put(uuid, wallet);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid UUID in wallets.yml: " + uuidStr);
                }
            }
        }
        
        plugin.getDebugLogger().info("Loaded " + playerWallets.size() + " Lightning wallets from storage");
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
        PlayerWallet wallet = playerWallets.get(uuid);
        if (wallet == null) return;
        
        String path = "wallets." + uuid.toString() + ".";
        walletConfig.set(path + "wallet_id", wallet.walletId);
        walletConfig.set(path + "admin_key", wallet.adminKey);
        walletConfig.set(path + "invoice_key", wallet.invoiceKey);
        walletConfig.set(path + "wallet_name", wallet.walletName);
        walletConfig.set(path + "created", wallet.created);
        walletConfig.set(path + "balance", wallet.balance);
        
        saveWallets();
    }

    // ========================================================================
    // Wallet Management - Real LNbits Integration
    // ========================================================================

    /**
     * Check if player has a Lightning wallet
     */
    public boolean hasWallet(Player player) {
        return playerWallets.containsKey(player.getUniqueId());
    }

    /**
     * Get player's wallet ID
     */
    public String getWalletId(Player player) {
        PlayerWallet wallet = playerWallets.get(player.getUniqueId());
        return wallet != null ? wallet.walletId : null;
    }

    /**
     * Get player's admin API key (for full wallet control)
     */
    public String getPlayerAdminKey(Player player) {
        PlayerWallet wallet = playerWallets.get(player.getUniqueId());
        return wallet != null ? wallet.adminKey : null;
    }

    /**
     * Get player's invoice API key (read-only)
     */
    public String getPlayerInvoiceKey(Player player) {
        PlayerWallet wallet = playerWallets.get(player.getUniqueId());
        return wallet != null ? wallet.invoiceKey : null;
    }

    /**
     * Create a REAL LNbits wallet for a player
     */
    public String createWallet(Player player) {
        UUID uuid = player.getUniqueId();
        
        // Check if already has wallet
        if (playerWallets.containsKey(uuid)) {
            plugin.getDebugLogger().warning("Player " + player.getName() + " already has a wallet!");
            return playerWallets.get(uuid).walletId;
        }
        
        plugin.getDebugLogger().info("Creating LNbits wallet for " + player.getName());
        
        try {
            // Call LNbits API to create wallet
            String walletName = "Player_" + player.getName() + "_" + uuid.toString().substring(0, 8);
            JsonObject response = callLNbitsCreateWallet(walletName);
            
            if (response == null) {
                throw new RuntimeException("Failed to create LNbits wallet - null response");
            }
            
            // Extract wallet details from response
            String walletId = response.get("id").getAsString();
            String adminKey = response.get("adminkey").getAsString();
            String invoiceKey = response.get("inkey").getAsString();
            
            // Create wallet object
            PlayerWallet wallet = new PlayerWallet(
                walletId,
                adminKey,
                invoiceKey,
                walletName,
                System.currentTimeMillis(),
                0L
            );
            
            // Store in memory and disk
            playerWallets.put(uuid, wallet);
            savePlayerWallet(uuid);
            
            plugin.getDebugLogger().info("✓ Created LNbits wallet for " + player.getName() + 
                ": " + walletId);
            
            return walletId;
            
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to create LNbits wallet for " + player.getName() + 
                ": " + e.getMessage());
            plugin.getDebugLogger().error("Wallet creation error", e);
            throw new RuntimeException("Could not create Lightning wallet", e);
        }
    }

    /**
     * Call LNbits API to create a new wallet
     */
    private JsonObject callLNbitsCreateWallet(String walletName) throws Exception {
        // Get LNbits config
        String lnbitsHost = plugin.getConfig().getString("lnbits.host", "localhost");
        boolean useHttps = plugin.getConfig().getBoolean("lnbits.use_https", true);
        String adminKey = plugin.getConfig().getString("lnbits.api_key", "");
        
        if (adminKey.isEmpty()) {
            throw new IllegalStateException("No LNbits admin key configured!");
        }
        
        String protocol = useHttps ? "https://" : "http://";
        String url = protocol + lnbitsHost + "/api/v1/wallet";
        
        // Prepare request body
        String body = String.format("{\"name\":\"%s\"}", walletName);
        
        plugin.getDebugLogger().debug("Creating wallet via LNbits API: " + url);
        plugin.getDebugLogger().debug("Wallet name: " + walletName);
        
        // Build HTTP request
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("X-Api-Key", adminKey)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        
        // Send request
        HttpResponse<String> response = httpClient.send(
            request, 
            HttpResponse.BodyHandlers.ofString()
        );
        
        plugin.getDebugLogger().debug("LNbits response status: " + response.statusCode());
        plugin.getDebugLogger().debug("LNbits response body: " + response.body());
        
        if (response.statusCode() == 201 || response.statusCode() == 200) {
            // Parse JSON response using our own Gson instance
            return gson.fromJson(response.body(), JsonObject.class);
        } else {
            throw new RuntimeException("LNbits API error: HTTP " + response.statusCode() + 
                " - " + response.body());
        }
    }

    // ========================================================================
    // Balance Management (Cached from LNbits)
    // ========================================================================

    /**
     * Get player's cached balance
     */
    public long getBalance(Player player) {
        PlayerWallet wallet = playerWallets.get(player.getUniqueId());
        return wallet != null ? wallet.balance : 0L;
    }

    /**
     * Update cached balance (called by InvoiceMonitor when invoice is paid)
     */
    public void updateBalance(Player player, long newBalance) {
        UUID uuid = player.getUniqueId();
        PlayerWallet wallet = playerWallets.get(uuid);
        
        if (wallet != null) {
            wallet.balance = newBalance;
            savePlayerWallet(uuid);
            plugin.getDebugLogger().debug("Updated cached balance for " + player.getName() + 
                ": " + newBalance + " sats");
        }
    }

    /**
     * Add to cached balance (when invoice is paid)
     */
    public void addBalance(Player player, long amount) {
        UUID uuid = player.getUniqueId();
        PlayerWallet wallet = playerWallets.get(uuid);
        
        if (wallet != null) {
            wallet.balance += amount;
            savePlayerWallet(uuid);
            plugin.getDebugLogger().debug("Added " + amount + " sats to " + player.getName() + 
                " (new: " + wallet.balance + ")");
        }
    }

    /**
     * Note: For real balance, use LNService.getBalanceAsync() with player's admin key
     * The cached balance here is just for quick reference
     */

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Format balance for display
     */
    public String formatBalance(long sats) {
        if (sats >= 100_000_000) {
            double btc = sats / 100_000_000.0;
            return String.format("%.8f BTC", btc);
        } else if (sats >= 1000) {
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
     * Reload wallet data from disk
     */
    public void reload() {
        playerWallets.clear();
        loadWallets();
        plugin.getDebugLogger().info("Wallet data reloaded");
    }

    // ========================================================================
    // Data Classes
    // ========================================================================

    /**
     * Represents a player's LNbits wallet
     */
    public static class PlayerWallet {
        public final String walletId;
        public final String adminKey;
        public final String invoiceKey;
        public final String walletName;
        public final long created;
        public long balance; // Cached balance
        
        public PlayerWallet(String walletId, String adminKey, String invoiceKey, 
                           String walletName, long created, long balance) {
            this.walletId = walletId;
            this.adminKey = adminKey;
            this.invoiceKey = invoiceKey;
            this.walletName = walletName;
            this.created = created;
            this.balance = balance;
        }
    }
}