package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import com.google.gson.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * WalletManager - manages per-player LNbits wallets
 * 
 * FIXED: Removed unnecessary balance caching and fetching
 */
public class WalletManager {

    private final LightningPlugin plugin;
    private final HttpClient httpClient;

    private final String apiBase;
    private final String adminKey;

    private final File walletFile;
    private FileConfiguration walletConfig;
    private final Map<UUID, PlayerWallet> playerWallets;

    public WalletManager(LightningPlugin plugin) throws Exception {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.playerWallets = new HashMap<>();

        // Get config values
        String host = plugin.getConfig().getString("lnbits.host", "");
        if (host.isEmpty()) {
            throw new IllegalStateException("lnbits.host not configured!");
        }

        boolean useHttps = plugin.getConfig().getBoolean("lnbits.use_https", true);
        this.apiBase = (useHttps ? "https://" : "http://") + host + "/api/v1";
        this.adminKey = plugin.getConfig().getString("lnbits.api_key", "");

        if (adminKey.isEmpty()) {
            throw new IllegalStateException("lnbits.api_key not configured!");
        }

        // Ensure server UUID exists
        String serverUuid = plugin.getConfig().getString("server_uuid", UUID.randomUUID().toString());
        if (plugin.getConfig().getString("server_uuid") == null) {
            plugin.getConfig().set("server_uuid", serverUuid);
            plugin.saveConfig();
        }

        // Create data folder
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        // Setup wallet file
        this.walletFile = new File(plugin.getDataFolder(), "wallets.yml");
        if (!walletFile.exists()) {
            try {
                walletFile.createNewFile();
                plugin.getLogger().info("Created wallets.yml");
            } catch (IOException e) {
                throw new RuntimeException("Could not create wallets.yml", e);
            }
        }

        // Load wallets
        this.walletConfig = YamlConfiguration.loadConfiguration(walletFile);
        loadWallets();
    }

    // ================================================================
    // Loading / Saving
    // ================================================================

    private void loadWallets() {
        playerWallets.clear();

        if (!walletConfig.contains("wallets")) {
            plugin.getLogger().info("No wallets to load");
            return;
        }

        for (String uuidStr : walletConfig.getConfigurationSection("wallets").getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                String path = "wallets." + uuidStr + ".";

                String walletId = walletConfig.getString(path + "wallet_id");
                String adminKey = walletConfig.getString(path + "admin_key");
                String invoiceKey = walletConfig.getString(path + "invoice_key");
                String walletName = walletConfig.getString(path + "wallet_name");
                long created = walletConfig.getLong(path + "created");

                if (walletId == null || adminKey == null || invoiceKey == null) {
                    plugin.getLogger().warning("Incomplete wallet entry for " + uuidStr);
                    continue;
                }

                PlayerWallet wallet = new PlayerWallet(walletId, adminKey, invoiceKey, walletName, created);
                playerWallets.put(uuid, wallet);

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load wallet " + uuidStr + ": " + e.getMessage());
            }
        }

