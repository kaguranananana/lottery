package com.anontokyo.lottery.domain.strategy.service.algorithm;

import com.anontokyo.lottery.domain.strategy.model.vo.AwardRateInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */

public abstract class BaseAlgorithm implements IDrawAlgorithm {

    // strategyId -> rateTuple 存储指定抽奖策略的奖品概率散列结果
    // ConcurrentHashMap 保证线程安全
    protected Map<Long, String[]> rateTupleMap = new ConcurrentHashMap<>();

    // strategyId -> List<AwardRateInfo>
    protected Map<Long, List<AwardRateInfo>> awardRateInfoMap = new ConcurrentHashMap<>();

    @Override
    public void initRateData(Long strategyId, List<AwardRateInfo> awardRateInfoList) {
        // 存储对应策略的奖品配置信息（两种算法都需要）
        awardRateInfoMap.put(strategyId, awardRateInfoList);
    }

    /**
     * 判断该策略的概率数据是否已经初始化
     * 检查 awardRateInfoMap 而不是 rateTupleMap，因为两种算法都会写入 awardRateInfoMap
     */
    @Override
    public boolean isInitialized(Long strategyId) {
        return awardRateInfoMap.containsKey(strategyId);
    }

}
