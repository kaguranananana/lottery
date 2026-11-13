package com.anontokyo.lottery.domain.activity.model.res;

import com.anontokyo.lottery.common.Result;

/**
 * @description: 活动参与结果
 * @author：anontokyo
 * @date: 2026/1/17
 */
public class PartakeResult extends Result {

    /**
     * 活动领取id
     */
    private Long takeId;
    /** 被阻塞时待恢复的原请求号，不表示当前请求取得该参与记录。 */
    private String pendingRequestId;

    public String getPendingRequestId() { return pendingRequestId; }
    public void setPendingRequestId(String pendingRequestId) { this.pendingRequestId = pendingRequestId; }

    public Long getTakeId() {
        return takeId;
    }

    public void setTakeId(Long takeId) {
        this.takeId = takeId;
    }

    public PartakeResult(String code, String info) {
        super(code, info);
    }

}
