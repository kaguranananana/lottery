package com.anontokyo.lottery.domain.award.service.order;

import com.anontokyo.lottery.domain.award.model.req.AwardOrderReq;
import com.anontokyo.lottery.domain.award.model.res.AwardOrderResult;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;

import java.util.List;

/**
 * 奖品订单领域服务。
 */
public interface IAwardOrder {

    /**
     * 完成中奖单落库，并负责奖品库存结算与失败补偿。
     */
    AwardOrderResult placeOrder(AwardOrderReq awardOrderReq);

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
    List<InvoiceVO> scanInvoiceMqState(int dbCount, int tbCount, int limit);

    /**
     * Scan award stock settlement outbox.
     */
    List<AwardStockSettlementVO> scanAwardStockMqState(int dbCount, int tbCount, int limit);

    /** 分库元信息（供调度任务按分片遍历） */
    int shardingDbCount();

    /** 分表元信息 */
    int shardingTableCount();

    /**
     * Query invoice payload by order id.
     */
    InvoiceVO queryInvoiceByOrderId(String uId, Long orderId);

    /**
     * Whether stock settlement is already complete.
     */
    boolean isStockMqCompleted(String uId, Long orderId);
}
