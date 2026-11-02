package com.anontokyo.lottery.test.infrastructure;

import com.anontokyo.lottery.infrastructure.cache.CacheConfigProperties;
import com.anontokyo.lottery.infrastructure.cache.TwoLevelCacheService;
import com.anontokyo.lottery.infrastructure.utils.RedisUtil;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.mockito.Mockito.*;

/** 真实 Spring 事务提交前不能删除缓存，否则其它请求可回填尚未提交的旧配置。 */
public class CacheTransactionInvalidationTest {
    private RedisUtil redis;
    private TwoLevelCacheService cache;
    private TransactionTemplate transaction;

    @Before
    public void setup() throws Exception {
        redis = mock(RedisUtil.class);
        cache = new TwoLevelCacheService();
        ReflectionTestUtils.setField(cache, "redisUtil", redis);
        ReflectionTestUtils.setField(cache, "cacheConfigProperties", new CacheConfigProperties());
        cache.init();
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @Test
    public void activityInvalidationWaitsForCommit() {
        transaction.execute(status -> {
            cache.evictActivityCache("activity-probe");
            // 在事务内应保留旧缓存，提交后再删除，阻止过早回源。
            verify(redis, never()).delStringKey("activity-probe");
            return null;
        });
        verify(redis).delStringKey("activity-probe");
    }

    @Test
    public void snapshotInvalidationWaitsForCommit() {
        transaction.execute(status -> {
            cache.evictStrategySnapshotCache("snapshot-probe");
            verify(redis, never()).delStringKey("snapshot-probe");
            return null;
        });
        verify(redis).delStringKey("snapshot-probe");
    }

    @Test
    public void rolledBackConfigurationDoesNotInvalidateTheCommittedCache() {
        transaction.execute(status -> {
            cache.evictActivityCache("activity-probe");
            status.setRollbackOnly();
            return null;
        });
        verify(redis, never()).delStringKey("activity-probe");
    }
}
