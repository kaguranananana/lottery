package com.anontokyo.lottery.infrastructure.utils;

import com.anontokyo.lottery.common.Constants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/** 库存和待对账记录在同一个 Redis Lua 命令内修改。 */
@Component
public class RedisStockReservations {
    private static final String BY_TAKE = "lottery_stock_reservation_by_take";
    private static final String PENDING = "lottery_stock_reservations";

    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>(
            // 缺键由运营对账，售罄继续消费参与记录并返回未中奖。
            "local stock = redis.call('GET', KEYS[1]); "
            + "if not stock then return -1 end; "
            + "if tonumber(stock) <= 0 then return 0 end; "
            // 先登记可被重启扫描的索引，再扣库存。两步对其它客户端原子可见。
            + "redis.call('ZADD', KEYS[2], ARGV[1], ARGV[2]); "
            + "redis.call('HSET', KEYS[3], ARGV[3], ARGV[2]); "
            + "redis.call('DECR', KEYS[1]); return 1;", Long.class);

    private static final DefaultRedisScript<Long> RESOLVE = new DefaultRedisScript<>(
            // 以成员是否存在作为回补资格，多次对账只执行一次。
            "if not redis.call('ZSCORE', KEYS[2], ARGV[1]) then return 0 end; "
            + "if ARGV[2] == 'refund' then "
            + "if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end; "
            + "redis.call('INCR', KEYS[1]); end; "
            + "if redis.call('HGET', KEYS[3], ARGV[3]) == ARGV[1] then "
            + "redis.call('HDEL', KEYS[3], ARGV[3]); end; "
            + "return redis.call('ZREM', KEYS[2], ARGV[1]);", Long.class);

    private final StringRedisTemplate redis;

    public RedisStockReservations(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean reserve(String marker, long takeId, long activityId, String awardId, long deadline) {
        String stockKey = Constants.RedisKey.KEY_LOTTERY_ACTIVITY_PRIZE_STOCK_COUNT(activityId, awardId);
        long result = redis.execute(RESERVE, Arrays.asList(stockKey, PENDING, BY_TAKE), Long.toString(deadline), marker,
                Long.toString(takeId));
        if (result == -1) {
            throw new IllegalStateException("库存 Key 不存在，key=" + stockKey);
        }
        return result == 1;
    }

    public void resolve(String marker, long takeId, long activityId, String awardId, boolean committed) {
        String stockKey = Constants.RedisKey.KEY_LOTTERY_ACTIVITY_PRIZE_STOCK_COUNT(activityId, awardId);
        long result = redis.execute(RESOLVE, Arrays.asList(stockKey, PENDING, BY_TAKE), marker,
                committed ? "keep" : "refund", Long.toString(takeId));
        if (result == -1) {
            throw new IllegalStateException("预扣待对账，库存 Key 不存在，marker=" + marker);
        }
    }

    public String findByTake(long takeId) {
        return (String) redis.opsForHash().get(BY_TAKE, Long.toString(takeId));
    }

    public Set<String> expired(long now, int limit) {
        return redis.opsForZSet().rangeByScore(PENDING, 0, now, 0, limit);
    }
}
