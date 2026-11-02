package com.anontokyo.lottery.test.domain;

import com.anontokyo.lottery.domain.support.ids.SnowflakeIdGenerator;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import static org.junit.Assert.*;

/** 同一宿主机的两个实例必须使用各自配置的节点号，不能从相同网段推导为同一节点。 */
public class SnowflakeNodeConfigurationTest {
    @Test
    public void configuredNodesOnTheSameHostGenerateDistinctIds() {
        try (AnnotationConfigApplicationContext first = node(2, 3);
                AnnotationConfigApplicationContext second = node(7, 3)) {
            SnowflakeIdGenerator one = first.getBean(SnowflakeIdGenerator.class);
            SnowflakeIdGenerator two = second.getBean(SnowflakeIdGenerator.class);
            Set<Long> ids = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                long left = one.nextId();
                long right = two.nextId();
                // 直接检查实际 ID 的节点位，避免偶然时间差掩盖配置被忽略。
                assertEquals(2L, (left >> 12) & 31L);
                assertEquals(7L, (right >> 12) & 31L);
                assertEquals(3L, (left >> 17) & 31L);
                assertTrue(ids.add(left));
                assertTrue(ids.add(right));
            }
        }
    }

    private AnnotationConfigApplicationContext node(long worker, long datacenter) {
        Properties properties = new Properties();
        properties.setProperty("lottery.ids.worker-id", String.valueOf(worker));
        properties.setProperty("lottery.ids.datacenter-id", String.valueOf(datacenter));
        PropertySourcesPlaceholderConfigurer placeholders = new PropertySourcesPlaceholderConfigurer();
        placeholders.setProperties(properties);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.addBeanFactoryPostProcessor(placeholders);
        context.register(SnowflakeIdGenerator.class);
        context.refresh();
        return context;
    }
}
