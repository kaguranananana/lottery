package com.anontokyo.lottery.domain.award.repository;

import com.anontokyo.lottery.common.Constants;
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
     * CAS 标记发奖完成（grant_state -> SUCCESS，且仅当未完成时生效）。
     * @return true 表示本次调用完成了首次标记；false 表示订单此前已完成（幂等短路）
     */
    boolean markUserAwardGranted(String uId, Long orderId, String awardId);

    /**
     * 条件推进发奖 MQ 状态：发送回调写 SENT/FAIL，消费确认发奖后写 COMPLETE。
     */
    void updateInvoiceMqState(String uId, Long orderId, Integer mqState);

    /**
     * 条件推进库存 MQ 状态：发送失败不覆盖 SENT，所有发送回调不覆盖 COMPLETE。
     */
    void updateStockMqState(String uId, Long orderId, Integer stockMqState);

    /** 库存消费失败允许 INIT/SENT 转 FAIL，COMPLETE 保持不变。 */
    void markStockSettlementFailed(String uId, Long orderId);

    /**
     * 扫描待补偿的发奖MQ订单。
     */
    List<InvoiceVO> scanInvoiceMqState(int limit);

    List<InvoiceVO> scanInvoiceMqStateByShard(int dbIdx, int tbIdx, int limit);

    List<AwardStockSettlementVO> scanAwardStockMqStateByShard(int dbIdx, int tbIdx, int limit);

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
     * 保存中奖订单并锁定对应参与记录（按 uId 路由 + 本地事务由 infrastructure 承担）。
     * @return SUCCESS / NO_UPDATE(锁定失败) / INDEX_DUP(订单重复)
     */
    Constants.ResponseCode saveDrawOrderWithTakeLock(DrawOrderVO drawOrder);

    /** 分库元信息（供调度任务分片遍历用） */
    int shardingDbCount();

    /** 分表元信息 */
    int shardingTableCount();

    /**
     * Whether stock settlement is already complete.
     */
    boolean isStockMqCompleted(String uId, Long orderId);

}
