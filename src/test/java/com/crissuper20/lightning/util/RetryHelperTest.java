package com.crissuper20.lightning.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryHelperTest {

    @Test
    void testSuccessFirstTry() throws ExecutionException, InterruptedException {
        CompletableFuture<String> result = RetryHelper.withRetry(
            () -> CompletableFuture.completedFuture("success"),
            3,
            10
        );

        assertEquals("success", result.get());
    }

    @Test
    void testRetrySuccess() throws ExecutionException, InterruptedException {
        AtomicInteger attempts = new AtomicInteger(0);

        CompletableFuture<String> result = RetryHelper.withRetry(
            () -> {
                if (attempts.incrementAndGet() < 2) {
                    return CompletableFuture.failedFuture(new RuntimeException("Fail"));
                }
                return CompletableFuture.completedFuture("success");
            },
            3,
            10
        );

        assertEquals("success", result.get());
        assertEquals(2, attempts.get());
    }

    @Test
    void testRetryExhausted() {
        CompletableFuture<String> result = RetryHelper.withRetry(
            () -> CompletableFuture.failedFuture(new RuntimeException("Fail")),
            3,
            10
        );

        ExecutionException ex = assertThrows(ExecutionException.class, result::get);
        
        // The current implementation returns the last exception, not RetryExhaustedException
        Throwable cause = ex.getCause();
        while (cause instanceof java.util.concurrent.CompletionException) {
            cause = cause.getCause();
        }
        
        assertTrue(cause instanceof RuntimeException, "Expected RuntimeException");
        assertEquals("Fail", cause.getMessage());
    }
}
