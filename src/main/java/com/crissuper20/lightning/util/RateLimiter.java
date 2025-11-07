package com.crissuper20.lightning.util;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Token bucket rate limiter 
 * 
 * Limits actions per player per time window.
 * Example: 5 invoices per minute, 10 payments per minute
 */
public class RateLimiter {
    
    private final ConcurrentHashMap<UUID, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final int maxTokens;
    private final long refillIntervalMs;
    private final int tokensPerRefill;
    
    /**
     * Create a rate limiter
     * 
     * @param maxTokens Maximum tokens (burst capacity)
     * @param refillIntervalMs How often to refill tokens
     * @param tokensPerRefill How many tokens to add per refill
     */
    public RateLimiter(int maxTokens, long refillIntervalMs, int tokensPerRefill) {
        this.maxTokens = maxTokens;
        this.refillIntervalMs = refillIntervalMs;
        this.tokensPerRefill = tokensPerRefill;
    }
    
    /**
     * Convenient constructor for "N actions per minute"
     */
    public static RateLimiter perMinute(int actionsPerMinute) {
        // Refill 1 token every (60/N) seconds
        long refillMs = (60 * 1000) / actionsPerMinute;
        return new RateLimiter(actionsPerMinute, refillMs, 1);
    }
    
    /**
     * Convenient constructor for "N actions per second"
     */
    public static RateLimiter perSecond(int actionsPerSecond) {
        long refillMs = 1000 / actionsPerSecond;
        return new RateLimiter(actionsPerSecond, refillMs, 1);
    }
    
    /**
     * Check if action is allowed for player
     * 
     * @param playerId Player UUID
     * @return true if allowed (token consumed), false if rate limited
     */
    public boolean tryConsume(UUID playerId) {
        TokenBucket bucket = buckets.computeIfAbsent(playerId, k -> new TokenBucket());
        return bucket.tryConsume();
    }
    
    /**
     * Get remaining tokens for player
     */
    public int getAvailableTokens(UUID playerId) {
        TokenBucket bucket = buckets.get(playerId);
        if (bucket == null) return maxTokens;
        return bucket.getAvailableTokens();
    }
    
    /**
     * Get time until next token available (milliseconds)
     */
    public long getTimeUntilNextToken(UUID playerId) {
        TokenBucket bucket = buckets.get(playerId);
        if (bucket == null) return 0;
        return bucket.getTimeUntilNextToken();
    }
    
    /**
     * Reset rate limit for a player (admin command)
     */
    public void reset(UUID playerId) {
        buckets.remove(playerId);
    }
    
    /**
     * Clear all rate limits
     */
    public void resetAll() {
        buckets.clear();
    }
    
    /**
     * Token bucket implementation
     */
    private class TokenBucket {
        private int tokens;
        private long lastRefillTime;
        
        public TokenBucket() {
            this.tokens = maxTokens;
            this.lastRefillTime = System.currentTimeMillis();
        }
        
        public synchronized boolean tryConsume() {
            refill();
            
            if (tokens > 0) {
                tokens--;
                return true;
            }
            
            return false;
        }
        
        public synchronized int getAvailableTokens() {
            refill();
            return tokens;
        }
        
        public synchronized long getTimeUntilNextToken() {
            refill();
            
            if (tokens >= maxTokens) {
                return 0; // Already at max
            }
            
            long timeSinceLastRefill = System.currentTimeMillis() - lastRefillTime;
            long timeUntilNextRefill = refillIntervalMs - timeSinceLastRefill;
            
            return Math.max(0, timeUntilNextRefill);
        }
        
        private void refill() {
            long now = System.currentTimeMillis();
            long timePassed = now - lastRefillTime;
            
            if (timePassed >= refillIntervalMs) {
                int refills = (int) (timePassed / refillIntervalMs);
                int tokensToAdd = refills * tokensPerRefill;
                
                tokens = Math.min(maxTokens, tokens + tokensToAdd);
                lastRefillTime = now;
            }
        }
    }
    
    /**
     * Result of a rate limit check with details
     */
    public static class RateLimitResult {
        public final boolean allowed;
        public final int remainingTokens;
        public final long retryAfterMs;
        
        public RateLimitResult(boolean allowed, int remainingTokens, long retryAfterMs) {
            this.allowed = allowed;
            this.remainingTokens = remainingTokens;
            this.retryAfterMs = retryAfterMs;
        }
        
        public String getRetryMessage() {
            if (allowed) return null;
            
            long seconds = TimeUnit.MILLISECONDS.toSeconds(retryAfterMs);
            if (seconds < 60) {
                return "Please wait " + seconds + " seconds";
            }
            
            long minutes = TimeUnit.MILLISECONDS.toMinutes(retryAfterMs);
            return "Please wait " + minutes + " minute" + (minutes > 1 ? "s" : "");
        }
    }
    
    /**
     * Check with detailed result
     */
    public RateLimitResult check(UUID playerId) {
        boolean allowed = tryConsume(playerId);
        int remaining = getAvailableTokens(playerId);
        long retryAfter = allowed ? 0 : getTimeUntilNextToken(playerId);
        
        return new RateLimitResult(allowed, remaining, retryAfter);
    }
}