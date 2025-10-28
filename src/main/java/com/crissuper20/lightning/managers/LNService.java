/**package com.crissuper20.lightning.managers;

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
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public class LNService {
    private final LightningPlugin plugin;
    private final HttpClient client;
    private final Gson gson;

    // dedicated executor for blocking HTTP work (prevents using common ForkJoin pool)
    private final ExecutorService httpExecutor;
    private final AtomicInteger httpThreadCounter = new AtomicInteger(0);

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

    // Health monitoring fields
    private volatile HealthStatus healthStatus = HealthStatus.UNKNOWN;
    private volatile long lastSuccessTime = 0;
    private volatile long lastCheckTime = 0;
    private volatile int consecutiveFailures = 0;
    private volatile String lastError = null;
    private final ScheduledExecutorService healthChecker;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final long HEALTH_CHECK_INTERVAL_MS = 60000; // 1 minute

    public enum BackendType {
        LNBITS, LND
    }

    public enum HealthStatus {
        HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN
    }

    // Add this inner class to store health metrics
    public static class HealthMetrics {
        public final HealthStatus status;
        public final long lastSuccessTime;
        public final long lastCheckTime;
        public final int consecutiveFailures;
        public final String lastError;
        public final long uptimeMs;

        public HealthMetrics(HealthStatus status, long lastSuccessTime, 
                            long lastCheckTime, int consecutiveFailures,
                            String lastError, long uptimeMs) {
            this.status = status;
            this.lastSuccessTime = lastSuccessTime;
            this.lastCheckTime = lastCheckTime;
            this.consecutiveFailures = consecutiveFailures;
            this.lastError = lastError;
            this.uptimeMs = uptimeMs;
        }
    }

    public LNService(LightningPlugin plugin) {
        this.plugin = plugin;
        this.gson = new Gson();

        plugin.getDebugLogger().debug("=== LNService Initialization Started ===");

        try {
            // Step 0: determine backend early (used by validation checks)
            String backendStr = plugin.getConfig().getString("backend", "lnbits").toLowerCase();
            this.backend = backendStr.equals("lnd") ? BackendType.LND : BackendType.LNBITS;
            plugin.getDebugLogger().debug("Backend type determined: " + backend);

            // create a dedicated executor for blocking HTTP operations
            int poolSize = Math.max(2, Runtime.getRuntime().availableProcessors());
            this.httpExecutor = Executors.newFixedThreadPool(poolSize, r -> {
                Thread t = new Thread(r, "LNService-HTTP-" + httpThreadCounter.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

            // NEW: Prevent running against real-money mainnet instances (EULA compliance).
            // Check configuration for any "mainnet" setting and abort startup if found.
            checkMainnetAndAbort();

            // Validate configuration BEFORE creating any resources (fail fast)
            validateConfig();

            // Step 2: Load validated configuration
            plugin.getDebugLogger().debug("Loading configuration...");
            loadConfig();

            // Step 3: Build HTTP client with validated settings
            plugin.getDebugLogger().debug("Building HTTP client...");
            this.client = buildHttpClient();

            // Initialize health checker
            this.healthChecker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "LNService-HealthChecker");
                t.setDaemon(true);
                return t;
            });

            // Start periodic health checks
            healthChecker.scheduleAtFixedRate(
                this::checkHealth,
                HEALTH_CHECK_INTERVAL_MS / 2, // Initial delay
                HEALTH_CHECK_INTERVAL_MS,     // Regular interval
                TimeUnit.MILLISECONDS
            );

            plugin.getDebugLogger().debug("=== LNService Initialization Complete ===");
            plugin.getDebugLogger().info("LNService initialized with backend: " + backend);

        } catch (IllegalStateException e) {
            plugin.getLogger().severe("Configuration error: " + e.getMessage());
            plugin.getDebugLogger().error("Failed to initialize LNService", e);
            // ensure resources cleaned up
            safeShutdownExecutors();
            throw e;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize LNService: " + e.getMessage());
            plugin.getDebugLogger().error("Unexpected error during initialization", e);
            safeShutdownExecutors();
            throw new IllegalStateException("Failed to initialize LNService", e);
        }
    }

    private void validateConfig() {
        plugin.saveDefaultConfig();

        // Validate common settings
        String host = plugin.getConfig().getString(
            backend == BackendType.LNBITS ? "lnbits.host" : "lnd.host"
        );
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalStateException(
                "Missing required config: " + 
                (backend == BackendType.LNBITS ? "lnbits.host" : "lnd.host")
            );
        }

        // Validate backend-specific settings
        if (backend == BackendType.LNBITS) {
            validateLNbitsConfig();
        } else {
            validateLNDConfig();
        }

        // Validate Tor configuration if .onion address is used
        validateTorConfig(host);

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
                "Invalid lnd.port: Must be between 1 and 65535"
            );
        }

        String macaroonHex = plugin.getConfig().getString("lnd.macaroon_hex", "");
        String macaroonPath = plugin.getConfig().getString("lnd.macaroon_path", "");

        if (macaroonHex.isEmpty() && macaroonPath.isEmpty()) {
            throw new IllegalStateException(
   s             "Missing required config: Either lnd.macaroon_hex or lnd.macaroon_path must be set"
            );
        }

        if (!macaroonPath.isEmpty()) {
            if (!Files.exists(Paths.get(macaroonPath))) {
                throw new IllegalStateException(
                    "Invalid lnd.macaroon_path: File does not exist: " + macaroonPath
                );
            }
        }
    }

    private void validateTorConfig(String host) {
        if (host != null && host.endsWith(".onion")) {
            boolean useTorProxy = plugin.getConfig().getBoolean(
                backend == BackendType.LNBITS ? "lnbits.use_tor_proxy" : "lnd.use_tor_proxy",
                false
            );
            
            if (!useTorProxy) {
                throw new IllegalStateException(
                    "Configuration error: .onion address '" + host + "' requires Tor proxy. " +
                    "Set " + (backend == BackendType.LNBITS ? "lnbits" : "lnd") + ".use_tor_proxy: true"
                );
            }

            boolean useHttps = plugin.getConfig().getBoolean(
                backend == BackendType.LNBITS ? "lnbits.use_https" : "lnd.use_https",
                true
            );
            boolean skipTls = plugin.getConfig().getBoolean(
                backend == BackendType.LNBITS ? "lnbits.skip_tls_verify" : "lnd.skip_tls_verify",
                false
            );

            if (useHttps && !skipTls) {
                throw new IllegalStateException(
                    "Configuration error: .onion address '" + host + "' uses HTTPS with a self-signed cert. " +
                    "Either set " + (backend == BackendType.LNBITS ? "lnbits" : "lnd") + ".use_https: false OR " +
                    "set " + (backend == BackendType.LNBITS ? "lnbits" : "lnd") + ".skip_tls_verify: true"
                );
            }

            String torHost = plugin.getConfig().getString(
                backend == BackendType.LNBITS ? "lnbits.tor_proxy_host" : "lnd.tor_proxy_host",
                ""
            );
            int torPort = plugin.getConfig().getInt(
                backend == BackendType.LNBITS ? "lnbits.tor_proxy_port" : "lnd.tor_proxy_port",
                -1
            );

            if (torHost.isEmpty() || torPort <= 0 || torPort > 65535) {
                throw new IllegalStateException(
                    "Invalid Tor proxy configuration for .onion address. Check " +
                    (backend == BackendType.LNBITS ? "lnbits" : "lnd") + 
                    ".tor_proxy_host and tor_proxy_port settings"
                );
            }
        }
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

        boolean useTor = plugin.getConfig().getBoolean(
            backend == BackendType.LNBITS ? "lnbits.use_tor_proxy" : "lnd.use_tor_proxy",
            false
        );

        plugin.getDebugLogger().debug("  Tor proxy enabled: " + useTor);

        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(useTor ? 30 : 10));

        // Configure Tor proxy if enabled (critical for .onion addresses)
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

        // Configure SSL context - only trust all certs when skip_tls_verify is explicitly true
        boolean skipTlsVerify = (backend == BackendType.LND && lndSkipTlsVerify) || 
                                (backend == BackendType.LNBITS && lnbitsSkipTlsVerify);
                             
        if (skipTlsVerify) {
            plugin.getDebugLogger().debug("  Configuring SSL context to trust all certificates...");
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                
                builder.sslContext(sslContext)
                       .sslParameters(sslContext.getDefaultSSLParameters());
                
                // Also set default hostname verifier to trust all
                HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
                
                plugin.getDebugLogger().debug("  SSL context configured (trusting all certificates)");
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                plugin.getLogger().warning("Failed to setup SSL context");
                plugin.getDebugLogger().error("SSL context error: " + e.getMessage(), e);
            }
        } else {
            plugin.getDebugLogger().debug("  Using default SSL context with standard certificate verification");
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

     Get wallet/node info (works for both backends) 
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
        }, httpExecutor);
    }

     Get balance (unified for both backends)  @param walletId 
    public CompletableFuture<LNResponse<Long>> getBalanceAsync(String walletId) {
        plugin.getDebugLogger().debug("=== getBalance Request ===");

        if (backend == BackendType.LNBITS) {
            plugin.getDebugLogger().debug("Using LNbits wallet endpoint for balance");
            // Compose on existing async method to avoid blocking
            return getWalletInfoAsync().thenApply(walletInfo -> {
                if (walletInfo.success) {
                    long balanceMsat = walletInfo.data.get("balance").getAsLong();
                    long balance = balanceMsat / 1000; // msat to sat
                    plugin.getDebugLogger().debug("Balance: " + balanceMsat + " msat = " + balance + " sats");
                    return LNResponse.success(balance, walletInfo.statusCode);
                }
                plugin.getDebugLogger().debug("Failed to get balance: " + walletInfo.error);
                return LNResponse.failure(walletInfo.error, walletInfo.statusCode);
            });
        } else {
            // LND balance endpoint (keeps logic but runs in dedicated executor)
            return CompletableFuture.supplyAsync(() -> {
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
            }, httpExecutor);
        }
    }

    Create invoice (works for both backends) 
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
                        // LNbits returns: payment_hash, payment_request (or bolt11)
                        if (json.has("payment_hash")) {
                            paymentHash = json.get("payment_hash").getAsString();
                        } else {
                            plugin.getDebugLogger().error("LNbits response missing payment_hash. Response: " + response.body());
                            return LNResponse.failure("Invalid response from LNbits: missing payment_hash", response.statusCode());
                        }
                        
                        // Try both field names for the invoice
                        if (json.has("payment_request")) {
                            bolt11 = json.get("payment_request").getAsString();
                        } else if (json.has("bolt11")) {
                            bolt11 = json.get("bolt11").getAsString();
                        } else {
                            plugin.getDebugLogger().error("LNbits response missing payment_request/bolt11. Response: " + response.body());
                            return LNResponse.failure("Invalid response from LNbits: missing payment_request/bolt11", response.statusCode());
                        }
                    } else {
                        // LND returns: r_hash, payment_request
                        if (json.has("r_hash") && json.has("payment_request")) {
                            paymentHash = json.get("r_hash").getAsString();
                            bolt11 = json.get("payment_request").getAsString();
                        } else {
                            plugin.getDebugLogger().error("LND response missing required fields. Response: " + response.body());
                            return LNResponse.failure("Invalid response from LND: missing r_hash or payment_request", response.statusCode());
                        }
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
        }, httpExecutor);
    }

     Check if invoice is paid (works for both backends) 
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
        }, httpExecutor);
    }

    /** Pay an invoice (works for both backends) 
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
        }, httpExecutor);
    }

    public void shutdown() {
        plugin.getDebugLogger().debug("Shutting down LNService...");
        
        try {
            healthChecker.shutdown();
            if (!healthChecker.awaitTermination(5, TimeUnit.SECONDS)) {
                healthChecker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            healthChecker.shutdownNow();
        }

        // shutdown http executor used for blocking HTTP calls
        try {
            httpExecutor.shutdown();
            if (!httpExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                httpExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            httpExecutor.shutdownNow();
        }
    }

    // helper to clean up executors on failed init
    private void safeShutdownExecutors() {
        try {
            if (healthChecker != null) {
                healthChecker.shutdownNow();
            }
        } catch (Throwable ignored) { }
        try {
            if (httpExecutor != null) {
                httpExecutor.shutdownNow();
            }
        } catch (Throwable ignored) { }
    }

    
      Improved network error logging helper — logs exception class, message, stacktrace,
      and prints a small curl example (with masked auth) to aid debugging from the server.
     
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
        private String paymentHash;
        private String bolt11;
        private long amount;
        private String memo;

        // Constructor used in createInvoiceAsync
        public Invoice(String paymentHash, String bolt11, long amount) {
            this.paymentHash = paymentHash;
            this.bolt11 = bolt11;
            this.amount = amount;
        }

        // Getter for bolt11
        public String getBolt11() {
            return bolt11;
        }

        // Getter for other fields
        public String getPaymentHash() {
            return paymentHash;
        }

        public long getAmount() {
            return amount;
        }

        public String getMemo() {
            return memo;
        }
    }

    // Health check methods
    private void checkHealth() {
        plugin.getDebugLogger().debug("Performing health check...");
        lastCheckTime = System.currentTimeMillis();

        try {
            // Use getWalletInfo as health check
            CompletableFuture<LNResponse<JsonObject>> future = getWalletInfoAsync();
            LNResponse<JsonObject> response = future.get(30, TimeUnit.SECONDS);

            if (response.success) {
                handleHealthCheckSuccess();
            } else {
                handleHealthCheckFailure("Backend error: " + response.error);
            }
        } catch (Exception e) {
            handleHealthCheckFailure("Health check failed: " + e.getMessage());
        }
    }

    private void handleHealthCheckSuccess() {
        lastSuccessTime = System.currentTimeMillis();
        consecutiveFailures = 0;
        lastError = null;
        
        healthStatus = HealthStatus.HEALTHY;
        plugin.getDebugLogger().debug("Health check successful");
    }

    private void handleHealthCheckFailure(String error) {
        consecutiveFailures++;
        lastError = error;
        
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            healthStatus = HealthStatus.UNHEALTHY;
            plugin.getLogger().warning("Backend declared unhealthy after " + 
                consecutiveFailures + " consecutive failures");
        } else if (consecutiveFailures > 1) {
            healthStatus = HealthStatus.DEGRADED;
            plugin.getLogger().warning("Backend showing signs of degradation (" + 
                consecutiveFailures + " failures)");
        }
        
        plugin.getDebugLogger().error("Health check failed: " + error);
    }

    public HealthMetrics getHealthMetrics() {
        return new HealthMetrics(
            healthStatus,
            lastSuccessTime,
            lastCheckTime,
            consecutiveFailures,
            lastError,
            lastSuccessTime > 0 ? System.currentTimeMillis() - lastSuccessTime : 0
        );
    }

    // Fix isHealthy() method to consider all health states
    public boolean isHealthy() {
        switch (healthStatus) {
            case HEALTHY:
                return true;
            case DEGRADED:
                // Allow degraded state but log a warning
                plugin.getDebugLogger().debug("Service is in degraded state (" + 
                    consecutiveFailures + " recent failures)");
                return true;
            case UNHEALTHY:
                return false;
            case UNKNOWN:
            default:
                // Treat unknown status as unhealthy after startup grace period
                long uptime = System.currentTimeMillis() - lastCheckTime;
                boolean isStarting = uptime < HEALTH_CHECK_INTERVAL_MS * 2;
                if (!isStarting) {
                    plugin.getDebugLogger().warning("Service health status is UNKNOWN");
                }
                return isStarting;
        }
    }

    private void checkMainnetAndAbort() {
        try {
            String globalNet = plugin.getConfig().getString("network", "").trim();
            String backendNetKey = (backend == BackendType.LNBITS) ? "lnbits.network" : "lnd.network";
            String backendNet = plugin.getConfig().getString(backendNetKey, "").trim();

            boolean globalMain = "mainnet".equalsIgnoreCase(globalNet);
            boolean backendMain = "mainnet".equalsIgnoreCase(backendNet);

            if (globalMain || backendMain) {
                String msg = "Sorry, to avoid your server getting banned, mainnet use is not allowed, use testnet pls";
                plugin.getLogger().severe(msg);
                plugin.getDebugLogger().error(msg);

                // Try to disable plugin gracefully if running under a Bukkit/Spigot-like environment
                try {
                    plugin.getServer().getPluginManager().disablePlugin(plugin);
                } catch (Throwable t) {
                    // If we cannot programmatically disable, log debug and continue to fail fast
                    plugin.getDebugLogger().error("Failed to disable plugin programmatically", t);
                }

                throw new IllegalStateException(msg);
            }
        } catch (Exception e) {
            // Any unexpected error during the check should abort initialization to be safe
            String err = "Failed while checking network type for mainnet safety: " + e.getMessage();
            plugin.getLogger().severe(err);
            plugin.getDebugLogger().error(err, e);
            throw new IllegalStateException(err, e);
        }
    }

}
**/