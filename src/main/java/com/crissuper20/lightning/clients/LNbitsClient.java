package com.crissuper20.lightning.clients;

import com.crissuper20.lightning.LightningPlugin;
import com.google.gson.*;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.*;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.*;

/**
 * LNbitsClient — Standalone implementation (no interface)
 * 
 * NOTE: This class is deprecated in favor of LNService which now contains
 * all Lightning logic directly. This file can be safely deleted.
 * 
 * Supports both global admin wallet and per-player LNbits wallets.
 * Includes Tor proxy support and TLS verification options.
 */
@Deprecated
public class LNbitsClient {

    private final LightningPlugin plugin;
    private final String apiBase;
    private final String adminKey;
    private final HttpClient httpClient;
    
    // Health tracking
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong lastSuccessTime = new AtomicLong(System.currentTimeMillis());
    private final ScheduledExecutorService healthChecker;
    
    // Executor for async operations
    private final ExecutorService executor;

    public LNbitsClient(LightningPlugin plugin) {
        this.plugin = plugin;
        
        // Read configuration
        String host = plugin.getConfig().getString("lnbits.host");
        boolean useHttps = plugin.getConfig().getBoolean("lnbits.use_https", true);
        this.apiBase = (useHttps ? "https://" : "http://") + host + "/api/v1";
        this.adminKey = plugin.getConfig().getString("lnbits.api_key");
        
        // Create HTTP client with Tor/TLS options
        this.httpClient = createHttpClient();
        
        // Create executor service
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "LNbits-Worker");
            t.setDaemon(true);
            return t;
        });
        
        // Create health checker
        this.healthChecker = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "LNbits-HealthCheck");
            t.setDaemon(true);
            return t;
        });
        
        // Start health monitoring
        startHealthMonitoring();
        
        plugin.getDebugLogger().info("LNbits client initialized for base: " + apiBase);
    }

    /**
     * Create HTTP client with optional Tor proxy and TLS verification settings
     */
    private HttpClient createHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL);

        // Tor proxy support
        boolean useTor = plugin.getConfig().getBoolean("lnbits.use_tor_proxy", false);
        if (useTor) {
            String torHost = plugin.getConfig().getString("lnbits.tor_proxy_host", "127.0.0.1");
            int torPort = plugin.getConfig().getInt("lnbits.tor_proxy_port", 9050);
            
            ProxySelector proxy = ProxySelector.of(
                new InetSocketAddress(torHost, torPort)
            );
            builder.proxy(proxy);
            plugin.getDebugLogger().info("LNbits using Tor proxy: " + torHost + ":" + torPort);
        }

        // Skip TLS verification (for self-signed certs)
        boolean skipTls = plugin.getConfig().getBoolean("lnbits.skip_tls_verify", false);
        if (skipTls) {
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new java.security.SecureRandom());
                builder.sslContext(sslContext);
                plugin.getDebugLogger().warning("LNbits TLS verification DISABLED - use only for testing!");
            } catch (Exception e) {
                plugin.getDebugLogger().error("Failed to disable TLS verification", e);
            }
        }

        return builder.build();
    }

    /**
     * Start periodic health monitoring
     */
    private void startHealthMonitoring() {
        healthChecker.scheduleAtFixedRate(() -> {
            try {
                getWalletInfoAsync().thenAccept(response -> {
                    if (response.success) {
                        consecutiveFailures.set(0);
                        lastSuccessTime.set(System.currentTimeMillis());
                    } else {
                        consecutiveFailures.incrementAndGet();
                    }
                });
            } catch (Exception e) {
                consecutiveFailures.incrementAndGet();
                plugin.getDebugLogger().error("Health check failed", e);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    public String getBackendName() {
        return "LNbits";
    }

    public boolean isHealthy() {
        return consecutiveFailures.get() < 3;
    }

    public HealthMetrics getHealthMetrics() {
        long lastSuccess = lastSuccessTime.get();
        long timeSinceSuccess = System.currentTimeMillis() - lastSuccess;
        
        return new HealthMetrics(
            isHealthy(),
            consecutiveFailures.get(),
            lastSuccess,
            timeSinceSuccess
        );
    }

    // ================================================================
    // Response & Data Classes (duplicated from LNService)
    // ================================================================

    public static class LNResponse<T> {
        public final boolean success;
        public final T data;
        public final String error;
        public final int statusCode;
        
        public LNResponse(boolean success, T data, String error, int statusCode) {
            this.success = success;
            this.data = data;
            this.error = error;
            this.statusCode = statusCode;
        }

        public static <T> LNResponse<T> success(T data, int statusCode) {
            return new LNResponse<>(true, data, null, statusCode);
        }
        
        public static <T> LNResponse<T> failure(String error, int statusCode) {
            return new LNResponse<>(false, null, error, statusCode);
        }
    }
    
    public static class Invoice {
        public final String paymentHash;
        public final String bolt11;
        public final long amount;
        
        public Invoice(String bolt11, String paymentHash, long amount) {
            this.bolt11 = bolt11;
            this.paymentHash = paymentHash;
            this.amount = amount;
        }
        
        @Override
        public String toString() {
            return "Invoice{hash=" + paymentHash.substring(0, 8) + "..., amount=" + amount + "}";
        }
    }

    public static class HealthMetrics {
        public final boolean healthy;
        public final int consecutiveFailures;
        public final long lastSuccessTime;
        public final long timeSinceSuccess;
        
        public HealthMetrics(boolean healthy, int consecutiveFailures, 
                           long lastSuccessTime, long timeSinceSuccess) {
            this.healthy = healthy;
            this.consecutiveFailures = consecutiveFailures;
            this.lastSuccessTime = lastSuccessTime;
            this.timeSinceSuccess = timeSinceSuccess;
        }
        
        @Override
        public String toString() {
            return "HealthMetrics{healthy=" + healthy + 
                ", failures=" + consecutiveFailures + 
                ", timeSinceSuccess=" + timeSinceSuccess + "ms}";
        }
    }

    // ================================================================
    // Shared / Admin wallet
    // ================================================================

    public CompletableFuture<LNResponse<JsonObject>> getWalletInfoAsync() {
        return sendGet("/wallet", adminKey);
    }

    public CompletableFuture<LNResponse<Long>> getBalanceAsync(String walletId) {
        return getWalletInfoAsync().thenApply(resp -> {
            if (!resp.success) return LNResponse.failure(resp.error, resp.statusCode);
            long sats = resp.data.get("balance").getAsLong() / 1000;
            return LNResponse.success(sats, resp.statusCode);
        });
    }

    public CompletableFuture<LNResponse<Invoice>> createInvoiceAsync(long amountSats, String memo) {
        return createInvoiceAsync(amountSats, memo, adminKey);
    }

    public CompletableFuture<LNResponse<Boolean>> checkInvoiceAsync(String paymentHash) {
        return checkInvoiceAsync(paymentHash, adminKey);
    }

    public CompletableFuture<LNResponse<JsonObject>> payInvoiceAsync(String bolt11) {
        return payInvoiceAsync(bolt11, adminKey);
    }

    // ================================================================
    // Multi-wallet (per-player) API
    // ================================================================

    public CompletableFuture<LNResponse<JsonObject>> getWalletInfoAsync(String key) {
        return sendGet("/wallet", key);
    }

    public CompletableFuture<LNResponse<Invoice>> createInvoiceAsync(long amountSats, String memo, String key) {
        JsonObject body = new JsonObject();
        body.addProperty("out", false);
        body.addProperty("amount", amountSats);
        body.addProperty("memo", memo);
        
        return sendPost("/payments", key, body)
            .thenApply(resp -> {
                if (!resp.success) return LNResponse.failure(resp.error, resp.statusCode);
                JsonObject obj = resp.data;
                Invoice invoice = new Invoice(
                    obj.get("payment_request").getAsString(),
                    obj.get("payment_hash").getAsString(),
                    amountSats
                );
                return LNResponse.success(invoice, resp.statusCode);
            });
    }

    public CompletableFuture<LNResponse<Boolean>> checkInvoiceAsync(String paymentHash, String key) {
        return sendGet("/payments/" + paymentHash, key)
            .thenApply(resp -> {
                if (!resp.success) return LNResponse.failure(resp.error, resp.statusCode);
                boolean paid = resp.data.has("paid") && resp.data.get("paid").getAsBoolean();
                return LNResponse.success(paid, resp.statusCode);
            });
    }

    public CompletableFuture<LNResponse<JsonObject>> payInvoiceAsync(String bolt11, String key) {
        JsonObject body = new JsonObject();
        body.addProperty("bolt11", bolt11);
        return sendPost("/payments", key, body);
    }

    // ================================================================
    // HTTP helpers
    // ================================================================

    private CompletableFuture<LNResponse<JsonObject>> sendGet(String path, String key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + path))
                    .header("X-Api-Key", key)
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

                HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                return parseResponse(response);
            } catch (Exception e) {
                plugin.getDebugLogger().error("HTTP GET failed: " + path, e);
                consecutiveFailures.incrementAndGet();
                return LNResponse.failure("Request failed: " + e.getMessage(), 500);
            }
        }, executor);
    }

    private CompletableFuture<LNResponse<JsonObject>> sendPost(String path, String key, JsonObject body) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + path))
                    .header("X-Api-Key", key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .timeout(Duration.ofSeconds(30))
                    .build();

                HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                return parseResponse(response);
            } catch (Exception e) {
                plugin.getDebugLogger().error("HTTP POST failed: " + path, e);
                consecutiveFailures.incrementAndGet();
                return LNResponse.failure("Request failed: " + e.getMessage(), 500);
            }
        }, executor);
    }

    private LNResponse<JsonObject> parseResponse(HttpResponse<String> resp) {
        int code = resp.statusCode();
        
        try {
            JsonElement parsed = JsonParser.parseString(resp.body());
            
            if (code >= 200 && code < 300) {
                consecutiveFailures.set(0);
                lastSuccessTime.set(System.currentTimeMillis());
                return LNResponse.success(parsed.getAsJsonObject(), code);
            }
            
            return LNResponse.failure(parsed.toString(), code);
        } catch (JsonSyntaxException e) {
            plugin.getDebugLogger().error("Failed to parse JSON: " + e.getMessage());
            return LNResponse.failure("Invalid JSON: " + resp.body(), code);
        }
    }

    public void shutdown() {
        plugin.getDebugLogger().info("Shutting down LNbits client...");
        
        // Shutdown health checker
        try {
            healthChecker.shutdown();
            if (!healthChecker.awaitTermination(5, TimeUnit.SECONDS)) {
                healthChecker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            healthChecker.shutdownNow();
        }
        
        // Shutdown executor
        try {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        
        plugin.getDebugLogger().info("LNbits client fully shut down.");
    }

    /**
     * Trust manager that accepts all certificates (use only for testing!)
     */
    private static class TrustAllManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        
        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}