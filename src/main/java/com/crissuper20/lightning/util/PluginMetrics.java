package com.crissuper20.lightning.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.*;

/**
 * Comprehensive metrics system for monitoring plugin health and usage
 */
public class PluginMetrics {
    
    // Command execution metrics
    private final AtomicInteger invoicesCreated = new AtomicInteger(0);
    private final AtomicInteger invoicesPaid = new AtomicInteger(0);
    private final AtomicInteger paymentsAttempted = new AtomicInteger(0);
    private final AtomicInteger paymentsSuccessful = new AtomicInteger(0);
    private final AtomicInteger paymentsFailed = new AtomicInteger(0);
    
    // Balance tracking
    private final AtomicLong totalSatsDeposited = new AtomicLong(0);
    private final AtomicLong totalSatsWithdrawn = new AtomicLong(0);
    
    // Error tracking
    private final AtomicInteger apiErrors = new AtomicInteger(0);
    private final AtomicInteger networkErrors = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicInteger> errorsByType = new ConcurrentHashMap<>();
    
    // Performance metrics
    private final ConcurrentHashMap<String, PerformanceMetric> operationTimings = new ConcurrentHashMap<>();
    
    // Player activity
    private final ConcurrentHashMap<UUID, PlayerActivity> playerActivity = new ConcurrentHashMap<>();
    
    private final long startTime;
    
    public PluginMetrics() {
        this.startTime = System.currentTimeMillis();
    }
    
    // ================================================================
    // Recording methods
    // ================================================================
    
    public void recordInvoiceCreated(UUID playerId, long amountSats) {
        invoicesCreated.incrementAndGet();
        updatePlayerActivity(playerId, activity -> activity.invoicesCreated++);
    }
    
    public void recordInvoicePaid(UUID playerId, long amountSats) {
        invoicesPaid.incrementAndGet();
        totalSatsDeposited.addAndGet(amountSats);
        updatePlayerActivity(playerId, activity -> {
            activity.invoicesPaid++;
            activity.totalDeposited += amountSats;
        });
    }
    
    public void recordPaymentAttempt(UUID playerId) {
        paymentsAttempted.incrementAndGet();
        updatePlayerActivity(playerId, activity -> activity.paymentsAttempted++);
    }
    
    public void recordPaymentSuccess(UUID playerId, long amountSats) {
        paymentsSuccessful.incrementAndGet();
        totalSatsWithdrawn.addAndGet(amountSats);
        updatePlayerActivity(playerId, activity -> {
            activity.paymentsSuccessful++;
            activity.totalWithdrawn += amountSats;
        });
    }
    
    public void recordPaymentFailure(UUID playerId, String reason) {
        paymentsFailed.incrementAndGet();
        updatePlayerActivity(playerId, activity -> activity.paymentsFailed++);
        recordError("payment_failure", reason);
    }
    
    public void recordApiError() {
        apiErrors.incrementAndGet();
    }
    
    public void recordNetworkError() {
        networkErrors.incrementAndGet();
    }
    
    public void recordError(String errorType, String details) {
        errorsByType.computeIfAbsent(errorType, k -> new AtomicInteger(0)).incrementAndGet();
    }
    
    /**
     * Record timing for an operation
     */
    public void recordOperationTime(String operation, long milliseconds) {
        operationTimings.computeIfAbsent(operation, k -> new PerformanceMetric())
            .record(milliseconds);
    }
    
    /**
     * Helper to time operations
     */
    public TimingContext startTiming(String operation) {
        return new TimingContext(this, operation);
    }
    
    // ================================================================
    // Query methods
    // ================================================================
    
    public int getInvoicesCreated() {
        return invoicesCreated.get();
    }
    
    public int getInvoicesPaid() {
        return invoicesPaid.get();
    }
    
    public int getPaymentsAttempted() {
        return paymentsAttempted.get();
    }
    
    public int getPaymentsSuccessful() {
        return paymentsSuccessful.get();
    }
    
    public int getPaymentsFailed() {
        return paymentsFailed.get();
    }
    
    public double getPaymentSuccessRate() {
        int total = paymentsAttempted.get();
        if (total == 0) return 100.0;
        return (paymentsSuccessful.get() * 100.0) / total;
    }
    
    public long getTotalSatsDeposited() {
        return totalSatsDeposited.get();
    }
    
    public long getTotalSatsWithdrawn() {
        return totalSatsWithdrawn.get();
    }
    
    public long getNetBalance() {
        return totalSatsDeposited.get() - totalSatsWithdrawn.get();
    }
    
    public int getTotalErrors() {
        return apiErrors.get() + networkErrors.get();
    }
    
    public Map<String, Integer> getErrorBreakdown() {
        Map<String, Integer> breakdown = new HashMap<>();
        breakdown.put("api_errors", apiErrors.get());
        breakdown.put("network_errors", networkErrors.get());
        errorsByType.forEach((type, count) -> breakdown.put(type, count.get()));
        return breakdown;
    }
    
    public long getUptimeMillis() {
        return System.currentTimeMillis() - startTime;
    }
    
