package com.anontokyo.lottery.application.process.draw.res;

import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.domain.strategy.model.vo.DrawAwardInfo;

/**
 * @description: 活动抽奖结果
 * @author：anontokyo
 * @date：2026/1/22
 */
public class DrawProcessResult extends Result {

    /** 参与记录ID（一旦领取即确定，重复请求返回同一 takeId） */
    private Long takeId;
    /** 被阻塞时待恢复的原请求号，不表示当前请求取得该参与记录。 */
    private String pendingRequestId;

    public String getPendingRequestId() { return pendingRequestId; }
    public void setPendingRequestId(String pendingRequestId) { this.pendingRequestId = pendingRequestId; }
    /** 中奖订单ID（仅中奖时有值） */
    private Long orderId;
    private DrawAwardInfo drawAwardInfo;

    public DrawProcessResult(String code, String info) {
        super(code, info);
    }

    public DrawProcessResult(String code, String info, DrawAwardInfo drawAwardInfo) {
        super(code, info);
        this.drawAwardInfo = drawAwardInfo;
    }

    public Long getTakeId() {
        return takeId;
    }

    public void setTakeId(Long takeId) {
        this.takeId = takeId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public DrawAwardInfo getDrawAwardInfo() {
        return drawAwardInfo;
    }

    public void setDrawAwardInfo(DrawAwardInfo drawAwardInfo) {
        this.drawAwardInfo = drawAwardInfo;
    }
}
