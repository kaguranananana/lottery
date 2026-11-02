package com.anontokyo.lottery.infrastructure.dao;

import com.anontokyo.lottery.infrastructure.po.Strategy;
import org.apache.ibatis.annotations.Mapper;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */

@Mapper
public interface IStrategyDao {

    Strategy queryStrategy(Long strategyId);

    /** 在启动事务中锁定共享策略，串行化不同活动的奖品库存初始化。 */
    Strategy queryStrategyForUpdate(Long strategyId);

    void insert(Strategy strategy);
}