        plugin.getLogger().info("Loaded " + playerWallets.size() + " player wallets");
        saveWallets();
    }

    private void saveWallets() {
        if (walletConfig == null || walletFile == null) {
            plugin.getLogger().warning("Cannot save wallets - not initialized");
            return;
        }

        try {
            walletConfig.save(walletFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save wallets.yml: " + e.getMessage());
        }
    }

    private void savePlayerWallet(UUID uuid) {
        PlayerWallet w = playerWallets.get(uuid);
        if (w == null || walletConfig == null) return;

        try {
            String path = "wallets." + uuid + ".";
            walletConfig.set(path + "wallet_id", w.walletId);
            walletConfig.set(path + "admin_key", w.adminKey);
            walletConfig.set(path + "invoice_key", w.invoiceKey);
            walletConfig.set(path + "wallet_name", w.walletName);
            walletConfig.set(path + "created", w.created);

            saveWallets();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save wallet for " + uuid + ": " + e.getMessage());
        }
    }

    // ================================================================
    // Wallet management
    // ================================================================

    public boolean hasWallet(Player player) {
        return playerWallets.containsKey(player.getUniqueId());
    }

    public boolean hasWallet(UUID uuid) {
        return playerWallets.containsKey(uuid);
    }

    public CompletableFuture<JsonObject> getOrCreateWallet(Player player) {
        UUID uuid = player.getUniqueId();

        if (playerWallets.containsKey(uuid)) {
            PlayerWallet w = playerWallets.get(uuid);
            JsonObject obj = new JsonObject();
            obj.addProperty("id", w.walletId);
            obj.addProperty("adminkey", w.adminKey);
            obj.addProperty("inkey", w.invoiceKey);
            return CompletableFuture.completedFuture(obj);
        }

        return createWalletForPlayer(player);
    }

    private CompletableFuture<JsonObject> createWalletForPlayer(Player player) {
        plugin.getLogger().info("Creating LNbits wallet for " + player.getName() + "...");

        String endpoint = apiBase + "/wallet";

        JsonObject body = new JsonObject();
        body.addProperty("name", player.getName());

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", adminKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(Duration.ofSeconds(15))
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    int code = resp.statusCode();
                    String bodyResp = resp.body();

                    try {
                        if (code >= 200 && code < 300) {
                            JsonObject wallet = JsonParser.parseString(bodyResp).getAsJsonObject();

                            if (!wallet.has("id") || !wallet.has("adminkey") || !wallet.has("inkey")) {
                                plugin.getLogger().severe("Invalid wallet response - missing required fields");
                                return new JsonObject();
                            }

                            String walletId = wallet.get("id").getAsString();
                            String adminkey = wallet.get("adminkey").getAsString();
                            String inkey = wallet.get("inkey").getAsString();

                            PlayerWallet w = new PlayerWallet(
                                walletId,
                                adminkey,
                                inkey,
                                player.getName(),
                                System.currentTimeMillis()
                            );

                            playerWallets.put(player.getUniqueId(), w);
                            savePlayerWallet(player.getUniqueId());

                            plugin.getLogger().info("✓ Wallet created for " + player.getName() + " (" + walletId + ")");
                            return wallet;

                        } else {
                            plugin.getLogger().severe("Failed to create wallet (" + code + "): " + bodyResp);
                            return new JsonObject();
                        }
                    } catch (Exception e) {
                        plugin.getLogger().severe("Wallet creation error: " + e.getMessage());
                        e.printStackTrace();
                        return new JsonObject();
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Network error during wallet creation: " + ex.getMessage());
                    return new JsonObject();
                });
    }

    /**
     * Get balance (just calls fetchBalanceFromLNbits for compatibility)
     */
    public CompletableFuture<Long> getBalance(Player player) {
        return fetchBalanceFromLNbits(player);
    }

    /**
     * Fetches the real-time balance from LNbits
     * Only called when explicitly needed (e.g., /wallet command)
     */
    public CompletableFuture<Long> fetchBalanceFromLNbits(Player player) {
        PlayerWallet w = playerWallets.get(player.getUniqueId());
        if (w == null) {
            plugin.getLogger().warning("Cannot fetch balance - no wallet for " + player.getName());
            return CompletableFuture.completedFuture(0L);
        }

        String endpoint = apiBase + "/wallet";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("X-Api-Key", w.adminKey)
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    int code = resp.statusCode();
                    if (code >= 200 && code < 300) {
                        try {
                            JsonObject wallet = JsonParser.parseString(resp.body()).getAsJsonObject();
                            long balanceMsat = wallet.get("balance").getAsLong();
                            long balanceSats = balanceMsat / 1000;

                            plugin.getDebugLogger().debug("Fetched balance for " + player.getName() + ": " + balanceSats + " sats");
                            return balanceSats;
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to parse balance for " + player.getName() + ": " + e.getMessage());
                            return 0L;
                        }
                    } else {
                        plugin.getLogger().warning("Failed to fetch balance for " + player.getName() + " (" + code + "): " + resp.body());
                        return 0L;
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Network error fetching balance for " + player.getName() + ": " + ex.getMessage());
                    return 0L;
                });
    }

    // ================================================================
    // Accessors
    // ================================================================

    public String getPlayerAdminKey(Player player) {
        PlayerWallet w = playerWallets.get(player.getUniqueId());
        return w != null ? w.adminKey : null;
    }

    public String getPlayerInvoiceKey(Player player) {
        PlayerWallet w = playerWallets.get(player.getUniqueId());
        return w != null ? w.invoiceKey : null;
    }
    
    public String getWalletId(Player player) {
        PlayerWallet w = playerWallets.get(player.getUniqueId());
        return w != null ? w.walletId : null;
    }

    public int getWalletCount() {
        return playerWallets.size();
    }

    public void reload() {
        playerWallets.clear();
        if (walletFile != null && walletFile.exists()) {
            this.walletConfig = YamlConfiguration.loadConfiguration(walletFile);
            try {
                loadWallets();
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to reload wallets: " + e.getMessage());
            }
        }
    }

    public String formatBalance(long sats) {
        if (sats >= 100_000_000) {
            return String.format("%.8f BTC", sats / 100_000_000.0);
        }
        return String.format("%,d sats", sats);
    }

    // ================================================================
    // Data structure
    // ================================================================

    public static class PlayerWallet {
        public final String walletId;
        public final String adminKey;
        public final String invoiceKey;
        public final String walletName;
        public final long created;

        public PlayerWallet(String walletId, String adminKey, String invoiceKey,
                            String walletName, long created) {
            this.walletId = walletId;
            this.adminKey = adminKey;
            this.invoiceKey = invoiceKey;
            this.walletName = walletName;
            this.created = created;
        }
    }

    public void syncAllBalances() {
        throw new UnsupportedOperationException("Unimplemented method 'syncAllBalances'");
    }
}