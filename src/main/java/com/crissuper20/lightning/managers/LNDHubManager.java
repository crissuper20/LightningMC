package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LNDHub Manager - Generates LNDHub URIs for Zeus wallet integration
 * 
 * FIXED: 
 * - Separate HTTPS config for external LNDHub access
 * - Correct LNDHub extension path format (/lndhub/ext/)
 */
public class LNDHubManager {
    
    private final LightningPlugin plugin;
    private final WalletManager walletManager;
    private final String lndhubHost;
    private final boolean lndhubUseHttps;
    
    // Cache generated URIs
    private final ConcurrentHashMap<UUID, String> lndhubCache;
    
    private final boolean lndhubEnabled;
    
    public LNDHubManager(LightningPlugin plugin) {
        this.plugin = plugin;
        this.walletManager = plugin.getWalletManager();
        this.lndhubCache = new ConcurrentHashMap<>();
        
        // Check if LNDHub is enabled
        this.lndhubEnabled = plugin.getConfig().getBoolean("give_user_lndhub", true);
        
        if (!lndhubEnabled) {
            plugin.getDebugLogger().info("LNDHub Manager initialized but DISABLED");
            this.lndhubHost = null;
            this.lndhubUseHttps = false;
            return;
        }
        
        // FIXED: Check multiple possible config locations for lndhub_address
        String configuredAddress = null;
        
        // 1. Try root level lndhub_address first
        if (plugin.getConfig().contains("lndhub_address")) {
            configuredAddress = plugin.getConfig().getString("lndhub_address");
            plugin.getDebugLogger().debug("Found lndhub_address at root level: " + configuredAddress);
        }
        
        // 2. Try lnbits.lndhub_address as fallback
        if ((configuredAddress == null || configuredAddress.trim().isEmpty()) 
            && plugin.getConfig().contains("lnbits.lndhub_address")) {
            configuredAddress = plugin.getConfig().getString("lnbits.lndhub_address");
            plugin.getDebugLogger().debug("Found lndhub_address in lnbits section: " + configuredAddress);
        }
        
        // 3. Use configured address if valid, otherwise fall back to lnbits.host
        if (configuredAddress != null && !configuredAddress.trim().isEmpty()) {
            this.lndhubHost = configuredAddress.trim();
            plugin.getDebugLogger().info("Using custom LNDHub address: " + this.lndhubHost);
        } else {
            this.lndhubHost = plugin.getConfig().getString("lnbits.host");
            plugin.getDebugLogger().info("Using lnbits.host for LNDHub: " + this.lndhubHost);
        }
        
        // FIXED: Separate HTTPS config for external LNDHub access
        // Check for lndhub-specific HTTPS setting first, then fall back to lnbits.use_https
        if (plugin.getConfig().contains("lndhub_use_https")) {
            this.lndhubUseHttps = plugin.getConfig().getBoolean("lndhub_use_https", true);
            plugin.getDebugLogger().debug("Using lndhub_use_https config: " + this.lndhubUseHttps);
        } else if (plugin.getConfig().contains("lnbits.lndhub_use_https")) {
            this.lndhubUseHttps = plugin.getConfig().getBoolean("lnbits.lndhub_use_https", true);
            plugin.getDebugLogger().debug("Using lnbits.lndhub_use_https config: " + this.lndhubUseHttps);
        } else {
            this.lndhubUseHttps = plugin.getConfig().getBoolean("lnbits.use_https", true);
            plugin.getDebugLogger().debug("Using lnbits.use_https as fallback: " + this.lndhubUseHttps);
        }
        
        plugin.getDebugLogger().info("LNDHub Manager initialized");
        plugin.getDebugLogger().info("  Enabled: " + lndhubEnabled);
        plugin.getDebugLogger().info("  Address: " + lndhubHost);
        plugin.getDebugLogger().info("  Protocol: " + (lndhubUseHttps ? "https" : "http"));
        plugin.getDebugLogger().info("Players can connect Zeus wallet to their in-game wallet");
    }
    
    public boolean isEnabled() {
        return lndhubEnabled;
    }
    
    /**
     * Generate LNDHub URI for a player's wallet
     * 
     * Format for Zeus: lndhub://admin:ADMIN_KEY@https://your-server.com/lndhub/ext/
     * 
     * @param player The player
     * @return CompletableFuture with LNDHub URI string
     */
    public CompletableFuture<String> generateLNDHubURI(Player player) {
        UUID uuid = player.getUniqueId();
        
        // Check cache first
        if (lndhubCache.containsKey(uuid)) {
            plugin.getDebugLogger().debug("Using cached LNDHub URI for " + player.getName());
            return CompletableFuture.completedFuture(lndhubCache.get(uuid));
        }
        
        return walletManager.getOrCreateWallet(player)
            .thenApply(walletJson -> {
                if (!walletJson.has("id")) {
                    throw new RuntimeException("Failed to get wallet info");
                }
                
                String adminKey = walletManager.getPlayerAdminKey(player);
                String invoiceKey = walletManager.getPlayerInvoiceKey(player);
                
                if (adminKey == null || invoiceKey == null) {
                    throw new RuntimeException("Failed to get wallet keys");
                }
                
                // Build LNDHub URI for Zeus
                // FIXED: Correct LNDHub extension format with /lndhub/ext/ path
                String protocol = lndhubUseHttps ? "https" : "http";
                String baseUrl = protocol + "://" + lndhubHost;
                
                // Ensure the path ends with /lndhub/ext/ for LNDHub extension compatibility
                if (!baseUrl.endsWith("/")) {
                    baseUrl += "/";
                }
                baseUrl += "lndhub/ext/";
                
                // Standard LNDHub format that Zeus understands
                String zeusUri = String.format("lndhub://admin:%s@%s",
                    adminKey,
                    baseUrl
                );
                
                // Cache the result
                lndhubCache.put(uuid, zeusUri);
                
                plugin.getDebugLogger().info("Generated LNDHub URI for " + player.getName());
                plugin.getDebugLogger().debug("  Host: " + lndhubHost);
                plugin.getDebugLogger().debug("  Protocol: " + protocol);
                plugin.getDebugLogger().debug("  Full URL: " + baseUrl);
                plugin.getDebugLogger().debug("  Admin key (first 8): " + adminKey.substring(0, Math.min(8, adminKey.length())) + "...");
                
                return zeusUri;
            });
    }
    
