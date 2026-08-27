package com.anontokyo.lottery.test.infrastructure.lock;

import com.anontokyo.lottery.infrastructure.lock.RedisActivityDrawProcessGuard;
import com.anontokyo.lottery.infrastructure.utils.RedisUtil;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RedisActivityDrawProcessGuardTest {

    @Test
    public void shouldReturnFalseWhenDrawInflightKeyAlreadyExists() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.setIfAbsent(anyString(), anyString(), anyLong())).thenReturn(false);

        RedisActivityDrawProcessGuard guard = buildGuard(redisUtil);

        boolean claimed = guard.tryClaim(100001L, "u001", "token-A");

        assertFalse(claimed);
    }

    @Test
    public void shouldReturnTrueWhenDrawInflightKeyIsClaimed() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.setIfAbsent(anyString(), anyString(), anyLong())).thenReturn(true);

        RedisActivityDrawProcessGuard guard = buildGuard(redisUtil);

        boolean claimed = guard.tryClaim(100001L, "u001", "token-A");

        assertTrue(claimed);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldFailClosedWhenRedisClaimThrowsException() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.setIfAbsent(anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("redis unavailable"));

        RedisActivityDrawProcessGuard guard = buildGuard(redisUtil);

        guard.tryClaim(100001L, "u001", "token-A");
    }

    /**
     * 场景 F：A 超时后 B 获得锁，A finally 释放 —— 必须通过 compare-and-delete 判定属主，
     * 不允许无条件 DEL。
     */
    @Test
    public void releaseMustUseOwnerCompareAndDeleteNotPlainDel() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        // 模拟 A 的 token 已过期、当前 key 属于 B
        when(redisUtil.deleteIfValueEquals(anyString(), eq("token-B"))).thenReturn(true);
        when(redisUtil.deleteIfValueEquals(anyString(), eq("token-A"))).thenReturn(false);

        RedisActivityDrawProcessGuard guard = buildGuard(redisUtil);

        guard.release(100001L, "u001", "token-A");

        verify(redisUtil).deleteIfValueEquals(eq("lottery_draw_inflight_100001_u001"), eq("token-A"));
        verify(redisUtil, never()).delStringKey(anyString());
        verify(redisUtil, never()).del(anyString(), anyString(), anyString());
    }

    @Test
    public void tryClaimStoresRandomTokenAsLockValue() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.setIfAbsent(anyString(), anyString(), anyLong())).thenReturn(true);

        RedisActivityDrawProcessGuard guard = buildGuard(redisUtil);

        assertTrue(guard.tryClaim(100001L, "u001", "token-A"));

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(redisUtil).setIfAbsent(eq("lottery_draw_inflight_100001_u001"), valueCaptor.capture(),
                ttlCaptor.capture());
        org.junit.Assert.assertEquals("3s in-flight TTL", Long.valueOf(3L), ttlCaptor.getValue());
        org.junit.Assert.assertEquals("lock value must be the owner token",
                "token-A", valueCaptor.getValue());
    }

    private RedisActivityDrawProcessGuard buildGuard(RedisUtil redisUtil) {
        RedisActivityDrawProcessGuard guard = new RedisActivityDrawProcessGuard();
        ReflectionTestUtils.setField(guard, "redisUtil", redisUtil);
        ReflectionTestUtils.setField(guard, "drawInflightRedisTtlSeconds", 3L);
        return guard;
    }
}
