package com.anontokyo.lottery.domain.strategy.service.algorithm.impl;

import com.anontokyo.lottery.domain.strategy.model.vo.AwardRateInfo;
import com.anontokyo.lottery.domain.strategy.service.algorithm.BaseAlgorithm;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */

@Component("entiretyRateRandomDrawAlgorithm")
public class EntiretyRateRandomDrawAlgorithm extends BaseAlgorithm {

    @Override
    public String randomDraw(Long strategyId, List<String> excludeAwardIds) {
        List<AwardRateInfo> awardRateInfos = awardRateInfoMap.get(strategyId);
        // 记得在BaseAlgorithm或Support里先填充这个Map
        if (null == awardRateInfos) {
            return null;
        }

        // 1. 计算当前可用奖品的总概率分母
        BigDecimal totalRate = BigDecimal.ZERO;
        List<AwardRateInfo> activeAwards = new ArrayList<>();
        for (AwardRateInfo res : awardRateInfos) {
            if (excludeAwardIds.contains(res.getAwardId())) continue;
            activeAwards.add(res);
            totalRate = totalRate.add(res.getAwardRate());
        }

        if (activeAwards.isEmpty()) return null;
        if (activeAwards.size() == 1) return activeAwards.get(0).getAwardId();

        // 2. 生成随机采样点 (0, totalRate]
        // 使用 double 比较比转成 1-100 整数更精确
        double random = new SecureRandom().nextDouble();

        // 3. 概率区间累加算法 (CDF 采样)
        BigDecimal cursor = BigDecimal.ZERO;
        for (AwardRateInfo award : activeAwards) {
            // 计算当前奖品在剩余奖品池中的相对概率区间
            // 比如 A:0.2, B:0.3 -> A区间[0, 0.4], B区间(0.4, 1.0]
            BigDecimal relativeRate = award.getAwardRate().divide(totalRate, 4, BigDecimal.ROUND_HALF_UP);
            cursor = cursor.add(relativeRate);

            if (random <= cursor.doubleValue()) {
                return award.getAwardId();
            }
        }

        return null;
    }
}
