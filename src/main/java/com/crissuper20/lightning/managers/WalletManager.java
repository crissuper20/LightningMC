package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.util.DebugLogger;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import com.crissuper20.lightning.storage.SQLiteWalletStorage;
import com.crissuper20.lightning.storage.WalletStorage;

public class WalletManager implements Listener {

    private final LightningPlugin plugin;
    private final Map<String, WalletData> wallets = new ConcurrentHashMap<>();
    private final Map<String, String> walletOwnerLookup = new ConcurrentHashMap<>();
    private final HttpClient httpClient;
    private final String lnbitsBaseUrl;
    private final String masterAdminKey;
    private final DebugLogger debug;
    private volatile WebSocketInvoiceMonitor invoiceMonitor;
    private final WalletStorage storage;

    public WalletManager(LightningPlugin plugin) {
        this(plugin, new SQLiteWalletStorage(plugin), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    /**
     * Constructor for dependency injection (primarily for testing).
     * 
     * @param plugin the plugin instance
     * @param storage the wallet storage implementation
     * @param httpClient the HTTP client for API calls
     */
    public WalletManager(LightningPlugin plugin, WalletStorage storage, HttpClient httpClient) {
        this.plugin = plugin;
        this.debug = plugin.getDebugLogger().withPrefix("WalletMgr");
        this.httpClient = httpClient;

        // Load config
        String host = plugin.getConfig().getString("lnbits.host", "http://127.0.0.1:5000");
        
        // Ensure protocol is present
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            boolean useHttps = plugin.getConfig().getBoolean("lnbits.use_https", false);
            host = (useHttps ? "https://" : "http://") + host;
        }
        
        // Remove trailing slash if present
        if (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        
        this.lnbitsBaseUrl = host;
        this.masterAdminKey = plugin.getConfig().getString("lnbits.adminKey");

        // Initialize storage
        this.storage = storage;
        this.storage.init();

        load();
        
        // Register as listener
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Shutdown the WalletManager and release resources.
     * Must be called during plugin disable.
     */
    public void shutdown() {
        debug.info("Shutting down WalletManager...");
        
        // Close the database connection pool
        if (storage != null) {
            try {
                storage.shutdown();
                debug.info("Storage (Hikari) shutdown cleanly");
            } catch (Exception e) {
                plugin.getLogger().warning("Error during storage shutdown: " + e.getMessage());
            }
        }
    }

    // -----------------------------------------------------
    // DATA CLASS
    // -----------------------------------------------------
    public static class WalletData {
        public final String walletId;
        public final String adminKey;
        public final String invoiceKey;
        public final String ownerName;
        public transient UUID playerUuid;

        public WalletData(String walletId, String adminKey, String invoiceKey, String ownerName) {
            this.walletId = walletId;
            this.adminKey = adminKey;
            this.invoiceKey = invoiceKey;
            this.ownerName = ownerName;
        }
    }

    // -----------------------------------------------------
    // FILE LOADING / SAVING
    // -----------------------------------------------------
    private void load() {
        debug.info("WalletManager initialized. Wallets will be loaded on player join.");
    }

    public void attachInvoiceMonitor(WebSocketInvoiceMonitor monitor) {
        this.invoiceMonitor = monitor;
        // Watch online players only
        for (Player p : Bukkit.getOnlinePlayers()) {
            WalletData data = wallets.get(p.getUniqueId().toString());
            if (data != null) {
                monitor.watchWallet(data.walletId, data.invoiceKey);
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerId = player.getUniqueId().toString();
        
        // Check if wallet is already in cache
        WalletData cachedData = wallets.get(playerId);
        if (cachedData != null) {
            // Already cached, just start the WebSocket watcher
            if (invoiceMonitor != null) {
                invoiceMonitor.watchWallet(cachedData.walletId, cachedData.invoiceKey);
            }
            return;
        }
        
        // Load wallet from database asynchronously
        storage.loadWallet(player.getUniqueId()).thenAccept(data -> {
            if (data != null) {
                // Cache the wallet
                wallets.put(playerId, data);
                walletOwnerLookup.put(data.walletId, playerId);
                debug.debug("Loaded wallet for " + player.getName() + " from database");
                
                // Start WebSocket watcher
                if (invoiceMonitor != null) {
                    invoiceMonitor.watchWallet(data.walletId, data.invoiceKey);
                }
            } else {
                debug.debug("No wallet found for " + player.getName() + " - they can create one with /wallet create");
            }
        }).exceptionally(ex -> {
            debug.error("Failed to load wallet for " + player.getName(), ex);
            return null;
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        String playerId = event.getPlayer().getUniqueId().toString();
        WalletData data = wallets.get(playerId);
        
        if (data != null && invoiceMonitor != null) {
            invoiceMonitor.unwatchWallet(data.walletId);
        }
        
        // Optionally remove from cache to save memory
        // Comment out these lines if you want to keep wallets cached for players who rejoin quickly
        if (data != null) {
            wallets.remove(playerId);
            walletOwnerLookup.remove(data.walletId);
            debug.debug("Unloaded wallet for " + event.getPlayer().getName() + " from cache");
        }
    }

    private void saveWallet(String uuid, WalletData data) {
        // Save to DB asynchronously
        storage.saveWallet(data);
    }

    /**
     * Fetch balance asynchronously - returns a CompletableFuture
     */
    private CompletableFuture<Long> fetchBalanceAsync(String playerId) {
        WalletData data = wallets.get(playerId);
        if (data == null) return CompletableFuture.completedFuture(0L);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(lnbitsBaseUrl + "/api/v1/wallet"))
                .header("X-Api-Key", data.invoiceKey)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        JsonObject json = plugin.getGson().fromJson(response.body(), JsonObject.class);
                        // Balance is usually in msat
                        return json.get("balance").getAsLong() / 1000;
                    }
                    debug.warn("Balance fetch returned status " + response.statusCode());
                    return 0L;
                })
                .exceptionally(e -> {
                    debug.error("Failed to fetch balance for " + playerId, e);
                    return 0L;
                });
    }

    /**
     * @deprecated Use fetchBalanceAsync instead to avoid main thread blocking.
     * This method is kept for backwards compatibility but now delegates to async internally.
     */
    @Deprecated
    private long fetchBalanceBlocking(String playerId) {
        try {
            return fetchBalanceAsync(playerId).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            debug.error("Failed to fetch balance for " + playerId, e);
            return 0;
        }
    }

    public void reload() {
        load();
    }

    public String getLnbitsBaseUrl() {
        return lnbitsBaseUrl;
    }

    public UUID getOwnerUuidByWalletId(String walletId) {
        String uuidStr = walletOwnerLookup.get(walletId);
        return uuidStr != null ? UUID.fromString(uuidStr) : null;
    }

    // -----------------------------------------------------
    // SUMMARY HELPERS
    // -----------------------------------------------------
    public int getWalletCount() {
        return wallets.size();
    }

    public Map<String, WalletData> getWalletSnapshot() {
        return Collections.unmodifiableMap(wallets);
    }

    /**
     * Check if player has a wallet, checks cache first, then database.
     * @return CompletableFuture that resolves to true if wallet exists
     */
    public CompletableFuture<Boolean> hasWalletAsync(UUID uuid) {
        // Check cache first
        if (wallets.containsKey(uuid.toString())) {
            return CompletableFuture.completedFuture(true);
        }
        // Check database
        return storage.hasWallet(uuid);
    }

    public CompletableFuture<Boolean> hasWalletAsync(Player player) {
        return hasWalletAsync(player.getUniqueId());
    }

    /**
     * Check if an online player has a wallet (cache-only check).
     * This is safe for online players as their wallets are loaded on join.
     * For offline players, use {@link #hasWalletAsync(UUID)} instead.
     * 
     * @param uuid the player's UUID
     * @return true if wallet is in cache
     */
    public boolean hasWallet(UUID uuid) {
        return wallets.containsKey(uuid.toString());
    }

    /**
     * Check if an online player has a wallet (cache-only check).
     * This is safe for online players as their wallets are loaded on join.
     * For offline players, use {@link #hasWalletAsync(Player)} instead.
     * 
     * @param player the player to check
     * @return true if wallet is in cache
     */
    public boolean hasWallet(Player player) {
        return wallets.containsKey(player.getUniqueId().toString());
    }

    /**
     * Check if an online player has a wallet (cache-only check).
     * This is safe for online players as their wallets are loaded on join.
     * For offline players, use {@link #hasWalletAsync(UUID)} instead.
     * 
     * @param playerId the player's UUID as string
     * @return true if wallet is in cache
     */
    public boolean hasWallet(String playerId) {
        return wallets.containsKey(playerId);
    }

    // -----------------------------------------------------
    // PLAYER HELPERS
    // -----------------------------------------------------
    public String getPlayerAdminKey(Player player) {
        return getWalletAdminKey(player.getUniqueId().toString());
    }

    public String getPlayerInvoiceKey(Player player) {
        return getInvoiceKey(player.getUniqueId().toString());
    }

    public CompletableFuture<JsonObject> getOrCreateWallet(Player player) {
        String playerId = player.getUniqueId().toString();

        // Check cache first
        WalletData cachedData = wallets.get(playerId);
        if (cachedData != null) {
            // Wallet exists in cache, return immediately
            JsonObject json = new JsonObject();
            json.addProperty("id", cachedData.walletId);
            json.addProperty("adminkey", cachedData.adminKey);
            json.addProperty("inkey", cachedData.invoiceKey);
            json.addProperty("owner", cachedData.ownerName);
            return CompletableFuture.completedFuture(json);
        }

        // Check database, then create if not found
        return storage.loadWallet(player.getUniqueId()).thenCompose(data -> {
            if (data != null) {
                // Found in database, cache it and return
                wallets.put(playerId, data);
                walletOwnerLookup.put(data.walletId, playerId);
                
                JsonObject json = new JsonObject();
                json.addProperty("id", data.walletId);
                json.addProperty("adminkey", data.adminKey);
                json.addProperty("inkey", data.invoiceKey);
                json.addProperty("owner", data.ownerName);
                return CompletableFuture.completedFuture(json);
            }
            
            // Wallet doesn't exist, create it asynchronously
            debug.debug("Wallet missing for " + player.getName() + " – creating now");
            return createWalletForAsync(playerId, player.getName())
                    .thenApply(success -> {
                        if (!success) {
                            throw new IllegalStateException("Failed to create wallet for " + player.getName());
                        }
                        WalletData newData = wallets.get(playerId);
                        JsonObject json = new JsonObject();
                        json.addProperty("id", newData.walletId);
                        json.addProperty("adminkey", newData.adminKey);
                        json.addProperty("inkey", newData.invoiceKey);
                        json.addProperty("owner", newData.ownerName);
                        return json;
                    });
        });
    }

    public CompletableFuture<Long> getBalance(Player player) {
        return fetchBalanceFromLNbits(player);
    }

    public CompletableFuture<Long> fetchBalanceFromLNbits(Player player) {
        debug.debug("Fetching live balance for player=" + player.getName());
        return fetchBalanceAsync(player.getUniqueId().toString());
    }

    public CompletableFuture<Map<String, Long>> syncAllBalancesAsync() {
        Map<String, Long> balances = new ConcurrentHashMap<>();
        java.util.List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        
        for (String playerId : wallets.keySet()) {
            CompletableFuture<Void> f = fetchBalanceAsync(playerId)
                    .thenAccept(balance -> balances.put(playerId, balance))
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("Balance sync failed for " + playerId + ": " + ex.getMessage());
                        return null;
                    });
            futures.add(f);
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> balances);
    }

    public String formatBalance(long sats) {
        if (sats >= 100_000_000) {
            return String.format("%.8f BTC", sats / 100_000_000.0);
        }
        return String.format("%,d sats", sats);
    }

    // -----------------------------------------------------
    // WALLET LOOKUPS
    // -----------------------------------------------------
    public String getWalletAdminKey(String playerId) {
        WalletData w = wallets.get(playerId);
        return (w == null ? null : w.adminKey);
    }

    public String getInvoiceKey(String playerId) {
        WalletData w = wallets.get(playerId);
        return (w == null ? null : w.invoiceKey);
    }

    public String getWalletId(String playerId) {
        WalletData w = wallets.get(playerId);
        return (w == null ? null : w.walletId);
    }

    public String getWalletId(Player player) {
        return getWalletId(player.getUniqueId().toString());
    }

    // -----------------------------------------------------
    // WALLET CREATION
    // -----------------------------------------------------
    
    /**
     * Create a wallet for a player asynchronously.
     * This is the preferred method to avoid blocking the main thread.
     */
    public CompletableFuture<Boolean> createWalletForAsync(String playerId, String playerName) {
        if (masterAdminKey == null || masterAdminKey.isBlank()) {
            plugin.getLogger().warning("Missing lnbits.adminKey in config.yml");
            return CompletableFuture.completedFuture(false);
        }

        String walletEndpoint = lnbitsBaseUrl + "/api/v1/wallet";
        debug.info("Creating LNbits wallet for player=" + playerName + " via " + walletEndpoint);

        JsonObject requestJson = new JsonObject();
        // LNbits API expects only 'name' for wallet creation
        requestJson.addProperty("name", playerName);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(walletEndpoint))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", masterAdminKey)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(requestJson.toString()))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    debug.debug("Wallet create response status=" + response.statusCode());

                    if (response.statusCode() != 200 && response.statusCode() != 201) {
                        plugin.getLogger().warning("Failed to create LNbits wallet: " + response.body());
                        debug.warn("Wallet creation failed body=" + response.body());
                        return false;
                    }

                    JsonObject json = plugin.getGson().fromJson(response.body(), JsonObject.class);
                    debug.debug("Wallet create payload=" + json);

                    WalletData data = new WalletData(
                            json.get("id").getAsString(),
                            json.get("adminkey").getAsString(),
                            json.get("inkey").getAsString(),
                            playerName
                    );
                    data.playerUuid = UUID.fromString(playerId);

                    wallets.put(playerId, data);
                    walletOwnerLookup.put(data.walletId, playerId);
                    saveWallet(playerId, data);

                    if (invoiceMonitor != null) {
                        invoiceMonitor.watchWallet(data.walletId, data.invoiceKey);
                    }
                    debug.info("Wallet provisioned for " + playerName + " id=" + data.walletId);
                    return true;
                })
                .exceptionally(e -> {
                    plugin.getLogger().warning("Failed to create wallet: " + e.getMessage());
                    debug.error("createWalletForAsync exception", e);
                    return false;
                });
    }
    
    /**
     * @deprecated Use createWalletForAsync instead to avoid main thread blocking.
     * This method blocks and should not be called from the main server thread.
     */
    @Deprecated
    public boolean createWalletFor(String playerId, String playerName) {
        try {
            return createWalletForAsync(playerId, playerName).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create wallet: " + e.getMessage());
            debug.error("createWalletFor exception", e);
            return false;
        }
    }
}
