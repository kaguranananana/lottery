package com.anontokyo.lottery.infrastructure.dao;

import cn.bugstack.middleware.db.router.annotation.DBRouter;
import cn.bugstack.middleware.db.router.annotation.DBRouterStrategy;
import com.anontokyo.lottery.infrastructure.po.UserStrategyExport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/16
 */
@Mapper
@DBRouterStrategy(splitTable = true)
public interface IUserStrategyExportDao {
    /**
     * 新增数据
     * @param userStrategyExport 用户策略
     */
    @DBRouter(key = "uId")
    void insert(UserStrategyExport userStrategyExport);

    /**
     * 查询数据
     * @param uId 用户ID
     * @return 用户策略
     */
    @DBRouter
    UserStrategyExport queryUserStrategyExportByUId(String uId);

    /**
     * 按订单唯一ID查询用户领奖单。
     */
    @DBRouter(key = "uId")
    UserStrategyExport queryUserStrategyExportByUuid(UserStrategyExport userStrategyExport);

    /** 回放查询必须按用户同时路由库与订单分表。 */
    @DBRouter(key = "uId")
    UserStrategyExport queryUserStrategyExportByTakeId(UserStrategyExport userStrategyExport);

    /**
     * 更新发奖状态
     * @param userStrategyExport
     */
    @DBRouter
    int markUserAwardGranted(UserStrategyExport userStrategyExport);

    /**
     * 按消息事件条件更新发奖状态，完成事件还须满足 grant_state=1。
     * @return 1 表示状态推进；0 表示重复事件或不满足源状态条件
     */
    @DBRouter
    int updateInvoiceMqState(UserStrategyExport userStrategyExport);

    /**
     * 按发送或完成事件条件更新库存消息状态，不允许覆盖 COMPLETE。
     * @return 1 表示状态推进；0 表示重复事件或不满足源状态条件
     */
    @DBRouter
    int updateStockMqState(UserStrategyExport userStrategyExport);

    /** 消费失败按 uId 路由，以条件更新保留并发完成的终态。 */
    @DBRouter(key = "uId")
    int markStockSettlementFailed(UserStrategyExport userStrategyExport);

    List<UserStrategyExport> scanInvoiceMqState(int limit);

    List<UserStrategyExport> scanAwardStockMqState(@Param("limit") Integer limit);
}
