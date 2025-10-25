package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class LNService {
    private final LightningPlugin plugin;
    private final HttpClient client;
    private final Gson gson;

    // Backend type
    private final BackendType backend;
    
    // LNbits config
    private String lnbitsHost;
    private String lnbitsApiKey;
    private boolean lnbitsUseHttps;
    
    // LND config
    private String lndHost;
    private int lndPort;
    private String lndMacaroonHex;
    private boolean lndUseHttps;

    public enum BackendType {
        LNBITS, LND
    }

    public LNService(LightningPlugin plugin) {
        this.plugin = plugin;
        this.gson = new Gson();

        plugin.getDebugLogger().debug("=== LNService Initialization Started ===");

        // Determine backend type
        String backendStr = plugin.getConfig().getString("backend", "lnbits").toLowerCase();
        this.backend = backendStr.equals("lnd") ? BackendType.LND : BackendType.LNBITS;
        plugin.getDebugLogger().debug("Backend type determined: " + backend);

        // Load configuration
        plugin.getDebugLogger().debug("Loading configuration...");
        loadConfig();

        // Build HTTP client with appropriate settings
        plugin.getDebugLogger().debug("Building HTTP client...");
        this.client = buildHttpClient();

        plugin.getDebugLogger().debug("=== LNService Initialization Complete ===");
        plugin.getDebugLogger().info("LNService initialized with backend: " + backend);
    }

    private void loadConfig() {
        plugin.saveDefaultConfig();

        if (backend == BackendType.LNBITS) {
            plugin.getDebugLogger().debug("Loading LNbits configuration...");
            lnbitsHost = plugin.getConfig().getString("lnbits.host", "localhost");
            lnbitsApiKey = plugin.getConfig().getString("lnbits.api_key", "");
            lnbitsUseHttps = plugin.getConfig().getBoolean("lnbits.use_https", true);
            
            plugin.getDebugLogger().debug("  Host: " + lnbitsHost);
            plugin.getDebugLogger().debug("  Use HTTPS: " + lnbitsUseHttps);
            plugin.getDebugLogger().debug("  API Key length: " + lnbitsApiKey.length() + " chars");
            plugin.getDebugLogger().debug("  API Key preview: " + (lnbitsApiKey.length() > 10 ? lnbitsApiKey.substring(0, 10) + "..." : "***"));
        } else {
            plugin.getDebugLogger().debug("Loading LND configuration...");
            lndHost = plugin.getConfig().getString("lnd.host", "localhost");
            lndPort = plugin.getConfig().getInt("lnd.port", 8080);
            lndUseHttps = plugin.getConfig().getBoolean("lnd.use_https", true);
            
            plugin.getDebugLogger().debug("  Host: " + lndHost);
            plugin.getDebugLogger().debug("  Port: " + lndPort);
            plugin.getDebugLogger().debug("  Use HTTPS: " + lndUseHttps);
            
            // Load macaroon
            String macaroonHex = plugin.getConfig().getString("lnd.macaroon_hex", "");
            String macaroonPath = plugin.getConfig().getString("lnd.macaroon_path", "");
            
            if (!macaroonHex.isEmpty()) {
                lndMacaroonHex = macaroonHex;
                plugin.getDebugLogger().debug("  Macaroon source: hex config");
                plugin.getDebugLogger().debug("  Macaroon length: " + lndMacaroonHex.length() + " chars");
                plugin.getDebugLogger().debug("  Macaroon preview: " + (lndMacaroonHex.length() > 20 ? lndMacaroonHex.substring(0, 20) + "..." : "***"));
            } else if (!macaroonPath.isEmpty()) {
                plugin.getDebugLogger().debug("  Attempting to load macaroon from file: " + macaroonPath);
                try {
                    byte[] macaroonBytes = Files.readAllBytes(Paths.get(macaroonPath));
                    lndMacaroonHex = bytesToHex(macaroonBytes);
                    plugin.getDebugLogger().debug("  Successfully loaded macaroon from file");
                    plugin.getDebugLogger().debug("  Macaroon length: " + lndMacaroonHex.length() + " chars");
                } catch (IOException e) {
                    plugin.getLogger().severe("Failed to load macaroon from " + macaroonPath);
                    plugin.getDebugLogger().error("Macaroon loading error: " + e.getMessage(), e);
                }
            } else {
                plugin.getLogger().warning("No macaroon configured! Neither macaroon_hex nor macaroon_path is set.");
            }
        }
        
        plugin.getDebugLogger().debug("Configuration loaded successfully");
    }

    private HttpClient buildHttpClient() {
        plugin.getDebugLogger().debug("Building HTTP client...");
        
        boolean useTor = plugin.getConfig().getBoolean(
            backend == BackendType.LNBITS ? "lnbits.use_tor_proxy" : "lnd.use_tor_proxy", 
            false
        );
        
        plugin.getDebugLogger().debug("  Tor proxy enabled: " + useTor);

        int timeout = useTor ? 30 : 10;
        plugin.getDebugLogger().debug("  Connection timeout: " + timeout + " seconds");
        
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeout));

        // Add proxy if enabled
        if (useTor) {
            String proxyType = plugin.getConfig().getString(
                backend == BackendType.LNBITS ? "lnbits.tor_proxy_type" : "lnd.tor_proxy_type",
                "socks5"
            ).toLowerCase();
            
            String torHost = plugin.getConfig().getString(
                backend == BackendType.LNBITS ? "lnbits.tor_proxy_host" : "lnd.tor_proxy_host", 
                "127.0.0.1"
            );
            int torPort = plugin.getConfig().getInt(
                backend == BackendType.LNBITS ? "lnbits.tor_proxy_port" : "lnd.tor_proxy_port", 
                9050
            );
            
            plugin.getDebugLogger().debug("  Proxy type: " + proxyType.toUpperCase());
            plugin.getDebugLogger().debug("  Configuring proxy: " + torHost + ":" + torPort);
            
            try {
                // Java's HttpClient supports SOCKS proxies through ProxySelector
                // HTTP proxies would need different handling (not typically used with Tor)
                if (proxyType.equals("socks5") || proxyType.equals("socks")) {
                    builder.proxy(ProxySelector.of(new InetSocketAddress(torHost, torPort)));
                    plugin.getDebugLogger().debug("  SOCKS5 proxy configured successfully");
                } else if (proxyType.equals("http") || proxyType.equals("https")) {
                    // For HTTP proxy, we still use ProxySelector but note the difference
                    builder.proxy(ProxySelector.of(new InetSocketAddress(torHost, torPort)));
                    plugin.getDebugLogger().debug("  HTTP proxy configured successfully");
                    plugin.getDebugLogger().debug("  Note: Java HttpClient will attempt to connect using HTTP CONNECT");
                } else {
                    plugin.getLogger().warning("Unknown proxy type: " + proxyType + ", defaulting to SOCKS5");
                    builder.proxy(ProxySelector.of(new InetSocketAddress(torHost, torPort)));
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to configure proxy");
                plugin.getDebugLogger().error("Proxy configuration error: " + e.getMessage(), e);
            }
        }

        // Disable SSL verification for LND (self-signed certs)
        if (backend == BackendType.LND && lndUseHttps) {
            plugin.getDebugLogger().debug("  Configuring SSL context for self-signed certificates...");
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                builder.sslContext(sslContext);
                plugin.getDebugLogger().debug("  SSL context configured (trusting all certificates)");
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                plugin.getLogger().warning("Failed to setup SSL context");
                plugin.getDebugLogger().error("SSL context error: " + e.getMessage(), e);
            }
        }

        plugin.getDebugLogger().debug("HTTP client built successfully");
        return builder.build();
    }

    // Trust all certificates (for LND self-signed certs)
    private static final TrustManager[] trustAllCerts = new TrustManager[]{
        new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] certs, String authType) { }
            public void checkServerTrusted(X509Certificate[] certs, String authType) { }
        }
    };

    private String baseUrl() {
        if (backend == BackendType.LNBITS) {
            String protocol = lnbitsUseHttps ? "https://" : "http://";
            return protocol + lnbitsHost + "/api/v1";
        } else {
            String protocol = lndUseHttps ? "https://" : "http://";
            return protocol + lndHost + ":" + lndPort + "/v1";
        }
    }

    // Response wrapper
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

    /** Get wallet/node info (works for both backends) */
    public CompletableFuture<LNResponse<JsonObject>> getWalletInfoAsync() {
        return CompletableFuture.supplyAsync(() -> {
            String url = backend == BackendType.LNBITS ? 
                baseUrl() + "/wallet" : 
                baseUrl() + "/getinfo";
            
            plugin.getDebugLogger().debug("=== getWalletInfo Request ===");
            plugin.getDebugLogger().debug("URL: " + url);
            plugin.getDebugLogger().debug("Backend: " + backend);
                
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET();

            // Add appropriate auth header
            if (backend == BackendType.LNBITS) {
                requestBuilder.header("X-Api-Key", lnbitsApiKey);
                plugin.getDebugLogger().debug("Auth: X-Api-Key (length: " + lnbitsApiKey.length() + ")");
            } else {
                requestBuilder.header("Grpc-Metadata-macaroon", lndMacaroonHex);
                plugin.getDebugLogger().debug("Auth: Grpc-Metadata-macaroon (length: " + lndMacaroonHex.length() + ")");
            }

            plugin.getDebugLogger().debug("Sending request...");
            long startTime = System.currentTimeMillis();
            
            try {
                HttpResponse<String> response = client.send(
                    requestBuilder.build(), 
                    HttpResponse.BodyHandlers.ofString()
                );
                
                long duration = System.currentTimeMillis() - startTime;
                plugin.getDebugLogger().debug("Response received in " + duration + "ms");
                plugin.getDebugLogger().debug("Status code: " + response.statusCode());
                
                if (response.statusCode() == 200) {
                    plugin.getDebugLogger().debug("Response body: " + response.body());
                    JsonObject data = gson.fromJson(response.body(), JsonObject.class);
                    plugin.getDebugLogger().debug("Successfully parsed JSON response");
                    plugin.getDebugLogger().info("Wallet info retrieved successfully");
                    return LNResponse.success(data, response.statusCode());
                } else {
                    plugin.getLogger().warning("Wallet request failed with status: " + response.statusCode());
                    plugin.getDebugLogger().debug("Response body: " + response.body());
                    return LNResponse.failure("HTTP " + response.statusCode() + ": " + response.body(), response.statusCode());
                }
            } catch (IOException e) {
                long duration = System.currentTimeMillis() - startTime;
                plugin.getLogger().severe("Network error after " + duration + "ms");
                plugin.getDebugLogger().error("Network error: " + e.getMessage(), e);
                return LNResponse.failure("Network error: " + e.getMessage(), -1);
            } catch (InterruptedException e) {
                long duration = System.currentTimeMillis() - startTime;
                plugin.getLogger().warning("Request interrupted after " + duration + "ms");
                plugin.getDebugLogger().error("Request interrupted: " + e.getMessage(), e);
                Thread.currentThread().interrupt();
                return LNResponse.failure("Request interrupted", -1);
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                plugin.getLogger().severe("Unexpected error after " + duration + "ms");
                plugin.getDebugLogger().error("Unexpected error: " + e.getMessage(), e);
                return LNResponse.failure("Unexpected error: " + e.getMessage(), -1);
            }
        });
    }

    public LNResponse<JsonObject> getWalletInfo() {
        return getWalletInfoAsync().join();
    }

    /** Get balance (unified for both backends) */
    public CompletableFuture<LNResponse<Long>> getBalanceAsync() {
        return CompletableFuture.supplyAsync(() -> {
            plugin.getDebugLogger().debug("=== getBalance Request ===");
            
            if (backend == BackendType.LNBITS) {
                plugin.getDebugLogger().debug("Using LNbits wallet endpoint for balance");
                LNResponse<JsonObject> walletInfo = getWalletInfo();
                if (walletInfo.success) {
                    long balanceMsat = walletInfo.data.get("balance").getAsLong();
                    long balance = balanceMsat / 1000; // msat to sat
                    plugin.getDebugLogger().debug("Balance: " + balanceMsat + " msat = " + balance + " sats");
                    return LNResponse.success(balance, walletInfo.statusCode);
                }
                plugin.getDebugLogger().debug("Failed to get balance: " + walletInfo.error);
                return LNResponse.failure(walletInfo.error, walletInfo.statusCode);
            } else {
                // LND balance endpoint
                String url = baseUrl() + "/balance/channels";
                plugin.getDebugLogger().debug("URL: " + url);
                
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Grpc-Metadata-macaroon", lndMacaroonHex)
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                plugin.getDebugLogger().debug("Sending balance request...");
                long startTime = System.currentTimeMillis();
                
                try {
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    long duration = System.currentTimeMillis() - startTime;
                    plugin.getDebugLogger().debug("Response received in " + duration + "ms");
                    plugin.getDebugLogger().debug("Status code: " + response.statusCode());
                    
                    if (response.statusCode() == 200) {
                        plugin.getDebugLogger().debug("Response body: " + response.body());
                        JsonObject data = gson.fromJson(response.body(), JsonObject.class);
                        long balance = data.get("balance").getAsLong();
                        plugin.getDebugLogger().debug("Channel balance: " + balance + " sats");
                        return LNResponse.success(balance, response.statusCode());
                    }
                    plugin.getDebugLogger().debug("Failed response body: " + response.body());
                    return LNResponse.failure("HTTP " + response.statusCode(), response.statusCode());
                } catch (IOException e) {
                    plugin.getLogger().severe("Network error getting balance");
                    plugin.getDebugLogger().error("Network error: " + e.getMessage(), e);
                    return LNResponse.failure("Network error: " + e.getMessage(), -1);
                } catch (InterruptedException e) {
                    plugin.getLogger().warning("Balance request interrupted");
                    plugin.getDebugLogger().error("Request interrupted: " + e.getMessage(), e);
                    Thread.currentThread().interrupt();
                    return LNResponse.failure("Request interrupted", -1);
                }
            }
        });
    }

    /** Create invoice (works for both backends) */
    public CompletableFuture<LNResponse<Invoice>> createInvoiceAsync(long amountSats, String memo) {
        return CompletableFuture.supplyAsync(() -> {
            plugin.getDebugLogger().debug("=== createInvoice Request ===");
            plugin.getDebugLogger().debug("Amount: " + amountSats + " sats");
            plugin.getDebugLogger().debug("Memo: " + memo);
            
            String url = baseUrl() + (backend == BackendType.LNBITS ? "/payments" : "/invoices");
            plugin.getDebugLogger().debug("URL: " + url);
            
            String body;
            if (backend == BackendType.LNBITS) {
                body = String.format(
                    "{\"unit\":\"sat\",\"amount\":%d,\"memo\":\"%s\",\"out\":false}",
                    amountSats,
                    memo.replace("\"", "\\\"")
                );
            } else {
                body = String.format(
                    "{\"value\":\"%d\",\"memo\":\"%s\"}",
                    amountSats,
                    memo.replace("\"", "\\\"")
                );
            }
            plugin.getDebugLogger().debug("Request body: " + body);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body));

            if (backend == BackendType.LNBITS) {
                requestBuilder.header("X-Api-Key", lnbitsApiKey);
            } else {
                requestBuilder.header("Grpc-Metadata-macaroon", lndMacaroonHex);
            }

            plugin.getDebugLogger().debug("Sending invoice creation request...");
            long startTime = System.currentTimeMillis();
            
            try {
                HttpResponse<String> response = client.send(
                    requestBuilder.build(), 
                    HttpResponse.BodyHandlers.ofString()
                );
                
                long duration = System.currentTimeMillis() - startTime;
                plugin.getDebugLogger().debug("Response received in " + duration + "ms");
                plugin.getDebugLogger().debug("Status code: " + response.statusCode());
                
                if (response.statusCode() == 201 || response.statusCode() == 200) {
                    plugin.getDebugLogger().debug("Response body: " + response.body());
                    JsonObject json = gson.fromJson(response.body(), JsonObject.class);
                    
                    String paymentHash, bolt11;
                    if (backend == BackendType.LNBITS) {
                        paymentHash = json.get("payment_hash").getAsString();
                        bolt11 = json.get("payment_request").getAsString();
                    } else {
                        paymentHash = json.get("r_hash").getAsString();
                        bolt11 = json.get("payment_request").getAsString();
                    }
                    
                    plugin.getDebugLogger().debug("Invoice created successfully");
                    plugin.getDebugLogger().debug("Payment hash: " + paymentHash);
                    plugin.getDebugLogger().debug("BOLT11: " + bolt11.substring(0, Math.min(50, bolt11.length())) + "...");
                    
                    Invoice invoice = new Invoice(paymentHash, bolt11, amountSats);
                    return LNResponse.success(invoice, response.statusCode());
                } else {
                    plugin.getLogger().warning("Failed to create invoice: " + response.statusCode());
                    plugin.getDebugLogger().debug("Error response body: " + response.body());
                    return LNResponse.failure("Failed to create invoice: " + response.body(), response.statusCode());
                }
            } catch (IOException e) {
                plugin.getLogger().severe("Network error creating invoice");
                plugin.getDebugLogger().error("Network error: " + e.getMessage(), e);
                return LNResponse.failure("Network error: " + e.getMessage(), -1);
            } catch (InterruptedException e) {
                plugin.getLogger().warning("Invoice creation interrupted");
                plugin.getDebugLogger().error("Request interrupted: " + e.getMessage(), e);
                Thread.currentThread().interrupt();
                return LNResponse.failure("Request interrupted", -1);
            } catch (Exception e) {
                plugin.getLogger().severe("Unexpected error creating invoice");
                plugin.getDebugLogger().error("Unexpected error: " + e.getMessage(), e);
                return LNResponse.failure("Unexpected error: " + e.getMessage(), -1);
            }
        });
    }

    public LNResponse<Invoice> createInvoice(long amountSats, String memo) {
        return createInvoiceAsync(amountSats, memo).join();
    }

    /** Check if invoice is paid (works for both backends) */
    public CompletableFuture<LNResponse<Boolean>> checkInvoiceAsync(String paymentHash) {
        return CompletableFuture.supplyAsync(() -> {
            plugin.getDebugLogger().debug("=== checkInvoice Request ===");
            plugin.getDebugLogger().debug("Payment hash: " + paymentHash);
            
            String url = baseUrl() + (backend == BackendType.LNBITS ? 
                "/payments/" + paymentHash : 
                "/invoice/" + paymentHash);
            plugin.getDebugLogger().debug("URL: " + url);
                
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET();

            if (backend == BackendType.LNBITS) {
                requestBuilder.header("X-Api-Key", lnbitsApiKey);
            } else {
                requestBuilder.header("Grpc-Metadata-macaroon", lndMacaroonHex);
            }

            plugin.getDebugLogger().debug("Checking invoice status...");
            long startTime = System.currentTimeMillis();
            
            try {
                HttpResponse<String> response = client.send(
                    requestBuilder.build(), 
                    HttpResponse.BodyHandlers.ofString()
                );
                
                long duration = System.currentTimeMillis() - startTime;
                plugin.getDebugLogger().debug("Response received in " + duration + "ms");
                plugin.getDebugLogger().debug("Status code: " + response.statusCode());
                
                if (response.statusCode() == 200) {
                    plugin.getDebugLogger().debug("Response body: " + response.body());
                    JsonObject json = gson.fromJson(response.body(), JsonObject.class);
                    boolean paid = backend == BackendType.LNBITS ? 
                        json.get("paid").getAsBoolean() : 
                        json.get("settled").getAsBoolean();
                    plugin.getDebugLogger().debug("Invoice paid status: " + paid);
                    return LNResponse.success(paid, response.statusCode());
                } else {
                    plugin.getDebugLogger().debug("Failed response body: " + response.body());
                    return LNResponse.failure("Failed to check invoice", response.statusCode());
                }
            } catch (IOException e) {
                plugin.getLogger().severe("Network error checking invoice");
                plugin.getDebugLogger().error("Network error: " + e.getMessage(), e);
                return LNResponse.failure("Network error: " + e.getMessage(), -1);
            } catch (InterruptedException e) {
                plugin.getLogger().warning("Invoice check interrupted");
                plugin.getDebugLogger().error("Request interrupted: " + e.getMessage(), e);
                Thread.currentThread().interrupt();
                return LNResponse.failure("Request interrupted", -1);
            }
        });
    }

    public LNResponse<Boolean> checkInvoice(String paymentHash) {
        return checkInvoiceAsync(paymentHash).join();
    }

    /** Pay an invoice (works for both backends) */
    public CompletableFuture<LNResponse<JsonObject>> payInvoiceAsync(String bolt11) {
        return CompletableFuture.supplyAsync(() -> {
            plugin.getDebugLogger().debug("=== payInvoice Request ===");
            plugin.getDebugLogger().debug("BOLT11: " + bolt11.substring(0, Math.min(50, bolt11.length())) + "...");
            
            String url = baseUrl() + (backend == BackendType.LNBITS ? 
                "/payments" : 
                "/channels/transactions");
            plugin.getDebugLogger().debug("URL: " + url);
            
            String body;
            if (backend == BackendType.LNBITS) {
                body = String.format("{\"out\":true,\"bolt11\":\"%s\"}", bolt11);
            } else {
                body = String.format("{\"payment_request\":\"%s\"}", bolt11);
            }
            plugin.getDebugLogger().debug("Request body: " + body);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body));

            if (backend == BackendType.LNBITS) {
                requestBuilder.header("X-Api-Key", lnbitsApiKey);
            } else {
                requestBuilder.header("Grpc-Metadata-macaroon", lndMacaroonHex);
            }

            plugin.getDebugLogger().debug("Sending payment request...");
            long startTime = System.currentTimeMillis();
            
            try {
                HttpResponse<String> response = client.send(
                    requestBuilder.build(), 
                    HttpResponse.BodyHandlers.ofString()
                );
                
                long duration = System.currentTimeMillis() - startTime;
                plugin.getDebugLogger().debug("Response received in " + duration + "ms");
                plugin.getDebugLogger().debug("Status code: " + response.statusCode());
                
                if (response.statusCode() == 201 || response.statusCode() == 200) {
                    plugin.getDebugLogger().debug("Response body: " + response.body());
                    JsonObject data = gson.fromJson(response.body(), JsonObject.class);
                    plugin.getDebugLogger().info("Payment sent successfully");
                    return LNResponse.success(data, response.statusCode());
                } else {
                    plugin.getLogger().warning("Payment failed: " + response.statusCode());
                    plugin.getDebugLogger().debug("Error response body: " + response.body());
                    return LNResponse.failure("Payment failed: " + response.body(), response.statusCode());
                }
            } catch (IOException e) {
                plugin.getLogger().severe("Network error sending payment");
                plugin.getDebugLogger().error("Network error: " + e.getMessage(), e);
                return LNResponse.failure("Network error: " + e.getMessage(), -1);
            } catch (InterruptedException e) {
                plugin.getLogger().warning("Payment request interrupted");
                plugin.getDebugLogger().error("Request interrupted: " + e.getMessage(), e);
                Thread.currentThread().interrupt();
                return LNResponse.failure("Request interrupted", -1);
            }
        });
    }

    public void shutdown() {
        plugin.getDebugLogger().debug("Shutting down LNService...");
    }

    public BackendType getBackend() {
        return backend;
    }

    // Helper: bytes to hex
    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    // Invoice data class
    public static class Invoice {
        public final String paymentHash;
        public final String bolt11;
        public final long amountSats;

        public Invoice(String paymentHash, String bolt11, long amountSats) {
            this.paymentHash = paymentHash;
            this.bolt11 = bolt11;
            this.amountSats = amountSats;
        }
    }
}