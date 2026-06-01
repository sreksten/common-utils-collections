package com.threeamigos.common.util.implementations.collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@DisplayName("Cache unit tests")
public class CacheUnitTest {

    @Test
    @DisplayName("default constructor should cache values and track hit rate")
    void defaultConstructorTracksHitsMissesAndRate() {
        Cache<String, String> cache = new Cache<>();

        assertEquals(0.0, cache.getCacheHitRate(), 0.0);
        assertEquals(0L, cache.getHitCount());
        assertEquals(0L, cache.getMissCount());
        assertEquals(0, cache.size());

        assertEquals("value", cache.computeIfAbsent("k", () -> "value"));
        assertEquals("value", cache.computeIfAbsent("k", () -> "other"));

        assertEquals(1L, cache.getHitCount());
        assertEquals(1L, cache.getMissCount());
        assertEquals(0.5, cache.getCacheHitRate(), 0.0000001);
        assertEquals(1, cache.size());
    }

    @Test
    @DisplayName("constructor should validate arguments")
    void constructorValidation() {
        assertThrows(IllegalArgumentException.class, () -> new Cache<String, String>(0, 1, 0.75f));
        assertThrows(IllegalArgumentException.class, () -> new Cache<String, String>(1, 0, 0.75f));
        assertThrows(IllegalArgumentException.class, () -> new Cache<String, String>(1, 1, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> new Cache<String, String>(1, 1, 1.0f));
    }

    @Test
    @DisplayName("computeIfAbsent should reject null key and supplier")
    void computeIfAbsentRejectsNullArguments() {
        Cache<String, String> cache = new Cache<>();

        assertThrows(NullPointerException.class, () -> cache.computeIfAbsent(null, () -> "value"));
        assertThrows(NullPointerException.class, () -> cache.computeIfAbsent("k", null));
    }

    @Test
    @DisplayName("cache should support null values")
    void cachesNullValues() {
        Cache<String, String> cache = new Cache<>();

        assertNull(cache.computeIfAbsent("null-key", () -> null));
        assertNull(cache.computeIfAbsent("null-key", () -> {
            fail("supplier should not run for a cached null value");
            return "unexpected";
        }));

        assertEquals(1L, cache.getMissCount());
        assertEquals(1L, cache.getHitCount());
        assertEquals(1, cache.size());
    }

    @Test
    @DisplayName("custom cache size should evict least recently used entries")
    void customCacheEvictsLeastRecentlyUsed() {
        Cache<String, Integer> cache = new Cache<>(2, 1, 0.75f);
        AtomicInteger supplierCalls = new AtomicInteger();

        assertEquals(Integer.valueOf(1), cache.computeIfAbsent("a", () -> {
            supplierCalls.incrementAndGet();
            return 1;
        }));
        assertEquals(Integer.valueOf(2), cache.computeIfAbsent("b", () -> {
            supplierCalls.incrementAndGet();
            return 2;
        }));
        assertEquals(Integer.valueOf(1), cache.computeIfAbsent("a", () -> {
            fail("supplier should not run for cached key");
            return 100;
        }));

        assertEquals(Integer.valueOf(3), cache.computeIfAbsent("c", () -> {
            supplierCalls.incrementAndGet();
            return 3;
        }));
        assertEquals(2, cache.size());

        assertEquals(Integer.valueOf(2), cache.computeIfAbsent("b", () -> {
            supplierCalls.incrementAndGet();
            return 2;
        }));
        assertEquals(Integer.valueOf(3), cache.computeIfAbsent("c", () -> {
            fail("key c should still be cached");
            return 111;
        }));
        assertEquals(Integer.valueOf(1), cache.computeIfAbsent("a", () -> {
            supplierCalls.incrementAndGet();
            return 1;
        }));

        assertEquals(5, supplierCalls.get());
    }

    @Test
    @DisplayName("invalidate and clear should remove entries but preserve stats")
    void invalidateAndClearBehavior() {
        Cache<String, String> cache = new Cache<>();

        assertEquals("v1", cache.computeIfAbsent("k1", () -> "v1"));
        assertEquals("v2", cache.computeIfAbsent("k2", () -> "v2"));
        cache.computeIfAbsent("k2", () -> "other");

        cache.invalidate("k1");
        assertEquals(1, cache.size());
        assertEquals("new", cache.computeIfAbsent("k1", () -> "new"));

        long hitsBeforeClear = cache.getHitCount();
        long missesBeforeClear = cache.getMissCount();
        cache.clear();

        assertEquals(0, cache.size());
        assertEquals(hitsBeforeClear, cache.getHitCount());
        assertEquals(missesBeforeClear, cache.getMissCount());
    }

    @Test
    @DisplayName("invalidateAll should remove matching keys")
    void invalidateAllRemovesMatchingEntries() {
        Cache<String, String> cache = new Cache<>();

        cache.computeIfAbsent("user:1", () -> "a");
        cache.computeIfAbsent("user:2", () -> "b");
        cache.computeIfAbsent("session:1", () -> "c");

        cache.invalidateAll(key -> key.startsWith("user:"));

        assertEquals(1, cache.size());
        assertEquals("c", cache.computeIfAbsent("session:1", () -> "other"));
    }

    @Test
    @DisplayName("supplier exception should not cache value")
    void supplierExceptionDoesNotCacheValue() {
        Cache<String, String> cache = new Cache<>();

        assertThrows(IllegalStateException.class, () -> cache.computeIfAbsent("boom", () -> {
            throw new IllegalStateException("boom");
        }));

        assertEquals(1L, cache.getMissCount());
        assertEquals(0, cache.size());

        assertEquals("ok", cache.computeIfAbsent("boom", () -> "ok"));
        assertEquals(2L, cache.getMissCount());
        assertEquals(0L, cache.getHitCount());
    }

    @Test
    @DisplayName("concurrent callers should compute once for the same key")
    void concurrentComputeIfAbsentComputesOnce() throws InterruptedException {
        Cache<String, String> cache = new Cache<>();
        AtomicInteger supplierCalls = new AtomicInteger();
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch finishSupplier = new CountDownLatch(1);
        AtomicReference<String> firstResult = new AtomicReference<>();
        AtomicReference<String> secondResult = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread first = new Thread(() -> {
            try {
                firstResult.set(cache.computeIfAbsent("shared", () -> {
                    supplierCalls.incrementAndGet();
                    supplierStarted.countDown();
                    try {
                        if (!finishSupplier.await(2, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting for release");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return "value";
                }));
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        Thread second = new Thread(() -> {
            try {
                secondResult.set(cache.computeIfAbsent("shared", () -> {
                    supplierCalls.incrementAndGet();
                    return "should-not-be-used";
                }));
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        first.start();
        assertTrue(supplierStarted.await(2, TimeUnit.SECONDS), "first supplier never started");
        second.start();
        waitForBlocked(second);
        finishSupplier.countDown();

        first.join(2_000);
        second.join(2_000);

        assertFalse(first.isAlive(), "first thread did not finish");
        assertFalse(second.isAlive(), "second thread did not finish");
        if (failure.get() != null) {
            fail(failure.get());
        }

        assertEquals("value", firstResult.get());
        assertEquals("value", secondResult.get());
        assertEquals(1, supplierCalls.get());
        assertEquals(1L, cache.getMissCount());
        assertEquals(1L, cache.getHitCount());
    }

    private void waitForBlocked(Thread thread) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadlineNanos) {
            Thread.sleep(1L);
        }
        assertEquals(Thread.State.BLOCKED, thread.getState(), "thread was not blocked on compute lock");
    }
}
