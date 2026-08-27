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

    UserStrategyExport queryUserStrategyExportByTakeId(UserStrategyExport userStrategyExport);

    /**
     * 更新发奖状态
     * @param userStrategyExport
     */
    @DBRouter
    void updateUserAwardState(UserStrategyExport userStrategyExport);

    /**
     * 更新发送MQ状态
     * @param userStrategyExport
     */
    @DBRouter
    void updateInvoiceMqState(UserStrategyExport userStrategyExport);

    /**
     * Update award stock settlement MQ state.
     */
    @DBRouter
    void updateStockMqState(UserStrategyExport userStrategyExport);

    List<UserStrategyExport> scanInvoiceMqState();

    List<UserStrategyExport> scanAwardStockMqState(@Param("limit") Integer limit);
}
