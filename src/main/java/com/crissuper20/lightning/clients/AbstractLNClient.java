package com.crissuper20.lightning.clients;

import com.crissuper20.lightning.LightningPlugin;
import com.crissuper20.lightning.util.DebugLogger;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class AbstractLNClient implements LNClient {
    
    protected final LightningPlugin plugin;
    protected final DebugLogger logger;
    protected final HttpClient httpClient;
    protected final Gson gson;
    protected final ExecutorService httpExecutor;
    
    // Health monitoring
    private volatile HealthStatus healthStatus = HealthStatus.UNKNOWN;
    private volatile long lastSuccessTime = 0;
    private volatile long lastCheckTime = 0;
    private volatile int consecutiveFailures = 0;
    private volatile String lastError = null;
    private final ScheduledExecutorService healthChecker;
    
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final long HEALTH_CHECK_INTERVAL_MS = 60000; // 1 minute
    private static final AtomicInteger threadCounter = new AtomicInteger(0);
    
    // Trust all certificates (start9 xd)
    private static final TrustManager[] trustAllCerts = new TrustManager[]{
        new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] certs, String authType) { }
            public void checkServerTrusted(X509Certificate[] certs, String authType) { }
        }
    };

    protected AbstractLNClient(LightningPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getDebugLogger();
        this.gson = new Gson();
        
        // Create dedicated executor for HTTP operations
        int poolSize = Math.max(2, Runtime.getRuntime().availableProcessors());
        this.httpExecutor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, getBackendName() + "-HTTP-" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        
        // Build HTTP client with backend-specific config
        this.httpClient = buildHttpClient();
        
        // Initialize health checker
        this.healthChecker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, getBackendName() + "-HealthChecker");
            t.setDaemon(true);
            return t;
        });
        
        // Start periodic health checks
        healthChecker.scheduleAtFixedRate(
            this::checkHealth,
            HEALTH_CHECK_INTERVAL_MS / 2,
            HEALTH_CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
        
        logger.info(getBackendName() + " client initialized successfully");
    }
    private HttpClient buildHttpClient() {
        logger.debug("Building HTTP client for " + getBackendName() + "...");
        
        boolean useTor = shouldUseTor();
        boolean skipTls = shouldSkipTlsVerify();
        
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(useTor ? 30 : 10));
        
        // Configure Tor proxy if needed
        if (useTor) {
            String torHost = getTorProxyHost();
            int torPort = getTorProxyPort();
            
            logger.debug("  Configuring SOCKS5 proxy: " + torHost + ":" + torPort);
            
            builder.proxy(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    return List.of(new Proxy(Proxy.Type.SOCKS, 
                        new InetSocketAddress(torHost, torPort)));
                }
                
                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                    logger.error("Proxy connection failed for " + uri, ioe);
                }
            });
        }
        
        // Configure SSL context if needed
        if (skipTls) {
            logger.debug("  Configuring SSL to trust all certificates...");
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                builder.sslContext(sslContext)
                       .sslParameters(sslContext.getDefaultSSLParameters());
                HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                logger.error("Failed to setup SSL context", e);
            }
        }
        
        logger.debug("HTTP client built successfully");
        return builder.build();
    }

    /**
     * Health check implementation
     */
    private void checkHealth() {
        logger.debug("Performing health check for " + getBackendName() + "...");
        lastCheckTime = System.currentTimeMillis();
        
        try {
            LNResponse<JsonObject> response = getWalletInfoAsync()
                .get(30, TimeUnit.SECONDS);
            
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
        logger.debug("Health check successful");
    }

    private void handleHealthCheckFailure(String error) {
        consecutiveFailures++;
        lastError = error;
        
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            healthStatus = HealthStatus.UNHEALTHY;
            plugin.getLogger().warning(getBackendName() + " declared unhealthy after " + 
                consecutiveFailures + " consecutive failures");
        } else if (consecutiveFailures > 1) {
            healthStatus = HealthStatus.DEGRADED;
            plugin.getLogger().warning(getBackendName() + " showing signs of degradation (" + 
                consecutiveFailures + " failures)");
        }
        
        logger.error("Health check failed: " + error);
    }

    @Override
    public boolean isHealthy() {
        switch (healthStatus) {
            case HEALTHY:
                return true;
            case DEGRADED:
                logger.debug("Service is in degraded state (" + 
                    consecutiveFailures + " recent failures)");
                return true;
            case UNHEALTHY:
                return false;
            case UNKNOWN:
            default:
                long uptime = System.currentTimeMillis() - lastCheckTime;
                boolean isStarting = uptime < HEALTH_CHECK_INTERVAL_MS * 2;
                if (!isStarting) {
                    logger.warning("Service health status is UNKNOWN");
                }
                return isStarting;
        }
    }

    @Override
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

    @Override
    public void shutdown() {
        logger.debug("Shutting down " + getBackendName() + " client...");
        
        try {
            healthChecker.shutdown();
            if (!healthChecker.awaitTermination(5, TimeUnit.SECONDS)) {
                healthChecker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            healthChecker.shutdownNow();
        }
        
        try {
            httpExecutor.shutdown();
            if (!httpExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                httpExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            httpExecutor.shutdownNow();
        }
        
        logger.info(getBackendName() + " client shut down");
    }

    /**
     * Utility: Log network errors with helpful debug info
     */
    protected void logNetworkError(Exception e, String url, String authHeaderExample) {
        String exClass = (e == null) ? "UnknownException" : e.getClass().getSimpleName();
        String exMsg = (e == null || e.getMessage() == null) ? "" : e.getMessage();
        
        plugin.getLogger().severe("Network error contacting " + url + " — " + 
            exClass + (exMsg.isEmpty() ? "" : ": " + exMsg));
        logger.error("Network error: " + exMsg, e);
        
        if (authHeaderExample != null && !authHeaderExample.isEmpty()) {
            logger.debug("Try this curl command:");
            logger.debug("curl -v \"" + url + "\" -H \"" + authHeaderExample + "\"");
        }
    }

    /**
     * Utility: Mask secrets for safe logging
     */
    protected static String maskSecret(String s) {
        if (s == null || s.isEmpty()) return "(empty)";
        if (s.length() <= 8) return "****";
        return s.substring(0, 6) + "...";
    }

    /**
     * Utility: Convert bytes to hex string
     */
    protected static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    // Abstract methods that subclasses must implement
    protected abstract boolean shouldUseTor();
    protected abstract boolean shouldSkipTlsVerify();
    protected abstract String getTorProxyHost();
    protected abstract int getTorProxyPort();
}