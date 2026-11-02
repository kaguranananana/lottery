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

    /** 检查存在性、判断余量及扣减在同一个脚本内完成，避免读后删 Key 或进程中断留下负库存。 */
    private static final DefaultRedisScript<Long> DEDUCT_STOCK = new DefaultRedisScript<>(
            // 读取当前库存；脚本执行期间其它命令不能插入。
            "local stock = redis.call('GET', KEYS[1]); "
                    // 缺失是状态错误，与售罄使用不同返回值。
                    + "if not stock then return -1 end; "
                    // 售罄直接拒绝，不先减成负数再回补。
                    + "if tonumber(stock) <= 0 then return -2 end; "
                    // 仅对存在且有余量的库存执行一次扣减。
                    + "return redis.call('DECR', KEYS[1]);", Long.class);

    /** 回补只作用于现存库存；禁止 INCR 隐式创建一个余量为 1 的新 Key。 */
    private static final DefaultRedisScript<Long> RECOVER_STOCK = new DefaultRedisScript<>(
            // 与 INCR 放在同一个原子脚本内，避免存在性检查后的删除竞争。
            "if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end; "
                    // 现存库存才允许归还本次预扣量。
                    + "return redis.call('INCR', KEYS[1]);", Long.class);

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 返回非负剩余库存；-2 表示售罄，缺失库存或 Redis 执行失败直接抛出。 */
    public long deductStock(String key) {
        long remaining = stringRedisTemplate.execute(DEDUCT_STOCK, Collections.singletonList(key));
        // 缺失库存不得转换为正常未中奖，更不能从数据库热路径回填。
        if (remaining == -1L) {
            throw new IllegalStateException("库存 Key 不存在，key=" + key);
        }
        return remaining;
    }

    /** 仅归还已有 Key 的一个预扣量；异常交给业务调用方处理。 */
    public void recoverStock(String key) {
        long remaining = stringRedisTemplate.execute(RECOVER_STOCK, Collections.singletonList(key));
        // 需要对账恢复的缺失库存必须保持缺失，不能伪造恢复成功。
        if (remaining == -1L) {
            throw new IllegalStateException("回补库存 Key 不存在，key=" + key);
        }
    }


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
