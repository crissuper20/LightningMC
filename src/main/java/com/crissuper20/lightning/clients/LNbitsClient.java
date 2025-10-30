package com.crissuper20.lightning.clients;

import com.crissuper20.lightning.LightningPlugin;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class LNbitsClient extends AbstractLNClient {
    
    private final String host;
    private final String apiKey;
    private final boolean useHttps;
    private final boolean skipTlsVerify;
    private final boolean useTorProxy;
    private final String torProxyHost;
    private final int torProxyPort;

    public LNbitsClient(LightningPlugin plugin) {
        super(plugin);
        
        // Load LNbits-specific configuration
        this.host = plugin.getConfig().getString("lnbits.host", "localhost");
        this.apiKey = plugin.getConfig().getString("lnbits.api_key", "");
        this.useHttps = plugin.getConfig().getBoolean("lnbits.use_https", true);
        this.skipTlsVerify = plugin.getConfig().getBoolean("lnbits.skip_tls_verify", false);
        this.useTorProxy = plugin.getConfig().getBoolean("lnbits.use_tor_proxy", false);
        this.torProxyHost = plugin.getConfig().getString("lnbits.tor_proxy_host", "127.0.0.1");
        this.torProxyPort = plugin.getConfig().getInt("lnbits.tor_proxy_port", 9050);
        
        logger.debug("LNbits configuration loaded:");
        logger.debug("  Host: " + host);
        logger.debug("  Use HTTPS: " + useHttps);
        logger.debug("  Use Tor: " + useTorProxy);
        logger.debug("  API Key length: " + apiKey.length());
    }
    public String getBackendName() {
        return "LNbits";
    }
    protected boolean shouldUseTor() {
        return useTorProxy;
    }
    protected boolean shouldSkipTlsVerify() {
        return skipTlsVerify;
    }
    protected String getTorProxyHost() {
        return torProxyHost;
    }
    protected int getTorProxyPort() {
        return torProxyPort;
    }

    private String baseUrl() {
        String protocol = useHttps ? "https://" : "http://";
        return protocol + host + "/api/v1";
    }

    @Override
    public CompletableFuture<LNResponse<JsonObject>> getWalletInfoAsync() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl() + "/wallet";
            
            logger.debug("=== getWalletInfo Request (LNbits) ===");
            logger.debug("URL: " + url);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Api-Key", apiKey)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
            
            long startTime = System.currentTimeMillis();
            
            try {
                HttpResponse<String> response = httpClient.send(
                    request, 
                    HttpResponse.BodyHandlers.ofString()
                );
                
                long duration = System.currentTimeMillis() - startTime;
                logger.debug("Response received in " + duration + "ms");
                logger.debug("Status: " + response.statusCode());
                
                if (response.statusCode() == 200) {
                    JsonObject data = gson.fromJson(response.body(), JsonObject.class);
                    logger.info("Wallet info retrieved successfully");
                    return LNResponse.success(data, response.statusCode());
                } else {
                    plugin.getLogger().warning("Wallet request failed: " + response.statusCode());
                    return LNResponse.failure("HTTP " + response.statusCode() + ": " + 
                        response.body(), response.statusCode());
                }
            } catch (Exception e) {
                logNetworkError(e, url, "X-Api-Key: " + maskSecret(apiKey));
                return LNResponse.failure("Network error: " + e.getMessage(), -1);
            }
        }, httpExecutor);
    }

    @Override
    public CompletableFuture<LNResponse<Long>> getBalanceAsync(String walletId) {
        logger.debug("=== getBalance Request (LNbits) ===");
        
        // LNbits balance is in the wallet info response
        return getWalletInfoAsync().thenApply(walletInfo -> {
            if (walletInfo.success) {
                long balanceMsat = walletInfo.data.get("balance").getAsLong();
                long balance = balanceMsat / 1000; // msat to sat
                logger.debug("Balance: " + balanceMsat + " msat = " + balance + " sats");
                return LNResponse.success(balance, walletInfo.statusCode);
            }
            logger.debug("Failed to get balance: " + walletInfo.error);
            return LNResponse.failure(walletInfo.error, walletInfo.statusCode);
        });
    }

    @Override
    public CompletableFuture<LNResponse<Invoice>> createInvoiceAsync(long amountSats, String memo) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl() + "/payments";
            
            logger.debug("=== createInvoice Request (LNbits) ===");
            logger.debug("Amount: " + amountSats + " sats");
            logger.debug("Memo: " + memo);
            
            String body = String.format(
                "{\"unit\":\"sat\",\"amount\":%d,\"memo\":\"%s\",\"out\":false}",
                amountSats,
                memo.replace("\"", "\\\"")
            );
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", apiKey)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            
            long startTime = System.currentTimeMillis();
            
            try {
                HttpResponse<String> response = httpClient.send(
                    request, 
                    HttpResponse.BodyHandlers.ofString()
                );
                
                long duration = System.currentTimeMillis() - startTime;
                logger.debug("Response received in " + duration + "ms");
                logger.debug("Status: " + response.statusCode());
                
                if (response.statusCode() == 201 || response.statusCode() == 200) {
                    JsonObject json = gson.fromJson(response.body(), JsonObject.class);
                    
                    if (!json.has("payment_hash")) {
                        logger.error("LNbits response missing payment_hash");
                        return LNResponse.failure("Invalid response: missing payment_hash", 
                            response.statusCode());
                    }
                    
                    String paymentHash = json.get("payment_hash").getAsString();
                    String bolt11;
                    
                    if (json.has("payment_request")) {
                        bolt11 = json.get("payment_request").getAsString();
                    } else if (json.has("bolt11")) {
                        bolt11 = json.get("bolt11").getAsString();
                    } else {
                        logger.error("LNbits response missing payment_request/bolt11");
                        return LNResponse.failure("Invalid response: missing bolt11", 
                            response.statusCode());
                    }
                    
                    logger.debug("Invoice created successfully");
                    Invoice invoice = new Invoice(paymentHash, bolt11, amountSats);
                    return LNResponse.success(invoice, response.statusCode());
                } else {
                    plugin.getLogger().warning("Failed to create invoice: " + response.statusCode());
                    return LNResponse.failure("Failed to create invoice: " + response.body(), 
                        response.statusCode());
                }
            } catch (Exception e) {
                logNetworkError(e, url, "X-Api-Key: " + maskSecret(apiKey));
                return LNResponse.failure("Network error: " + e.getMessage(), -1);
            }
        }, httpExecutor);
    }

    @Override
    public CompletableFuture<LNResponse<Boolean>> checkInvoiceAsync(String paymentHash) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl() + "/payments/" + paymentHash;
            
            logger.debug("=== checkInvoice Request (LNbits) ===");
            logger.debug("Payment hash: " + paymentHash);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Api-Key", apiKey)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
            
            long startTime = System.currentTimeMillis();
            
            try {
                HttpResponse<String> response = httpClient.send(
                    request, 
                    HttpResponse.BodyHandlers.ofString()
                );
                
                long duration = System.currentTimeMillis() - startTime;
                logger.debug("Response received in " + duration + "ms");
                logger.debug("Status: " + response.statusCode());
                
                if (response.statusCode() == 200) {
                    JsonObject json = gson.fromJson(response.body(), JsonObject.class);
                    boolean paid = json.get("paid").getAsBoolean();
                    logger.debug("Invoice paid status: " + paid);
                    return LNResponse.success(paid, response.statusCode());
                } else {
                    return LNResponse.failure("Failed to check invoice", response.statusCode());
                }
            } catch (Exception e) {
                logNetworkError(e, url, "X-Api-Key: " + maskSecret(apiKey));
                return LNResponse.failure("Network error: " + e.getMessage(), -1);
            }
        }, httpExecutor);
    }

