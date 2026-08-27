package com.anontokyo.lottery.infrastructure.dao;

import com.anontokyo.lottery.infrastructure.po.StrategyDetail;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */

@Mapper
public interface IStrategyDetailDao {

    List<StrategyDetail> queryStrategyDetailList(Long strategyId);

    StrategyDetail queryStrategyDetail(StrategyDetail req);

    List<String> queryNoStockStrategyAwardList(Long strategyId);

    /**
     * 扣减库存
     * 
     * @param req
     * @return
     */
    int deductStock(StrategyDetail req);


    void insertList(List<StrategyDetail> strategyDetailList);

}
