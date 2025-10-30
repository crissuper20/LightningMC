package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.clients.LNClient;
import com.crissuper20.lightning.clients.LNbitsClient;
import com.crissuper20.lightning.clients.LNDClient;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

public class LNService {
    
    private final LightningPlugin plugin;
    private final LNClient client;
    private final String backendType;

    public LNService(LightningPlugin plugin) {
        this.plugin = plugin;
        
        plugin.getDebugLogger().info("Initializing Lightning Service...");
        
        try {
            this.backendType = plugin.getConfig().getString("backend", "lnbits").toLowerCase();
            plugin.getDebugLogger().debug("Backend type: " + backendType);
            validateConfiguration();
            checkMainnetAndAbort();
            this.client = createClient();
            
            plugin.getDebugLogger().info("Lightning Service initialized: " + client.getBackendName());
            
        } catch (IllegalStateException e) {
            plugin.getLogger().severe("Configuration error: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize Lightning Service: " + e.getMessage());
            throw new IllegalStateException("Lightning Service initialization failed", e);
        }
    }
    private LNClient createClient() {
        plugin.getDebugLogger().debug("Creating " + backendType + " client...");
        
        switch (backendType) {
            case "lnd":
                return new LNDClient(plugin);
                
            case "lnbits":
                return new LNbitsClient(plugin);
                
            default:
                throw new IllegalArgumentException(
                    "Unknown backend: '" + backendType + "'. Supported: lnbits, lnd"
                );
        }
    }

    private void validateConfiguration() {
        plugin.getDebugLogger().debug("Validating configuration...");
        plugin.saveDefaultConfig();
        String hostKey = backendType.equals("lnd") ? "lnd.host" : "lnbits.host";
        String host = plugin.getConfig().getString(hostKey);
        
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalStateException("Missing required config: " + hostKey);
        }
        if (backendType.equals("lnbits")) {
            validateLNbitsConfig();
        } else if (backendType.equals("lnd")) {
            validateLNDConfig();
        }
        if (host.endsWith(".onion")) {
            validateTorConfig(host);
        }
        
