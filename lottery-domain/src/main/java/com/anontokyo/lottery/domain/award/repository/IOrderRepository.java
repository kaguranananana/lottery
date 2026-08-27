package com.anontokyo.lottery.domain.award.repository;

import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.DrawOrderVO;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;

import java.util.List;

/**
 * @description: 奖品表仓储服务接口
 * @author：anontokyo
 * @date: 2026/1/10
 */
public interface IOrderRepository {

    /**
     * 更新奖品的发放状态
     * @param uId
     * @param orderId
     * @param awardId
     * @param grantState
     */
    /**
     * CAS 标记发奖完成（grant_state -> SUCCESS，且仅当未完成时生效）。
     * @return true 表示本次调用完成了首次标记；false 表示订单此前已完成（幂等短路）
     */
    boolean markUserAwardGranted(String uId, Long orderId, String awardId);

    /**
     * 保存中奖订单。
     */
    void saveDrawOrder(DrawOrderVO drawOrder);

    /**
     * 更新发奖MQ状态。
     */
    void updateInvoiceMqState(String uId, Long orderId, Integer mqState);

    /**
     * Update award stock settlement MQ state.
     */
    void updateStockMqState(String uId, Long orderId, Integer stockMqState);

    /**
     * 扫描待补偿的发奖MQ订单。
     */
    List<InvoiceVO> scanInvoiceMqState(int limit);

    /**
     * Scan award stock settlement outbox.
     */
    List<AwardStockSettlementVO> scanAwardStockMqState(int limit);

    /**
     * 查询订单是否已经完成发奖。
     */
    boolean isAwardCompleted(String uId, Long orderId);

    /**
     * Query invoice payload by order id.
     */
    InvoiceVO queryInvoiceByOrderId(String uId, Long orderId);

    /**
     * 按 take 关联查询中奖订单，用于 requestId 幂等回放；无订单返回 null。
     */
    InvoiceVO queryOrderByTakeId(String uId, Long takeId);

    /**
     * Whether stock settlement is already complete.
     */
    boolean isStockMqCompleted(String uId, Long orderId);

}
