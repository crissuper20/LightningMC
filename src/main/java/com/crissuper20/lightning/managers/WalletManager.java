package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.util.DebugLogger;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
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

public class WalletManager {

    private final LightningPlugin plugin;
    private final File userdataFolder;
    private final Map<String, WalletData> wallets = new ConcurrentHashMap<>();
    private final Map<String, String> walletOwnerLookup = new ConcurrentHashMap<>();
    private final HttpClient httpClient;
    private final String lnbitsBaseUrl;
    private final String masterAdminKey;
    private final DebugLogger debug;
    private volatile WebSocketInvoiceMonitor invoiceMonitor;

    public WalletManager(LightningPlugin plugin) {
        this.plugin = plugin;
        this.userdataFolder = new File(plugin.getDataFolder(), "userdata");
        this.debug = plugin.getDebugLogger().withPrefix("WalletManager");
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        this.masterAdminKey = plugin.getConfig().getString("lnbits.adminKey", "");
        this.lnbitsBaseUrl = resolveBaseUrl();

        load();
    }

    // -----------------------------------------------------
    // DATA CLASS
    // -----------------------------------------------------
    public static class WalletData {
        public final String walletId;
        public final String adminKey;
        public final String invoiceKey;
        public final String ownerName;

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
        debug.debug("Loading wallets from disk…");
        wallets.clear();
        walletOwnerLookup.clear();

        // Ensure userdata folder exists
        if (!userdataFolder.exists()) {
            userdataFolder.mkdirs();
        }

        // Migration: Check for old wallets.json
        File oldWalletFile = new File(plugin.getDataFolder(), "wallets.json");
        if (oldWalletFile.exists()) {
            migrateOldWallets(oldWalletFile);
        }

        // Load individual wallet files
        File[] files = userdataFolder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;

        for (File file : files) {
            try (FileReader reader = new FileReader(file)) {
                JsonObject obj = plugin.getGson().fromJson(reader, JsonObject.class);
                if (obj == null) continue;

                // Filename is UUID.json
                String uuid = file.getName().replace(".json", "");
                
                WalletData data = new WalletData(
                        obj.get("walletId").getAsString(),
                        obj.get("adminKey").getAsString(),
                        obj.get("invoiceKey").getAsString(),
                        obj.get("ownerName").getAsString()
                );

                wallets.put(uuid, data);
                walletOwnerLookup.put(data.walletId, uuid);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load wallet file " + file.getName() + ": " + e.getMessage());
            }
        }
        
        debug.info("Loaded " + wallets.size() + " wallets from storage");

        if (invoiceMonitor != null) {
            debug.debug("Re-attaching websocket monitor to existing wallets");
            wallets.values().forEach(data -> invoiceMonitor.watchWallet(data.walletId, data.invoiceKey));
        }
    }

    private void migrateOldWallets(File oldFile) {
        plugin.getLogger().info("Migrating wallets.json to individual files...");
        try (FileReader reader = new FileReader(oldFile)) {
            JsonObject root = plugin.getGson().fromJson(reader, JsonObject.class);
            if (root != null) {
                for (String key : root.keySet()) {
                    JsonObject obj = root.getAsJsonObject(key);
                    WalletData data = new WalletData(
                            obj.get("walletId").getAsString(),
                            obj.get("adminKey").getAsString(),
                            obj.get("invoiceKey").getAsString(),
                            obj.get("ownerName").getAsString()
                    );
                    // Save to new format
                    saveWallet(key, data);
                }
            }
            // Rename old file to .bak
            oldFile.renameTo(new File(plugin.getDataFolder(), "wallets.json.bak"));
            plugin.getLogger().info("Migration complete. Old file renamed to wallets.json.bak");
        } catch (Exception e) {
            plugin.getLogger().severe("Migration failed: " + e.getMessage());
        }
    }

