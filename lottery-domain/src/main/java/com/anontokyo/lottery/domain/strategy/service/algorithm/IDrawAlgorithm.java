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
     * 程序启动时初始化概率元组, 在初始化完成之后不允许修改元组数据
     * @param strategyId 策略id
     * @param awardRateInfoList 奖品概率配置集合
     */
    void initRateTuple(Long strategyId, List<AwardRateInfo> awardRateInfoList);


    /**
     * 判断是否已经做了数据初始化
     * @param strategyId
     * @return
     */
    boolean isExistRateTuple(Long strategyId);

    /**
     *
     * @param strategyId 策略id
     * @param excludeAwardIds 排除掉已经不能作为抽奖的奖品id
     * @return 抽奖结果
     */
    String randomDraw(Long strategyId, List<String> excludeAwardIds);

}
