package com.crissuper20.lightning.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    private RateLimiter rateLimiter;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        // 5 tokens max, refill every 100ms, 1 token per refill
        rateLimiter = new RateLimiter(5, 100, 1);
        playerId = UUID.randomUUID();
    }

    @Test
    void testBurst() {
        // Should be able to consume 5 tokens immediately
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.tryConsume(playerId), "Should allow token " + (i + 1));
        }
        // 6th should fail
        assertFalse(rateLimiter.tryConsume(playerId), "Should reject 6th token");
    }

    @Test
    void testRefill() throws InterruptedException {
        // Consume all
        for (int i = 0; i < 5; i++) {
            rateLimiter.tryConsume(playerId);
        }
        assertFalse(rateLimiter.tryConsume(playerId));

        // Wait for refill (100ms + buffer)
        Thread.sleep(150);

        // Should have at least 1 token now
        assertTrue(rateLimiter.tryConsume(playerId), "Should have refilled 1 token");
    }

    @Test
    void testIndependentPlayers() {
        UUID player2 = UUID.randomUUID();

        // Drain player 1
        for (int i = 0; i < 5; i++) {
            rateLimiter.tryConsume(playerId);
        }
        assertFalse(rateLimiter.tryConsume(playerId));

        // Player 2 should still be fresh
        assertTrue(rateLimiter.tryConsume(player2));
    }

    @Test
    void testPerMinuteConstructor() {
        RateLimiter limiter = RateLimiter.perMinute(60); // 1 per second
        UUID id = UUID.randomUUID();
        
        assertTrue(limiter.tryConsume(id));
        // Depending on implementation, it might start full or empty. 
        // The current implementation starts full (maxTokens).
        // So we can consume up to 60 immediately.
        
        assertEquals(59, limiter.getAvailableTokens(id));
    }
}
