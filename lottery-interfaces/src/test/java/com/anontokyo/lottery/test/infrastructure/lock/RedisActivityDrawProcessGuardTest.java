package com.anontokyo.lottery.test.infrastructure.lock;

import com.anontokyo.lottery.infrastructure.lock.RedisActivityDrawProcessGuard;
import com.anontokyo.lottery.infrastructure.utils.RedisUtil;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RedisActivityDrawProcessGuardTest {

    @Test
    public void shouldReturnFalseWhenDrawInflightKeyAlreadyExists() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.setIfAbsent(anyString(), anyString(), anyLong())).thenReturn(false);

        RedisActivityDrawProcessGuard guard = buildGuard(redisUtil);

        boolean claimed = guard.tryClaim(100001L, "u001");

        assertFalse(claimed);
    }

    @Test
    public void shouldReturnTrueWhenDrawInflightKeyIsClaimed() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.setIfAbsent(anyString(), anyString(), anyLong())).thenReturn(true);

        RedisActivityDrawProcessGuard guard = buildGuard(redisUtil);

        boolean claimed = guard.tryClaim(100001L, "u001");

        assertTrue(claimed);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldFailClosedWhenRedisClaimThrowsException() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.setIfAbsent(anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("redis unavailable"));

        RedisActivityDrawProcessGuard guard = buildGuard(redisUtil);

        guard.tryClaim(100001L, "u001");
    }

    private RedisActivityDrawProcessGuard buildGuard(RedisUtil redisUtil) {
        RedisActivityDrawProcessGuard guard = new RedisActivityDrawProcessGuard();
        ReflectionTestUtils.setField(guard, "redisUtil", redisUtil);
        ReflectionTestUtils.setField(guard, "drawInflightRedisTtlSeconds", 3L);
        return guard;
    }
}
