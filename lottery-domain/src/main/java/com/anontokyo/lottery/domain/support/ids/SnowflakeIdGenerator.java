package com.anontokyo.lottery.domain.support.ids;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * Hutool Snowflake：节点号由部署配置提供，同一数据中心的实例必须使用不同 workerId。
 * 不再从 IP 网段推导节点号，避免同宿主机或同网段实例落到同一编号。
 */
@Component
public class SnowflakeIdGenerator implements IdGenerator {

    private Snowflake snowflake;

    @Value("${lottery.ids.worker-id}")
    private long workerId;

    @Value("${lottery.ids.datacenter-id}")
    private long datacenterId;

    @PostConstruct
    public void init() {
        // 直接使用显式节点配置；超出 Snowflake 范围的配置由构造器在启动时拒绝。
        snowflake = IdUtil.createSnowflake(workerId, datacenterId);
    }

    @Override
    public synchronized long nextId() {
        return snowflake.nextId();
    }
}