        plugin.getDebugLogger().debug("Configuration validation successful");
    }

    private void validateLNbitsConfig() {
        String apiKey = plugin.getConfig().getString("lnbits.api_key");
        
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Missing required config: lnbits.api_key");
        }
        
        if (apiKey.length() < 32) {
            throw new IllegalStateException(
                "Invalid lnbits.api_key: Key appears too short (expected 32+ characters)"
            );
        }
    }

    private void validateLNDConfig() {
        int port = plugin.getConfig().getInt("lnd.port", -1);
        if (port <= 0 || port > 65535) {
            throw new IllegalStateException(
                "Invalid lnd.port: Must be between 1 and 65535 (got: " + port + ")"
            );
        }
        
        String macaroonHex = plugin.getConfig().getString("lnd.macaroon_hex", "");
        String macaroonPath = plugin.getConfig().getString("lnd.macaroon_path", "");
        
        if (macaroonHex.isEmpty() && macaroonPath.isEmpty()) {
            throw new IllegalStateException(
                "Missing required config: Either lnd.macaroon_hex or lnd.macaroon_path must be set"
            );
        }
        
        // Validate macaroon file exists if path is provided
        if (!macaroonPath.isEmpty() && !Files.exists(Paths.get(macaroonPath))) {
            throw new IllegalStateException(
                "Invalid lnd.macaroon_path: File does not exist: " + macaroonPath
            );
        }
    }

    private void validateTorConfig(String host) {
        String prefix = backendType.equals("lnd") ? "lnd" : "lnbits";
        
        boolean useTorProxy = plugin.getConfig().getBoolean(prefix + ".use_tor_proxy", false);
        if (!useTorProxy) {
            throw new IllegalStateException(
                "Configuration error: .onion address '" + host + "' requires Tor proxy.\n" +
                "Set " + prefix + ".use_tor_proxy: true in config.yml"
            );
        }
        
        boolean useHttps = plugin.getConfig().getBoolean(prefix + ".use_https", true);
        boolean skipTls = plugin.getConfig().getBoolean(prefix + ".skip_tls_verify", false);
        
        if (useHttps && !skipTls) {
            throw new IllegalStateException(
                "Configuration error: .onion address '" + host + "' with HTTPS requires TLS verification to be skipped.\n" +
                "Either set " + prefix + ".use_https: false\n" +
                "OR set " + prefix + ".skip_tls_verify: true"
            );
        }
        
        String torHost = plugin.getConfig().getString(prefix + ".tor_proxy_host", "");
        int torPort = plugin.getConfig().getInt(prefix + ".tor_proxy_port", -1);
        
        if (torHost.isEmpty() || torPort <= 0 || torPort > 65535) {
            throw new IllegalStateException(
                "Invalid Tor proxy configuration for .onion address.\n" +
                "Check " + prefix + ".tor_proxy_host and " + prefix + ".tor_proxy_port in config.yml"
            );
        }
    }

    private void checkMainnetAndAbort() {
        String globalNet = plugin.getConfig().getString("network", "").trim();
        String backendNetKey = backendType.equals("lnd") ? "lnd.network" : "lnbits.network";
        String backendNet = plugin.getConfig().getString(backendNetKey, "").trim();
        
        boolean isMainnet = "mainnet".equalsIgnoreCase(globalNet) || 
                           "mainnet".equalsIgnoreCase(backendNet);
        
        if (isMainnet) {
            String msg = "avoid using mainnet for this plugin" +
                        "this plugin avoids using mainnet (real money). Use testnet instead.";
            
            plugin.getLogger().severe("=".repeat(60));
            plugin.getLogger().severe(msg);
            plugin.getLogger().severe("=".repeat(60));
            
            // Try to disable plugin
            try {
                plugin.getServer().getPluginManager().disablePlugin(plugin);
            } catch (Throwable t) {
                plugin.getDebugLogger().error("Could not disable plugin programmatically", t);
            }
            
            throw new IllegalStateException(msg);
        }
    }

    /**
     * Get wallet/node information
     */
    public CompletableFuture<LNClient.LNResponse<JsonObject>> getWalletInfoAsync() {
        return client.getWalletInfoAsync();
    }

    /**
     * Get wallet balance in satoshis
     * @param walletId Wallet ID (unused for some backends)
     */
    public CompletableFuture<LNClient.LNResponse<Long>> getBalanceAsync(String walletId) {
        return client.getBalanceAsync(walletId);
    }

    /**
     * Create a Lightning invoice
     * @param amountSats Amount in satoshis
     * @param memo Invoice description/memo
     */
    public CompletableFuture<LNClient.LNResponse<LNClient.Invoice>> createInvoiceAsync(
        long amountSats, 
        String memo
    ) {
        return client.createInvoiceAsync(amountSats, memo);
    }

    /**
     * Check if an invoice has been paid
     * @param paymentHash Invoice payment hash
     */
    public CompletableFuture<LNClient.LNResponse<Boolean>> checkInvoiceAsync(String paymentHash) {
        return client.checkInvoiceAsync(paymentHash);
    }

    /**
     * Pay a Lightning invoice
     * @param bolt11 BOLT11 payment request
     */
    public CompletableFuture<LNClient.LNResponse<JsonObject>> payInvoiceAsync(String bolt11) {
        return client.payInvoiceAsync(bolt11);
    }

    /**
     * Check if the Lightning backend is healthy
     */
    public boolean isHealthy() {
        return client.isHealthy();
    }

    /**
     * Get detailed health metrics
     */
    public LNClient.HealthMetrics getHealthMetrics() {
        return client.getHealthMetrics();
    }

    /**
     * Shutdown the Lightning service and cleanup resources
     */
    public void shutdown() {
        plugin.getDebugLogger().info("Shutting down Lightning Service...");
        client.shutdown();
        plugin.getDebugLogger().info("Lightning Service shutdown complete");
    }

    /**
     * Get backend type string (lnbits/lnd)
     */
    public String getBackend() {
        return backendType;
    }

    /**
     * Get backend display name
     */
    public String getBackendName() {
        return client.getBackendName();
    }

    /**
     * Get the underlying client (for advanced usage)
     */
    public LNClient getClient() {
        return client;
    }
}