    /**
     * Generate a QR-friendly LNDHub URI (base64 encoded)
     */
    public CompletableFuture<String> generateQRFriendlyURI(Player player) {
        return generateLNDHubURI(player)
            .thenApply(uri -> {
                // Encode as base64 for easier QR scanning
                return Base64.getEncoder().encodeToString(uri.getBytes(StandardCharsets.UTF_8));
            });
    }
    
    /**
     * Clear cache for a player (call when wallet is recreated)
     */
    public void clearCache(UUID playerId) {
        lndhubCache.remove(playerId);
        plugin.getDebugLogger().debug("Cleared LNDHub cache for player: " + playerId);
    }
    
    /**
     * Clear all cached URIs
     */
    public void clearAllCache() {
        int count = lndhubCache.size();
        lndhubCache.clear();
        plugin.getDebugLogger().info("Cleared " + count + " cached LNDHub URIs");
    }
    
    /**
     * Format LNDHub info for display to player
     */
    public CompletableFuture<String[]> formatLNDHubInfo(Player player) {
        return generateLNDHubURI(player)
            .thenApply(uri -> {
                String[] lines = new String[]{
                    "§6§l=== Zeus Wallet Connection ===",
                    "",
                    "§7Connect your Zeus wallet to use your",
                    "§7in-game Lightning wallet on your phone!",
                    "",
                    "§eSteps:",
                    "§71. Install Zeus from https://zeusln.com",
                    "§72. Open Zeus → Add Node → Scan LNDHub QR",
                    "§73. Use §f/wallet givelogin qr §7to get QR code",
                    "",
                    "§eConnection String:",
                    "§8" + truncateForDisplay(uri),
                    "",
                    "§7Or copy full string:",
                    "§f/wallet givelogin copy",
                    "",
                    "§c⚠ Keep this private! Anyone with this",
                    "§ccan access your wallet!"
                };
                return lines;
            });
    }
    
    /**
     * Truncate URI for display (hide sensitive parts)
     */
    private String truncateForDisplay(String uri) {
        if (uri.length() <= 80) {
            return uri;
        }
        
        // Find the @ separator (splits credentials from host)
        int split = uri.indexOf("@");
        if (split > 0) {
            // Show protocol and first few chars of credential
            String prefix = uri.substring(0, Math.min(split - 30, 30));
            String suffix = uri.substring(split); // Keep the @host part
            return prefix + "...[HIDDEN]..." + suffix;
        }
        
        // Fallback: just truncate middle
        return uri.substring(0, 40) + "..." + uri.substring(uri.length() - 40);
    }
    
    /**
     * Validate that LNDHub connection works
     */
    public CompletableFuture<Boolean> testLNDHubConnection(Player player) {
        return generateLNDHubURI(player)
            .thenCompose(uri -> {
                // Test by trying to get wallet info through LNDHub endpoint
                String adminKey = walletManager.getPlayerAdminKey(player);
                String protocol = lndhubUseHttps ? "https" : "http";
                String testUrl = protocol + "://" + lndhubHost + "/lndhub/ext/getinfo";
                
                plugin.getDebugLogger().info("Testing LNDHub connection for " + player.getName());
                plugin.getDebugLogger().debug("  Test URL: " + testUrl);
                
                HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(testUrl))
                    .header("Authorization", "Bearer " + adminKey)
                    .GET()
                    .build();
                
                return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
                        
                        if (success) {
                            plugin.getDebugLogger().info("✓ LNDHub test successful for " + player.getName());
                            plugin.getDebugLogger().debug("  Response: " + response.statusCode());
                        } else {
                            plugin.getDebugLogger().warning("✗ LNDHub test failed for " + player.getName());
                            plugin.getDebugLogger().warning("  Status: " + response.statusCode());
                            plugin.getDebugLogger().warning("  Body: " + response.body());
                        }
                        
                        return success;
                    });
            })
            .exceptionally(ex -> {
                plugin.getDebugLogger().error("LNDHub test error for " + player.getName(), ex);
                return false;
            });
    }
    
    /**
     * Get the configured LNDHub address (for diagnostics)
     */
    public String getLNDHubAddress() {
        return lndhubHost;
    }
    
    /**
     * Get the full base URL (for diagnostics)
     */
    public String getBaseURL() {
        String protocol = lndhubUseHttps ? "https" : "http";
        return protocol + "://" + lndhubHost + "/lndhub/ext/";
    }
    
    /**
     * Get cached URI count (for metrics)
     */
    public int getCachedURICount() {
        return lndhubCache.size();
    }
}