package cn.itedus.lottery.domain.strategy.service.algorithm;


import cn.itedus.lottery.domain.strategy.model.vo.AwardRateInfo;

import java.util.List;

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


}
