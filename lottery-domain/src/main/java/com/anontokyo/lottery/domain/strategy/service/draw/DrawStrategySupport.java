package com.anontokyo.lottery.domain.strategy.service.draw;

import com.anontokyo.lottery.domain.strategy.model.aggregates.StrategyRich;
import com.anontokyo.lottery.domain.strategy.model.vo.AwardBriefVO;
import com.anontokyo.lottery.domain.strategy.repository.IStrategyRepository;

import javax.annotation.Resource;

/**
 * 抽奖策略数据支撑。一些通用的数据服务，封装了对 Repository 的调用。
 * 如果未来的数据源从 MySQL 变成 Redis 缓存，或者增加多级缓存逻辑，只需要修改 Support 类这一处，所有子类会自动升级。
 *
 * @author anontokyo
 * @date 2025/12/20
 */
public class DrawStrategySupport extends DrawConfig {

    @Resource
    protected IStrategyRepository strategyRepository;

    /**
     * 查询策略配置信息。
     *
     * @param strategyId 策略ID
     * @return 策略聚合信息
     */
    protected StrategyRich queryStrategyRich(Long strategyId) {
        return strategyRepository.queryStrategyRich(strategyId);
    }

    /**
     * 查询奖品详情信息。
     *
     * @param awardId 奖品ID
     * @return 奖品信息
     */
    protected AwardBriefVO queryAwardInfoById(String awardId) {
        return strategyRepository.queryAwardInfo(awardId);
    }


}