    public String getUptimeFormatted() {
        long millis = getUptimeMillis();
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours % 24, minutes % 60);
        } else if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        } else {
            return String.format("%ds", seconds);
        }
    }
    
    public PlayerActivity getPlayerActivity(UUID playerId) {
        return playerActivity.computeIfAbsent(playerId, k -> new PlayerActivity());
    }

    private void updatePlayerActivity(UUID playerId, java.util.function.Consumer<PlayerActivity> updater) {
        if (playerId == null || updater == null) {
            return;
        }
        updater.accept(getPlayerActivity(playerId));
    }
    
    public List<Map.Entry<UUID, PlayerActivity>> getTopPlayers(int limit) {
        return playerActivity.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().getTotalVolume(), a.getValue().getTotalVolume()))
            .limit(limit)
            .collect(java.util.stream.Collectors.toList());
    }
    
    public PerformanceMetric getOperationTiming(String operation) {
        return operationTimings.get(operation);
    }
    
    public Map<String, PerformanceMetric> getAllTimings() {
        return new HashMap<>(operationTimings);
    }
    
    /**
     * Generate a summary report
     */
    public String generateReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Lightning Plugin Metrics ===\n");
        sb.append("Uptime: ").append(getUptimeFormatted()).append("\n\n");
        
        sb.append("--- Transactions ---\n");
        sb.append("Invoices Created: ").append(invoicesCreated.get()).append("\n");
        sb.append("Invoices Paid: ").append(invoicesPaid.get()).append("\n");
        sb.append("Payments Attempted: ").append(paymentsAttempted.get()).append("\n");
        sb.append("Payments Successful: ").append(paymentsSuccessful.get()).append("\n");
        sb.append("Payments Failed: ").append(paymentsFailed.get()).append("\n");
        sb.append("Success Rate: ").append(String.format("%.1f%%", getPaymentSuccessRate())).append("\n\n");
        
        sb.append("--- Volume ---\n");
        sb.append("Total Deposited: ").append(String.format("%,d sats", totalSatsDeposited.get())).append("\n");
        sb.append("Total Withdrawn: ").append(String.format("%,d sats", totalSatsWithdrawn.get())).append("\n");
        sb.append("Net Balance: ").append(String.format("%,d sats", getNetBalance())).append("\n\n");
        
        sb.append("--- Errors ---\n");
        sb.append("API Errors: ").append(apiErrors.get()).append("\n");
        sb.append("Network Errors: ").append(networkErrors.get()).append("\n");
        if (!errorsByType.isEmpty()) {
            errorsByType.forEach((type, count) -> 
                sb.append("  ").append(type).append(": ").append(count.get()).append("\n"));
        }
        
        return sb.toString();
    }
    
    // ================================================================
    // Helper classes
    // ================================================================
    
    public static class PlayerActivity {
        public int invoicesCreated;
        public int invoicesPaid;
        public int paymentsAttempted;
        public int paymentsSuccessful;
        public int paymentsFailed;
        public long totalDeposited;
        public long totalWithdrawn;
        
        public long getTotalVolume() {
            return totalDeposited + totalWithdrawn;
        }
    }
    
    public static class PerformanceMetric {
        private final AtomicInteger count = new AtomicInteger(0);
        private final AtomicLong totalTime = new AtomicLong(0);
        private final AtomicLong minTime = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxTime = new AtomicLong(0);
        
        public void record(long milliseconds) {
            count.incrementAndGet();
            totalTime.addAndGet(milliseconds);
            
            // Update min
            long currentMin;
            do {
                currentMin = minTime.get();
                if (milliseconds >= currentMin) break;
            } while (!minTime.compareAndSet(currentMin, milliseconds));
            
            // Update max
            long currentMax;
            do {
                currentMax = maxTime.get();
                if (milliseconds <= currentMax) break;
            } while (!maxTime.compareAndSet(currentMax, milliseconds));
        }
        
        public int getCount() {
            return count.get();
        }
        
        public double getAverageTime() {
            int c = count.get();
            if (c == 0) return 0;
            return totalTime.get() / (double) c;
        }
        
        public long getMinTime() {
            long min = minTime.get();
            return min == Long.MAX_VALUE ? 0 : min;
        }
        
        public long getMaxTime() {
            return maxTime.get();
        }
        
        @Override
        public String toString() {
            return String.format("count=%d, avg=%.1fms, min=%dms, max=%dms", 
                getCount(), getAverageTime(), getMinTime(), getMaxTime());
        }
    }
    
    public static class TimingContext implements AutoCloseable {
        private final PluginMetrics metrics;
        private final String operation;
        private final long startTime;
        
        public TimingContext(PluginMetrics metrics, String operation) {
            this.metrics = metrics;
            this.operation = operation;
            this.startTime = System.currentTimeMillis();
        }
        
        @Override
        public void close() {
            long elapsed = System.currentTimeMillis() - startTime;
            metrics.recordOperationTime(operation, elapsed);
        }
    }
}