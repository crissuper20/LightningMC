package com.crissuper20.lightning.util;

import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Utility for retrying failed operations with exponential backoff
 * 
 * Usage:
 *   CompletableFuture<Result> future = RetryHelper.withRetry(
 *       () -> apiCall(),
 *       3,  // max attempts
 *       1000  // initial delay ms
 *   );
 */
public class RetryHelper {
    
    private static final ScheduledExecutorService scheduler = 
        Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "RetryHelper");
            t.setDaemon(true);
            return t;
        });
    
    /**
     * Retry an operation with exponential backoff
     * 
     * @param operation The operation to retry
     * @param maxAttempts Maximum number of attempts (including first try)
     * @param initialDelayMs Initial delay between retries in milliseconds
     * @param <T> Return type
     * @return CompletableFuture with the result or final exception
     */
    public static <T> CompletableFuture<T> withRetry(
            Supplier<CompletableFuture<T>> operation,
            int maxAttempts,
            long initialDelayMs) {
        
        return withRetry(operation, maxAttempts, initialDelayMs, 2.0);
    }
    
    /**
     * Retry with custom backoff multiplier
     */
    public static <T> CompletableFuture<T> withRetry(
            Supplier<CompletableFuture<T>> operation,
            int maxAttempts,
            long initialDelayMs,
            double backoffMultiplier) {
        
        CompletableFuture<T> result = new CompletableFuture<>();
        retryInternal(operation, maxAttempts, initialDelayMs, backoffMultiplier, 1, result, null);
        return result;
    }
    
    private static <T> void retryInternal(
            Supplier<CompletableFuture<T>> operation,
            int maxAttempts,
            long currentDelayMs,
            double backoffMultiplier,
            int attempt,
            CompletableFuture<T> result,
            Throwable lastError) {
        
        if (attempt > maxAttempts) {
            // Out of retries
            result.completeExceptionally(
                new RetryExhaustedException("Failed after " + maxAttempts + " attempts", lastError)
            );
            return;
        }
        
        if (attempt > 1) {
            // Delay before retry (except first attempt)
            scheduler.schedule(
                () -> executeAttempt(operation, maxAttempts, currentDelayMs, backoffMultiplier, attempt, result),
                currentDelayMs,
                TimeUnit.MILLISECONDS
            );
        } else {
            // First attempt - no delay
            executeAttempt(operation, maxAttempts, currentDelayMs, backoffMultiplier, attempt, result);
        }
    }
    
    private static <T> void executeAttempt(
            Supplier<CompletableFuture<T>> operation,
            int maxAttempts,
            long currentDelayMs,
            double backoffMultiplier,
            int attempt,
            CompletableFuture<T> result) {
        
        try {
            operation.get()
                .thenAccept(result::complete)
                .exceptionally(ex -> {
                    // Check if we should retry
                    if (shouldRetry(ex) && attempt < maxAttempts) {
                        // Retry with exponential backoff
                        long nextDelay = (long) (currentDelayMs * backoffMultiplier);
                        retryInternal(operation, maxAttempts, nextDelay, backoffMultiplier, attempt + 1, result, ex);
                    } else {
                        // No more retries or non-retryable error
                        result.completeExceptionally(ex);
                    }
                    return null;
                });
        } catch (Exception e) {
            // Synchronous exception during operation creation
            if (shouldRetry(e) && attempt < maxAttempts) {
                long nextDelay = (long) (currentDelayMs * backoffMultiplier);
                retryInternal(operation, maxAttempts, nextDelay, backoffMultiplier, attempt + 1, result, e);
            } else {
                result.completeExceptionally(e);
            }
        }
    }
    
    /**
     * Determine if an error is retryable
     */
    private static boolean shouldRetry(Throwable ex) {
        if (ex == null) return false;
        
        // Unwrap CompletionException
        while (ex instanceof CompletionException && ex.getCause() != null) {
            ex = ex.getCause();
        }
        
        String message = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        
        // Retryable: network/timeout errors
        if (ex instanceof java.net.SocketTimeoutException) return true;
        if (ex instanceof java.net.ConnectException) return true;
        if (ex instanceof java.net.UnknownHostException) return true;
        if (ex instanceof java.io.IOException && message.contains("timeout")) return true;
        if (ex instanceof java.util.concurrent.TimeoutException) return true;
        
        // Retryable: HTTP 5xx server errors
        if (message.contains("500") || message.contains("502") || 
            message.contains("503") || message.contains("504")) {
            return true;
        }
        
        // NOT retryable: client errors (400, 401, 404, etc.)
        if (message.contains("400") || message.contains("401") || 
            message.contains("403") || message.contains("404")) {
            return false;
        }
        
        // Default: retry unknown errors
        return true;
    }
    
    /**
     * Shutdown the scheduler
     */
    public static void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }
    
    /**
     * Exception thrown when all retry attempts are exhausted
     */
    public static class RetryExhaustedException extends Exception {
        public RetryExhaustedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    /**
     * Wrapper for operations with retry config
     */
    public static class RetryConfig {
        public int maxAttempts = 3;
        public long initialDelayMs = 1000;
        public double backoffMultiplier = 2.0;
        
        public RetryConfig() {}
        
        public RetryConfig maxAttempts(int max) {
            this.maxAttempts = max;
            return this;
        }
        
        public RetryConfig initialDelay(long ms) {
            this.initialDelayMs = ms;
            return this;
        }
        
        public RetryConfig backoff(double multiplier) {
            this.backoffMultiplier = multiplier;
            return this;
        }
        
        public <T> CompletableFuture<T> execute(Supplier<CompletableFuture<T>> operation) {
            return withRetry(operation, maxAttempts, initialDelayMs, backoffMultiplier);
        }
    }
}