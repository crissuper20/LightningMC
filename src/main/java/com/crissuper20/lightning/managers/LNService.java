package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
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
    private boolean lnbitsSkipTlsVerify;
    
    // LND config
    private String lndHost;
    private int lndPort;
    private String lndMacaroonHex;
    private boolean lndUseHttps;
    private boolean lndSkipTlsVerify;

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
            lnbitsSkipTlsVerify = plugin.getConfig().getBoolean("lnbits.skip_tls_verify", false);
            
            plugin.getDebugLogger().debug("  Host: " + lnbitsHost);
            plugin.getDebugLogger().debug("  Use HTTPS: " + lnbitsUseHttps);
            plugin.getDebugLogger().debug("  Skip TLS verify: " + lnbitsSkipTlsVerify);
            plugin.getDebugLogger().debug("  API Key length: " + lnbitsApiKey.length() + " chars");
            plugin.getDebugLogger().debug("  API Key preview: " + (lnbitsApiKey.length() > 10 ? lnbitsApiKey.substring(0, 10) + "..." : "***"));
        } else {
            plugin.getDebugLogger().debug("Loading LND configuration...");
            lndHost = plugin.getConfig().getString("lnd.host", "localhost");
            lndPort = plugin.getConfig().getInt("lnd.port", 8080);
            lndUseHttps = plugin.getConfig().getBoolean("lnd.use_https", true);
            lndSkipTlsVerify = plugin.getConfig().getBoolean("lnd.skip_tls_verify", false);
            
            plugin.getDebugLogger().debug("  Host: " + lndHost);
            plugin.getDebugLogger().debug("  Port: " + lndPort);
            plugin.getDebugLogger().debug("  Use HTTPS: " + lndUseHttps);
            plugin.getDebugLogger().debug("  Skip TLS verify: " + lndSkipTlsVerify);
            
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
        
        // Validate .onion configuration after we've loaded values
        String host = backend == BackendType.LNBITS ? lnbitsHost : lndHost;
        boolean useHttps = backend == BackendType.LNBITS ? lnbitsUseHttps : lndUseHttps;
        boolean skipTls = backend == BackendType.LNBITS ? lnbitsSkipTlsVerify : lndSkipTlsVerify;
        boolean useTorProxy = plugin.getConfig().getBoolean(
            backend == BackendType.LNBITS ? "lnbits.use_tor_proxy" : "lnd.use_tor_proxy",
            false
        );

        if (host != null && host.endsWith(".onion")) {
            plugin.getDebugLogger().debug("Detected .onion address: " + host);
            
            if (!useTorProxy) {
                throw new IllegalStateException(
                    "Configuration error: .onion address '" + host + "' requires Tor proxy. " +
                    "Set " + (backend == BackendType.LNBITS ? "lnbits" : "lnd") + ".use_tor_proxy: true"
                );
            }
            
            if (useHttps && !skipTls) {
                throw new IllegalStateException(
                    "Configuration error: .onion address '" + host + "' uses HTTPS with a self-signed cert. " +
                    "Either set " + (backend == BackendType.LNBITS ? "lnbits" : "lnd") + ".use_https: false OR " +
                    "set " + (backend == BackendType.LNBITS ? "lnbits" : "lnd") + ".skip_tls_verify: true to accept the self-signed certificate."
                );
            }
            
            plugin.getDebugLogger().debug("Tor configuration validated for .onion address");
        }

        plugin.getDebugLogger().debug("Configuration loaded successfully");
    }

    private HttpClient buildHttpClient() {
        plugin.getDebugLogger().debug("Building HTTP client...");

        // Force use of system native libraries for TLS and proxy handling
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "true");
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        System.setProperty("jdk.httpclient.enableAllMethodRetry", "true");
        
        boolean useTor = plugin.getConfig().getBoolean(
            backend == BackendType.LNBITS ? "lnbits.use_tor_proxy" : "lnd.use_tor_proxy",
            false
        );

        plugin.getDebugLogger().debug("  Tor proxy enabled: " + useTor);

        // Always use native transport
        HttpClient.Builder builder = HttpClient.newBuilder();

        if (useTor) {
            String torHost = plugin.getConfig().getString(
                backend == BackendType.LNBITS ? "lnbits.tor_proxy_host" : "lnd.tor_proxy_host",
                "127.0.0.1"
            );
            int torPort = plugin.getConfig().getInt(
                backend == BackendType.LNBITS ? "lnbits.tor_proxy_port" : "lnd.tor_proxy_port",
                9050
            );

            plugin.getDebugLogger().debug("  Configuring SOCKS5 proxy: " + torHost + ":" + torPort);

            // System properties for proxy
            System.setProperty("socksProxyHost", torHost);
            System.setProperty("socksProxyPort", String.valueOf(torPort));
            System.setProperty("java.net.useSocksProxy", "true");
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");

            builder.proxy(ProxySelector.of(new InetSocketAddress(torHost, torPort)));
        }

        // SSL setup
        try {
            SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
            TrustManager[] trustManagers = new TrustManager[] {
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
            };
            sslContext.init(null, trustManagers, new SecureRandom());

            builder.sslContext(sslContext)
                   .sslParameters(sslContext.getDefaultSSLParameters())
                   .connectTimeout(Duration.ofSeconds(useTor ? 30 : 10));

            plugin.getDebugLogger().debug("  SSL context configured with native TLS");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to configure SSL context: " + e.getMessage());
            plugin.getDebugLogger().error("SSL setup error", e);
        }

        // Configure SSL context and trust settings
        boolean wantSkipTls = (backend == BackendType.LND && lndSkipTlsVerify) || 
                             (backend == BackendType.LNBITS && lnbitsSkipTlsVerify);
                             
        if (wantSkipTls) {
            plugin.getDebugLogger().debug("  Configuring SSL context to trust all certificates...");
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                
                // Configure the builder with our all-trusting SSL context
                builder.sslContext(sslContext)
                       .sslParameters(sslContext.getDefaultSSLParameters());
                
                // Also set default hostname verifier to trust all (belt and suspenders)
                HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
                
                plugin.getDebugLogger().debug("  SSL context configured (trusting all certificates)");
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                plugin.getLogger().warning("Failed to setup SSL context");
                plugin.getDebugLogger().error("SSL context error: " + e.getMessage(), e);
            }
        }

        // Add proxy if enabled (critical for .onion addresses)
        if (useTor) {
            String torHost = plugin.getConfig().getString(
                backend == BackendType.LNBITS ? "lnbits.tor_proxy_host" : "lnd.tor_proxy_host", 
                "127.0.0.1"
            );
            int torPort = plugin.getConfig().getInt(
                backend == BackendType.LNBITS ? "lnbits.tor_proxy_port" : "lnd.tor_proxy_port", 
                9050
            );
            
            plugin.getDebugLogger().debug("  Configuring SOCKS5 proxy: " + torHost + ":" + torPort);
            
            try {
                // Use a proxy selector that explicitly handles HTTPS-over-SOCKS
                builder.proxy(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        plugin.getDebugLogger().debug("  Selecting proxy for URI: " + uri);
                        return List.of(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(torHost, torPort)));
                    }
                    
                    @Override
                    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                        plugin.getDebugLogger().error("Proxy connection failed for " + uri, ioe);
                    }
                });
                
                plugin.getDebugLogger().debug("  Custom SOCKS5 proxy selector configured");
                
                if ((backend == BackendType.LND && lndUseHttps) || 
                    (backend == BackendType.LNBITS && lnbitsUseHttps)) {
                    plugin.getDebugLogger().debug("  Using HTTPS over Tor (Start9/Embassy setup)");
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to configure proxy");
                plugin.getDebugLogger().error("Proxy configuration error: " + e.getMessage(), e);
            }
        }

        // Optionally disable SSL verification for backends when configured to skip TLS verification
        boolean needSkipTls = (backend == BackendType.LND && lndUseHttps && lndSkipTlsVerify)
                || (backend == BackendType.LNBITS && lnbitsUseHttps && lnbitsSkipTlsVerify);

        if (needSkipTls) {
            plugin.getDebugLogger().debug("  Configuring SSL context to skip TLS verification (trust all certs)...");
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
                
            // Parse URL components for .onion special handling
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            String path = uri.getPath();
            
            plugin.getDebugLogger().debug("Request components:");
            plugin.getDebugLogger().debug("  Scheme: " + scheme);
            plugin.getDebugLogger().debug("  Host: " + host);
            plugin.getDebugLogger().debug("  Port: " + port);
            plugin.getDebugLogger().debug("  Path: " + path);
            
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(30))  // Longer timeout for Tor
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
                String headerExample = backend == BackendType.LNBITS ?
                        "X-Api-Key: " + maskSecret(lnbitsApiKey) :
                        "Grpc-Metadata-macaroon: " + maskSecret(lndMacaroonHex);
                logNetworkError(e, url + " (after " + duration + "ms)", headerExample);
                return LNResponse.failure("Network error: " + e.getMessage(), -1);
            } catch (InterruptedException e) {
                long duration = System.currentTimeMillis() - startTime;
                String headerExample = backend == BackendType.LNBITS ?
                        "X-Api-Key: " + maskSecret(lnbitsApiKey) :
                        "Grpc-Metadata-macaroon: " + maskSecret(lndMacaroonHex);
                logNetworkError(e, url + " (interrupted after " + duration + "ms)", headerExample);
                Thread.currentThread().interrupt();
                return LNResponse.failure("Request interrupted", -1);
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                String headerExample = backend == BackendType.LNBITS ?
                        "X-Api-Key: " + maskSecret(lnbitsApiKey) :
                        "Grpc-Metadata-macaroon: " + maskSecret(lndMacaroonHex);
                logNetworkError(e, url + " (unexpected error after " + duration + "ms)", headerExample);
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
                    String headerExample = "Grpc-Metadata-macaroon: " + maskSecret(lndMacaroonHex);
                    logNetworkError(e, url, headerExample);
                    return LNResponse.failure("Network error: " + e.getMessage(), -1);
                } catch (InterruptedException e) {
                    String headerExample = "Grpc-Metadata-macaroon: " + maskSecret(lndMacaroonHex);
                    logNetworkError(e, url + " (interrupted)", headerExample);
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
                String headerExample = backend == BackendType.LNBITS ?
                        "X-Api-Key: " + maskSecret(lnbitsApiKey) :
                        "Grpc-Metadata-macaroon: " + maskSecret(lndMacaroonHex);
                logNetworkError(e, url, headerExample);
                return LNResponse.failure("Network error: " + e.getMessage(), -1);
            } catch (InterruptedException e) {
                String headerExample = backend == BackendType.LNBITS ?
                        "X-Api-Key: " + maskSecret(lnbitsApiKey) :
                        "Grpc-Metadata-macaroon: " + maskSecret(lndMacaroonHex);
                logNetworkError(e, url + " (interrupted)", headerExample);
                Thread.currentThread().interrupt();
                return LNResponse.failure("Request interrupted", -1);
            } catch (Exception e) {
                String headerExample = backend == BackendType.LNBITS ?
                        "X-Api-Key: " + maskSecret(lnbitsApiKey) :
                        "Grpc-Metadata-macaroon: " + maskSecret(lndMacaroonHex);
                logNetworkError(e, url + " (unexpected)", headerExample);
                return LNResponse.failure("Unexpected error: " + e.getMessage(), -1);
            }
        });
    }

    public LNResponse<Invoice> createInvoice(long amountSats, String memo) {
        return createInvoiceAsync(amountSats, memo).join();
    }

    public LNResponse<Long> getWalletBalance(String walletId) {
        if (backend != BackendType.LNBITS) {
            // For LND, we just use the main wallet balance
            return getBalanceAsync().join();
        }

        String url = baseUrl() + "/wallet";
        plugin.getDebugLogger().debug("Fetching balance for wallet: " + walletId);
            
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("X-Api-Key", walletId) // Use wallet-specific API key
            .GET();

        try {
            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonObject data = gson.fromJson(response.body(), JsonObject.class);
                long balanceMsat = data.get("balance").getAsLong();
                long balance = balanceMsat / 1000; // Convert msat to sat
                return LNResponse.success(balance, response.statusCode());
            }
            return LNResponse.failure("Failed to fetch balance: HTTP " + response.statusCode(), response.statusCode());
        } catch (Exception e) {
            plugin.getDebugLogger().error("Error fetching balance for wallet " + walletId, e);
            return LNResponse.failure("Failed to fetch balance: " + e.getMessage(), -1);
        }
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
                String headerExample = backend == BackendType.LNBITS ?
                        "X-Api-Key: " + maskSecret(lnbitsApiKey) :
                        "Grpc-Metadata-macaroon: " + maskSecret(lndMacaroonHex);
                logNetworkError(e, url, headerExample);
                return LNResponse.failure("Network error: " + e.getMessage(), -1);
            } catch (InterruptedException e) {
                String headerExample = backend == BackendType.LNBITS ?
                        "X-Api-Key: " + maskSecret(lnbitsApiKey) :
                        "Grpc-Metadata-macaroon: " + maskSecret(lndMacaroonHex);
                logNetworkError(e, url + " (interrupted)", headerExample);
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
                String headerExample = backend == BackendType.LNBITS ?
                        "X-Api-Key: " + maskSecret(lnbitsApiKey) :
                        "Grpc-Metadata-macaroon: " + maskSecret(lndMacaroonHex);
                logNetworkError(e, url, headerExample);
                return LNResponse.failure("Network error: " + e.getMessage(), -1);
            } catch (InterruptedException e) {
                String headerExample = backend == BackendType.LNBITS ?
                        "X-Api-Key: " + maskSecret(lnbitsApiKey) :
                        "Grpc-Metadata-macaroon: " + maskSecret(lndMacaroonHex);
                logNetworkError(e, url + " (interrupted)", headerExample);
                Thread.currentThread().interrupt();
                return LNResponse.failure("Request interrupted", -1);
            }
        });
    }

    public void shutdown() {
        plugin.getDebugLogger().debug("Shutting down LNService...");
    }

    /**
     * Improved network error logging helper — logs exception class, message, stacktrace,
     * and prints a small curl example (with masked auth) to aid debugging from the server.
     */
    private void logNetworkError(Exception e, String url, String authHeaderExample) {
        String exClass = (e == null) ? "UnknownException" : e.getClass().getSimpleName();
        String exMsg = (e == null || e.getMessage() == null) ? "" : e.getMessage();

        plugin.getLogger().severe("Network error contacting " + url + " — " + exClass + (exMsg.isEmpty() ? "" : ": " + exMsg));
        plugin.getDebugLogger().error("Network error contacting " + url + ": " + exMsg, e);

        // Provide a small curl example to help administrators reproduce the request.
        if (authHeaderExample != null && !authHeaderExample.isEmpty()) {
            plugin.getDebugLogger().debug("Try this curl command (headers may need adjustment):");
            plugin.getDebugLogger().debug("curl -v \"" + url + "\" -H \"" + authHeaderExample + "\"");
        } else {
            plugin.getDebugLogger().debug("Try this curl command:");
            plugin.getDebugLogger().debug("curl -v \"" + url + "\"");
        }
    }

    // Mask secret values for safe logging (show only a short prefix)
    private static String maskSecret(String s) {
        if (s == null || s.isEmpty()) return "(empty)";
        if (s.length() <= 8) return "****";
        return s.substring(0, 6) + "...";
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