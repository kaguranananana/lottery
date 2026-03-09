package com.anontokyo.lottery.domain.strategy.service.algorithm;

import com.anontokyo.lottery.domain.strategy.model.vo.AwardRateInfo;

import java.util.List;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */

public interface IDrawAlgorithm {

    /**
     * 初始化策略的概率数据（懒加载，首次抽奖时调用一次）
     * 
     * @param strategyId        策略id
     * @param awardRateInfoList 奖品概率配置集合
     */
    void initRateData(Long strategyId, List<AwardRateInfo> awardRateInfoList);

    /**
     * 判断该策略的概率数据是否已经初始化
     * 
     * @param strategyId 策略id
     * @return true=已初始化
     */
    boolean isInitialized(Long strategyId);

    /**
     * 执行抽奖
     * 
     * @param strategyId      策略id
     * @param excludeAwardIds 排除掉已经不能作为抽奖的奖品id
     * @return 抽奖结果
     */
    String randomDraw(Long strategyId, List<String> excludeAwardIds);

}