    private void saveWallet(String uuid, WalletData data) {
        try {
            File file = new File(userdataFolder, uuid + ".json");
            JsonObject obj = new JsonObject();
            obj.addProperty("walletId", data.walletId);
            obj.addProperty("adminKey", data.adminKey);
            obj.addProperty("invoiceKey", data.invoiceKey);
            obj.addProperty("ownerName", data.ownerName);

            try (FileWriter writer = new FileWriter(file)) {
                writer.write(plugin.getGson().toJson(obj));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save wallet for " + uuid + ": " + e.getMessage());
            debug.error("Wallet save failure", e);
        }
    }

    public void reload() {
        load();
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

    public boolean hasWallet(UUID uuid) {
        return hasWallet(uuid.toString());
    }

    public boolean hasWallet(Player player) {
        return hasWallet(player.getUniqueId().toString());
    }

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
        return CompletableFuture.supplyAsync(() -> {
            String playerId = player.getUniqueId().toString();

            if (!hasWallet(playerId)) {
                debug.debug("Wallet missing for " + player.getName() + " – creating now");
                if (!createWalletFor(playerId, player.getName())) {
                    throw new IllegalStateException("Failed to create wallet for " + player.getName());
                }
            }

            WalletData data = wallets.get(playerId);
            JsonObject json = new JsonObject();
            json.addProperty("id", data.walletId);
            json.addProperty("adminkey", data.adminKey);
            json.addProperty("inkey", data.invoiceKey);
            json.addProperty("owner", data.ownerName);
            return json;
        });
    }

    public CompletableFuture<Long> getBalance(Player player) {
        return fetchBalanceFromLNbits(player);
    }

    public CompletableFuture<Long> fetchBalanceFromLNbits(Player player) {
        return CompletableFuture.supplyAsync(() -> {
            debug.debug("Fetching live balance for player=" + player.getName());
            return fetchBalanceBlocking(player.getUniqueId().toString());
        });
    }

    public CompletableFuture<Map<String, Long>> syncAllBalancesAsync() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Long> balances = new ConcurrentHashMap<>();
            for (String playerId : wallets.keySet()) {
                try {
                    balances.put(playerId, fetchBalanceBlocking(playerId));
                } catch (Exception ex) {
                    plugin.getLogger().warning("Balance sync failed for " + playerId + ": " + ex.getMessage());
                }
            }
            return balances;
        });
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
    public boolean createWalletFor(String playerId, String playerName) {
        try {
            if (masterAdminKey == null || masterAdminKey.isBlank()) {
                plugin.getLogger().warning("Missing lnbits.adminKey in config.yml");
                return false;
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

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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

            wallets.put(playerId, data);
            walletOwnerLookup.put(data.walletId, playerId);
            saveWallet(playerId, data);

            if (invoiceMonitor != null) {
                invoiceMonitor.watchWallet(data.walletId, data.invoiceKey);
            }
            debug.info("Wallet provisioned for " + playerName + " id=" + data.walletId);
            return true;

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create wallet: " + e.getMessage());
            debug.error("createWalletFor exception", e);
            return false;
        }
    }
    public void attachInvoiceMonitor(WebSocketInvoiceMonitor monitor) {
        this.invoiceMonitor = monitor;
        if (monitor == null) {
            return;
        }

        debug.info("Invoice monitor attached; registering " + wallets.size() + " wallets");
        wallets.values().forEach(data -> monitor.watchWallet(data.walletId, data.invoiceKey));
    }

    // -----------------------------------------------------
    // INTERNAL HELPERS
    // -----------------------------------------------------
    private long fetchBalanceBlocking(String playerId) {
        WalletData data = wallets.get(playerId);
        if (data == null) {
            throw new IllegalStateException("Wallet not found for " + playerId);
        }

        debug.debug("Issuing balance fetch for wallet=" + data.walletId + " adminKey=" + maskKey(data.adminKey));

        String url = lnbitsBaseUrl + "/api/v1/wallet";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Api-Key", data.adminKey)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        try {
            debug.debug("Requesting wallet info for playerId=" + playerId + " url=" + url);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                debug.debug("Wallet info status=" + response.statusCode() + " body=" + response.body());
                JsonObject json = plugin.getGson().fromJson(response.body(), JsonObject.class);
                if (json == null) {
                    throw new IllegalStateException("Empty wallet response");
                }

                return extractBalanceSats(json);
            }

            debug.warn("Wallet info failed status=" + response.statusCode() + " body=" + response.body());
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        } catch (Exception e) {
            debug.error("Wallet info request failed for playerId=" + playerId, e);
            throw new IllegalStateException("Wallet info request failed: " + e.getMessage(), e);
        }
    }

    private long extractBalanceSats(JsonObject json) {
        long balanceMsat = 0L;

        if (json.has("balance_msat")) {
            balanceMsat = json.get("balance_msat").getAsLong();
        } else if (json.has("balance")) {
            balanceMsat = json.get("balance").getAsLong();
        } else if (json.has("wallet_balance")) {
            balanceMsat = json.get("wallet_balance").getAsLong();
        }

        return balanceMsat / 1000L;
    }

    public UUID getOwnerUuidByWalletId(String walletId) {
        String ownerId = walletOwnerLookup.get(walletId);
        if (ownerId == null) {
            return null;
        }
        try {
            return UUID.fromString(ownerId);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid UUID stored for wallet " + walletId + ": " + ownerId);
            return null;
        }
    }

    public String getOwnerNameByWalletId(String walletId) {
        String ownerId = walletOwnerLookup.get(walletId);
        if (ownerId == null) {
            return null;
        }
        WalletData data = wallets.get(ownerId);
        return data != null ? data.ownerName : null;
    }

    public String getLnbitsBaseUrl() {
        return lnbitsBaseUrl;
    }

    private String resolveBaseUrl() {
        String url = plugin.getConfig().getString("lnbits.url", "");
        if (url == null || url.isBlank()) {
            String host = plugin.getConfig().getString("lnbits.host", "127.0.0.1:5000");
            boolean useHttps = plugin.getConfig().getBoolean("lnbits.use_https", false);
            url = (useHttps ? "https://" : "http://") + host;
        }
        url = url.trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        debug.debug("Resolved LNbits base URL=" + url);
        return url;
    }

    private String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "<none>";
        }
        if (key.length() <= 8) {
            return key.charAt(0) + "***" + key.charAt(key.length() - 1);
        }
        return key.substring(0, 4) + "***" + key.substring(key.length() - 4);
    }
}
