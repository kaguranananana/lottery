package com.anontokyo.lottery.infrastructure.utils;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @description: Redis工具类
 * @author：anontokyo
 * @date: 2026/1/30
 */

@Component
public class RedisUtil {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    public long incr(String key, int delta) {
        if (delta < 0) {
            throw new RuntimeException("递增因子必须大于0");
        }
        return stringRedisTemplate.opsForValue().increment(key, delta);
    }

    public long decr(String key, int delta) {
        if (delta < 0) {
            throw new RuntimeException("递减因子必须大于0");
        }
        return stringRedisTemplate.opsForValue().increment(key, -delta);
    }


    public boolean setNx(String key, long lockExpireMils) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                .setIfAbsent(key, String.valueOf(System.currentTimeMillis() + lockExpireMils), lockExpireMils,
                        TimeUnit.MILLISECONDS));

    }

    /**
     * 释放带属主标记的锁：只有当 key 的值与 token 匹配时才删除，
     * 避免“A 超时后 B 获得锁，A finally DEL 误删 B 的锁”。原子性由 Lua 保证。
     */
    public boolean deleteIfValueEquals(String key, String token) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(
                "if redis.call('get', KEYS[1]) == ARGV[1] then "
                        + "return redis.call('del', KEYS[1]); end return 0", Long.class);
        Long result = stringRedisTemplate.execute(script, Collections.singletonList(key), token);
        return result != null && result == 1L;
    }

    public boolean setIfAbsent(String key, String value) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, value));
    }

    public boolean setIfAbsent(String key, String value, long expireSeconds) {
        if (expireSeconds <= 0) {
            return setIfAbsent(key, value);
        }
        return Boolean.TRUE.equals(
                stringRedisTemplate.opsForValue().setIfAbsent(key, value, expireSeconds, TimeUnit.SECONDS));
    }

    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    public void setWithExpire(String key, String value, long expireSeconds) {
        if (expireSeconds <= 0) {
            stringRedisTemplate.opsForValue().set(key, value);
            return;
        }
        stringRedisTemplate.opsForValue().set(key, value, expireSeconds, TimeUnit.SECONDS);
    }

    public void delStringKey(String key) {
        stringRedisTemplate.delete(key);
    }

    public void del(String... key) {
        if (key != null && key.length > 0) {
            if (key.length == 1) {
                stringRedisTemplate.delete(key[0]);
            } else {
                stringRedisTemplate.delete((Collection<String>) CollectionUtils.arrayToList(key));
            }
        }
    }
}
