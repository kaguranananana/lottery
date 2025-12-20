package cn.itedus.lottery.domain.strategy.service.algorithm;

import cn.itedus.lottery.common.Constants;
import cn.itedus.lottery.domain.strategy.model.vo.AwardRateInfo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class BaseAlgorithm implements  IDrawAlgorithm{

    // 斐波那契散列增量
    private final int HASH_INCREMENT = 0x61c88647;

    //数组初始化长度
    private final int RATE_TUPLE_LENGTH =128;

    // strategyId -> rateTuple 存储指定抽奖策略的奖品概率散列结果
    // ConcurrentHashMap 保证线程安全
    protected Map<Long, String[]> rateTupleMap =new ConcurrentHashMap<>();

    // strategyId -> List<AwardRateInfo>
    protected Map<Long, List<AwardRateInfo>> awardRateInfoMap = new ConcurrentHashMap<>();



    @Override
    public void initRateTuple(Long strategyId, List<AwardRateInfo> awardRateInfoList, Integer strategyMode) {
        // 存储对应策略的奖品配置信息
        awardRateInfoMap.put(strategyId, awardRateInfoList);

        if (!Constants.StrategyMode.SINGLE.getCode().equals(strategyMode)) {
            return ;
        }

        // 获取或创建对应策略的概率数组
        String[] rateTuple = rateTupleMap.computeIfAbsent(strategyId, k -> new String[RATE_TUPLE_LENGTH]);

        // 概率区间指针
        int cursor = 0;
        for (AwardRateInfo awardRateInfo : awardRateInfoList) {
            // 将奖品的概率信息转换为散列数组的槽位
            // 这里如果是概率极小, 可能精度不够, 后续再看看...
            int rateVal = awardRateInfo.getAwardRate().multiply(new BigDecimal(100)).intValue();

            // 循环rateVal次数, 生成散列下标, 填充到数组之中
            for (int i = cursor + 1; i <= cursor + rateVal; i++) {
                rateTuple[hashIdx(i)] = awardRateInfo.getAwardId();
            }
            // 更新指针偏移量, 处理下一个奖品
            cursor += rateVal;

        }
    }

    /**
     * 判断该策略下的概率元组是否已经初始化
     * @param strategyId
     * @return
     */
    @Override
    public boolean isExistRateTuple(Long strategyId) {
        return rateTupleMap.containsKey(strategyId);
    }

    /**
     * 斐波那契散列
     * @param val
     * @return
     */
    protected int hashIdx(int val) {
        int hashCode = val * HASH_INCREMENT + HASH_INCREMENT;
        return hashCode & (RATE_TUPLE_LENGTH - 1);
    }
}
