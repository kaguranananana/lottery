package com.anontokyo.lottery.infrastructure.dao;

import com.anontokyo.lottery.infrastructure.po.DrawConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */

@Mapper
public interface IDrawConfigDao {

    DrawConfig queryDrawConfig(Long drawConfigId);

    /** 在启动事务中锁定共享抽奖配置，串行化不同活动的奖品库存初始化。 */
    DrawConfig queryDrawConfigForUpdate(Long drawConfigId);

    void insert(DrawConfig drawConfig);
}
