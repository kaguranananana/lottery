package com.anontokyo.lottery.domain.award.repository;

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
    void updateUserAwardState(String uId, Long orderId, String awardId, Integer grantState);

    /**
     * 保存中奖订单。
     */
    void saveDrawOrder(DrawOrderVO drawOrder);

    /**
     * 更新发奖MQ状态。
     */
    void updateInvoiceMqState(String uId, Long orderId, Integer mqState);

    /**
     * 扫描待补偿的发奖MQ订单。
     */
    List<InvoiceVO> scanInvoiceMqState();

    /**
     * 查询订单是否已经完成发奖。
     */
    boolean isAwardCompleted(String uId, Long orderId);

}
