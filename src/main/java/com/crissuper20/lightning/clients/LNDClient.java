package com.crissuper20.lightning.clients;

import com.crissuper20.lightning.LightningPlugin;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class LNDClient extends AbstractLNClient {
    
    private final String host;
    private final int port;
    private final String macaroonHex;
    private final boolean useHttps;
    private final boolean skipTlsVerify;
    private final boolean useTorProxy;
    private final String torProxyHost;
    private final int torProxyPort;

    public LNDClient(LightningPlugin plugin) {
        super(plugin);
        
        // Load LND-specific configuration
        this.host = plugin.getConfig().getString("lnd.host", "localhost");
        this.port = plugin.getConfig().getInt("lnd.port", 8080);
        this.useHttps = plugin.getConfig().getBoolean("lnd.use_https", true);
        this.skipTlsVerify = plugin.getConfig().getBoolean("lnd.skip_tls_verify", false);
        this.useTorProxy = plugin.getConfig().getBoolean("lnd.use_tor_proxy", false);
        this.torProxyHost = plugin.getConfig().getString("lnd.tor_proxy_host", "127.0.0.1");
        this.torProxyPort = plugin.getConfig().getInt("lnd.tor_proxy_port", 9050);
        
        // Load macaroon
        this.macaroonHex = loadMacaroon();
        
        logger.debug("LND configuration loaded:");
        logger.debug("  Host: " + host);
        logger.debug("  Port: " + port);
        logger.debug("  Use HTTPS: " + useHttps);
        logger.debug("  Use Tor: " + useTorProxy);
        logger.debug("  Macaroon length: " + macaroonHex.length());
    }

    @Override
    public String getBackendName() {
        return "LND";
    }

    @Override
    protected boolean shouldUseTor() {
        return useTorProxy;
    }

    @Override
    protected boolean shouldSkipTlsVerify() {
        return skipTlsVerify;
    }

    @Override
    protected String getTorProxyHost() {
        return torProxyHost;
    }

    @Override
    protected int getTorProxyPort() {
        return torProxyPort;
    }

    private String baseUrl() {
        String protocol = useHttps ? "https://" : "http://";
        return protocol + host + ":" + port + "/v1";
    }

    private String loadMacaroon() {
        String macaroonHex = plugin.getConfig().getString("lnd.macaroon_hex", "");
        String macaroonPath = plugin.getConfig().getString("lnd.macaroon_path", "");
        
        if (!macaroonHex.isEmpty()) {
            logger.debug("  Macaroon source: hex config");
            return macaroonHex;
        } else if (!macaroonPath.isEmpty()) {
            logger.debug("  Attempting to load macaroon from file: " + macaroonPath);
            try {
                byte[] macaroonBytes = Files.readAllBytes(Paths.get(macaroonPath));
                String hex = bytesToHex(macaroonBytes);
                logger.debug("  Successfully loaded macaroon from file");
                return hex;
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to load macaroon from " + macaroonPath);
                logger.error("Macaroon loading error: " + e.getMessage(), e);
                throw new IllegalStateException("Could not load macaroon", e);
            }
        } else {
            throw new IllegalStateException("No macaroon configured");
        }
    }

    @Override
    public CompletableFuture<LNResponse<JsonObject>> getWalletInfoAsync() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl() + "/getinfo";
            
            logger.debug("=== getWalletInfo Request (LND) ===");
            logger.debug("URL: " + url);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Grpc-Metadata-macaroon", macaroonHex)
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
                    logger.info("Node info retrieved successfully");
                    return LNResponse.success(data, response.statusCode());
                } else {
                    plugin.getLogger().warning("Node info request failed: " + response.statusCode());
                    return LNResponse.failure("HTTP " + response.statusCode() + ": " + 
                        response.body(), response.statusCode());
                }
            } catch (Exception e) {
                logNetworkError(e, url, "Grpc-Metadata-macaroon: " + maskSecret(macaroonHex));
                return LNResponse.failure("Network error: " + e.getMessage(), -1);
            }
        }, httpExecutor);
    }

    @Override
    public CompletableFuture<LNResponse<Long>> getBalanceAsync(String walletId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl() + "/balance/channels";
            
            logger.debug("=== getBalance Request (LND) ===");
            logger.debug("URL: " + url);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Grpc-Metadata-macaroon", macaroonHex)
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
                    JsonObject data = gson.fromJson(response.body(), JsonObject.class);
                    long balance = data.get("balance").getAsLong();
                    logger.debug("Channel balance: " + balance + " sats");
                    return LNResponse.success(balance, response.statusCode());
                }
                return LNResponse.failure("HTTP " + response.statusCode(), response.statusCode());
            } catch (Exception e) {
                logNetworkError(e, url, "Grpc-Metadata-macaroon: " + maskSecret(macaroonHex));
                return LNResponse.failure("Network error: " + e.getMessage(), -1);
            }
        }, httpExecutor);
    }

    @Override
    public CompletableFuture<LNResponse<Invoice>> createInvoiceAsync(long amountSats, String memo) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl() + "/invoices";
            
            logger.debug("=== createInvoice Request (LND) ===");
            logger.debug("Amount: " + amountSats + " sats");
            logger.debug("Memo: " + memo);
            
            String body = String.format(
                "{\"value\":\"%d\",\"memo\":\"%s\"}",
                amountSats,
                memo.replace("\"", "\\\"")
            );
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Grpc-Metadata-macaroon", macaroonHex)
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
                    
                    if (!json.has("r_hash") || !json.has("payment_request")) {
                        logger.error("LND response missing required fields");
                        return LNResponse.failure("Invalid response: missing r_hash or payment_request", 
                            response.statusCode());
                    }
                    
                    String paymentHash = json.get("r_hash").getAsString();
                    String bolt11 = json.get("payment_request").getAsString();
                    
                    logger.debug("Invoice created successfully");
                    Invoice invoice = new Invoice(paymentHash, bolt11, amountSats);
                    return LNResponse.success(invoice, response.statusCode());
                } else {
                    plugin.getLogger().warning("Failed to create invoice: " + response.statusCode());
                    return LNResponse.failure("Failed to create invoice: " + response.body(), 
                        response.statusCode());
                }
            } catch (Exception e) {
                logNetworkError(e, url, "Grpc-Metadata-macaroon: " + maskSecret(macaroonHex));
                return LNResponse.failure("Network error: " + e.getMessage(), -1);
            }
        }, httpExecutor);
    }

    @Override
    public CompletableFuture<LNResponse<Boolean>> checkInvoiceAsync(String paymentHash) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl() + "/invoice/" + paymentHash;
            
            logger.debug("=== checkInvoice Request (LND) ===");
            logger.debug("Payment hash: " + paymentHash);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Grpc-Metadata-macaroon", macaroonHex)
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
                    boolean paid = json.get("settled").getAsBoolean();
                    logger.debug("Invoice settled status: " + paid);
                    return LNResponse.success(paid, response.statusCode());
                } else {
                    return LNResponse.failure("Failed to check invoice", response.statusCode());
                }
            } catch (Exception e) {
                logNetworkError(e, url, "Grpc-Metadata-macaroon: " + maskSecret(macaroonHex));
                return LNResponse.failure("Network error: " + e.getMessage(), -1);
            }
        }, httpExecutor);
    }

    @Override
    public CompletableFuture<LNResponse<JsonObject>> payInvoiceAsync(String bolt11) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl() + "/channels/transactions";
            
            logger.debug("=== payInvoice Request (LND) ===");
            logger.debug("BOLT11: " + bolt11.substring(0, Math.min(50, bolt11.length())) + "...");
            
            String body = String.format("{\"payment_request\":\"%s\"}", bolt11);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Grpc-Metadata-macaroon", macaroonHex)
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
                logger.debug("Status: " + response.statusCode());
                
                if (response.statusCode() == 201 || response.statusCode() == 200) {
                    JsonObject data = gson.fromJson(response.body(), JsonObject.class);
                    logger.info("Payment sent successfully");
                    return LNResponse.success(data, response.statusCode());
                } else {
                    plugin.getLogger().warning("Payment failed: " + response.statusCode());
                    return LNResponse.failure("Payment failed: " + response.body(), 
                        response.statusCode());
                }
            } catch (Exception e) {
                logNetworkError(e, url, "Grpc-Metadata-macaroon: " + maskSecret(macaroonHex));
                return LNResponse.failure("Network error: " + e.getMessage(), -1);
            }
        }, httpExecutor);
    }
}