@Override
public CompletableFuture<LNResponse<JsonObject>> payInvoiceAsync(String bolt11) {
    return CompletableFuture.supplyAsync(() -> {
        String url = baseUrl() + "/payments";
        
        logger.debug("=== payInvoice Request (LNbits) ===");
        logger.debug("URL: " + url);
        logger.debug("Full BOLT11 length: " + bolt11.length());
        logger.debug("BOLT11 first 50 chars: " + bolt11.substring(0, Math.min(50, bolt11.length())));
        logger.debug("BOLT11 last 50 chars: " + bolt11.substring(Math.max(0, bolt11.length() - 50)));
        logger.debug("Has whitespace: " + (bolt11.length() != bolt11.trim().length()));
        logger.debug("Contains newlines: " + bolt11.contains("\n"));
        
        String body = String.format("{\"out\":true,\"bolt11\":\"%s\"}", bolt11);
        
        logger.debug("Request body: " + body);
        logger.debug("Request body length: " + body.length());
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("X-Api-Key", apiKey)
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        
        long startTime = System.currentTimeMillis();
        
        try {
            HttpResponse<String> response = httpClient.send(
                request, 
                HttpResponse.BodyHandlers.ofString()
            );
            
            long duration = System.currentTimeMillis() - startTime;
            logger.debug("Response received in " + duration + "ms");
            logger.debug("Response status: " + response.statusCode());
            logger.debug("Response body: " + response.body());
            logger.debug("=====================================");
            
            if (response.statusCode() == 201 || response.statusCode() == 200) {
                JsonObject data = gson.fromJson(response.body(), JsonObject.class);
                logger.info("Payment sent successfully");
                return LNResponse.success(data, response.statusCode());
            } else {
                plugin.getLogger().warning("Payment failed with status: " + response.statusCode());
                logger.debug("Full error response: " + response.body());
                return LNResponse.failure("Payment failed: " + response.body(), 
                    response.statusCode());
            }
        } catch (Exception e) {
            logger.debug("Exception during payment: " + e.getClass().getName() + " - " + e.getMessage());
            logNetworkError(e, url, "X-Api-Key: " + maskSecret(apiKey));
            return LNResponse.failure("Network error: " + e.getMessage(), -1);
        }
    }, httpExecutor);
}
}