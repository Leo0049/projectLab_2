package com.example.demo.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 排程互斥（不載入 Spring context）。
 *
 * <p>守住三件事：沒拿到鎖就不執行；拿到鎖時一定會釋放（包含任務拋例外）；
 * 釋放時帶的是取得鎖時的 token，避免誤放掉其他實例之後拿到的鎖。
 */
class ExclusiveJobRunnerTest {

    @Test
    @DisplayName("其他實例持有鎖時，本實例略過任務，且不釋放別人的鎖")
    void skipsWhenAnotherInstanceHoldsTheLock() {
        RedisLockService locks = mock(RedisLockService.class);
        when(locks.acquireLock(anyString(), anyLong())).thenReturn(null);
        ExclusiveJobRunner runner = new ExclusiveJobRunner(locks);
        AtomicInteger runs = new AtomicInteger();

        boolean ran = runner.runExclusively("coupon-expiry", 600, runs::incrementAndGet);

        assertFalse(ran);
        assertEquals(0, runs.get());
        verify(locks, never()).releaseLock(anyString(), anyString());
    }

    @Test
    @DisplayName("取得鎖時執行任務一次，並以同一個 token 釋放，key 帶 lock:job: 前綴")
    void runsOnceAndReleasesWithTheSameToken() {
        RedisLockService locks = mock(RedisLockService.class);
        when(locks.acquireLock(eq("lock:job:coupon-expiry"), eq(600L))).thenReturn("token-1");
        ExclusiveJobRunner runner = new ExclusiveJobRunner(locks);
        AtomicInteger runs = new AtomicInteger();

        boolean ran = runner.runExclusively("coupon-expiry", 600, runs::incrementAndGet);

        assertTrue(ran);
        assertEquals(1, runs.get());
        verify(locks).releaseLock("lock:job:coupon-expiry", "token-1");
    }

    @Test
    @DisplayName("任務拋出例外時仍會釋放鎖，且例外照常往外拋")
    void releasesTheLockWhenTheJobThrows() {
        RedisLockService locks = mock(RedisLockService.class);
        when(locks.acquireLock(anyString(), anyLong())).thenReturn("token-2");
        ExclusiveJobRunner runner = new ExclusiveJobRunner(locks);

        assertThrows(IllegalStateException.class,
                () -> runner.runExclusively("coupon-expire-old", 600, () -> {
                    throw new IllegalStateException("boom");
                }));

        verify(locks).releaseLock("lock:job:coupon-expire-old", "token-2");
    }
}
