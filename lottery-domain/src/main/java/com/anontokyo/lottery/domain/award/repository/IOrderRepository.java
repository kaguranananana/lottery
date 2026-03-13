package com.anontokyo.lottery.domain.award.repository;

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
     * 查询订单是否已经完成发奖。
     */
    boolean isAwardCompleted(String uId, Long orderId);

}
