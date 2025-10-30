package com.crissuper20.lightning.clients;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

public interface LNClient {
    
    class LNResponse<T> {
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

    class Invoice {
        private final String paymentHash;
        private final String bolt11;
        private final long amount;

        public Invoice(String paymentHash, String bolt11, long amount) {
            this.paymentHash = paymentHash;
            this.bolt11 = bolt11;
            this.amount = amount;
        }

        public String getPaymentHash() { return paymentHash; }
        public String getBolt11() { return bolt11; }
        public long getAmount() { return amount; }
    }

    enum HealthStatus {
        HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN
    }

    class HealthMetrics {
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

    CompletableFuture<LNResponse<JsonObject>> getWalletInfoAsync();
    CompletableFuture<LNResponse<Long>> getBalanceAsync(String walletId);
    CompletableFuture<LNResponse<Invoice>> createInvoiceAsync(long amountSats, String memo);
    CompletableFuture<LNResponse<Boolean>> checkInvoiceAsync(String paymentHash);
    CompletableFuture<LNResponse<JsonObject>> payInvoiceAsync(String bolt11);
    boolean isHealthy();
    HealthMetrics getHealthMetrics();
    void shutdown();
    String getBackendName();
}