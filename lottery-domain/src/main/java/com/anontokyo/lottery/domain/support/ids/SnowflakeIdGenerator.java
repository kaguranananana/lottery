package com.anontokyo.lottery.domain.support.ids;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.net.NetUtil;
import cn.hutool.core.util.IdUtil;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * Hutool Snowflake：workerId 取本机 IP 派生值低 5 位。
 * 单实例部署下没有冲突问题；多实例部署时应改为配置或注册中心分配 workerId（known limitation）。
 */
@Component
public class SnowflakeIdGenerator implements IdGenerator {

    private Snowflake snowflake;

    @PostConstruct
    public void init() {
        long workerId;
        try {
            workerId = NetUtil.ipv4ToLong(NetUtil.getLocalhostStr());
        } catch (Exception e) {
            workerId = NetUtil.getLocalhostStr().hashCode();
        }
        workerId = workerId >> 16 & 31;
        snowflake = IdUtil.createSnowflake(workerId, 1L);
    }

    @Override
    public synchronized long nextId() {
        return snowflake.nextId();
    }
}
