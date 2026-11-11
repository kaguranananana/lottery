package com.anontokyo.lottery.infrastructure.dao;

import com.anontokyo.lottery.infrastructure.po.AwardStockSettlement;
import org.apache.ibatis.annotations.Mapper;

/**
 * 结算账本 DAO。账本与 draw_config_prize 的扣减
 * 可以在同一个本地事务中执行。
 */
@Mapper
public interface IAwardStockSettlementDao {

    void insert(AwardStockSettlement settlement);
}
