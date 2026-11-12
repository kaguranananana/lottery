package com.anontokyo.lottery.infrastructure.dao;

import com.anontokyo.lottery.infrastructure.po.DrawOrder;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository.OutboxKind;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/16
 */
@Mapper
public interface IDrawOrderDao {
    /**
     * 新增数据
     * @param drawOrderRow 用户策略
     */
    void insert(DrawOrder drawOrderRow);

    /**
     * 查询数据
     * @param uId 用户ID
     * @return 用户策略
     */
    DrawOrder queryDrawOrderByUId(String uId);

    /**
     * 按订单唯一ID查询用户领奖单。
     */
    DrawOrder queryDrawOrderByOrderId(DrawOrder drawOrderRow);

    /** 回放查询按用户和参与号核对订单归属。 */
    DrawOrder queryDrawOrderByTakeId(DrawOrder drawOrderRow);

    /**
     * 更新发奖状态
     * @param drawOrderRow
     */
    int markUserAwardGranted(DrawOrder drawOrderRow);

    /**
     * 按消息事件条件更新发奖状态，完成事件还须满足 award_fulfillment_status=1。
     * @return 1 表示状态推进；0 表示重复事件或不满足源状态条件
     */
    int updateFulfillmentEventStatus(DrawOrder drawOrderRow);

    /**
     * 按发送或完成事件条件更新库存消息状态，不允许覆盖 COMPLETE。
     * @return 1 表示状态推进；0 表示重复事件或不满足源状态条件
     */
    int updateStockSettlementEventStatus(DrawOrder drawOrderRow);

    /** 消费失败按用户与订单定位，以条件更新保留并发完成的终态。 */
    int markStockSettlementFailed(DrawOrder drawOrderRow);

    List<DrawOrder> scanStockSettlementEventStatus(@Param("limit") int limit);

    List<DrawOrder> scanFulfillmentEventStatus(@Param("limit") int limit);

    int claimOutbox(@Param("kind") OutboxKind kind, @Param("orderId") long orderId,
            @Param("attempt") int attempt,
            @Param("leaseSeconds") int leaseSeconds);

    int finishOutbox(@Param("kind") OutboxKind kind, @Param("orderId") long orderId,
            @Param("attempt") int attempt, @Param("sent") boolean sent,
            @Param("delaySeconds") int delaySeconds);
}
