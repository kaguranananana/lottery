package com.anontokyo.lottery.domain.strategy.service.algorithm.impl;

import com.anontokyo.lottery.domain.strategy.model.vo.AwardRateInfo;
import com.anontokyo.lottery.domain.strategy.service.algorithm.BaseAlgorithm;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */

@Component("singleRateRandomDrawAlgorithm")
public class SingleRateRandomDrawAlgorithm extends BaseAlgorithm {

    // 斐波那契散列增量
    private final int HASH_INCREMENT = 0x61c88647;

    // 数组初始化长度
    private final int RATE_TUPLE_LENGTH = 128;

    @Override
    public void initRateData(Long strategyId, List<AwardRateInfo> awardRateInfoList) {
        // 1. 先调用父类方法，把 awardRateInfoMap 填充了
        super.initRateData(strategyId, awardRateInfoList);

        // 2. 特化逻辑：构建斐波那契散列表用于 O(1) 概率查找
        String[] rateTuple = rateTupleMap.computeIfAbsent(strategyId, k -> new String[RATE_TUPLE_LENGTH]);
        int cursor = 0;
        for (AwardRateInfo awardRateInfo : awardRateInfoList) {
            int rateVal = awardRateInfo.getAwardRate().multiply(new BigDecimal(100)).intValue();
            for (int i = cursor + 1; i <= cursor + rateVal; i++) {
                rateTuple[hashIdx(i)] = awardRateInfo.getAwardId();
            }
            cursor += rateVal;
        }
    }

    @Override
    public String randomDraw(Long strategyId, List<String> excludeAwardIds) {
        // 获取策略对应的元组
        String[] rateTuple = super.rateTupleMap.get(strategyId);
        assert rateTuple != null;
        // 随机索引
        int randomVal = new SecureRandom().nextInt(100) + 1;
        int idx = hashIdx(randomVal);
        // 返回结果
        String awardId = rateTuple[idx];
        if (excludeAwardIds.contains(awardId)) {
            return null;
        }

        return awardId;
    }

    /**
     * 斐波那契散列
     * 
     * @param val
     * @return
     */
    protected int hashIdx(int val) {
        int hashCode = val * HASH_INCREMENT + HASH_INCREMENT;
        return hashCode & (RATE_TUPLE_LENGTH - 1);
    }

}
