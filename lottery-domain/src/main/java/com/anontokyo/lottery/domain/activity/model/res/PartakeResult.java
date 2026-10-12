package com.anontokyo.lottery.domain.activity.model.res;

import com.anontokyo.lottery.common.Result;

/**
 * @description: 活动参与结果
 * @author：anontokyo
 * @date: 2026/1/17
 */
public class PartakeResult extends Result {
    /**
     * 活动策略ID
     */
    private Long strategyId;

    /**
     * 活动领取id
     */
    private Long takeId;
    /** 被阻塞时待恢复的原请求号，不表示当前请求取得该参与记录。 */
    private String pendingRequestId;

    public String getPendingRequestId() { return pendingRequestId; }
    public void setPendingRequestId(String pendingRequestId) { this.pendingRequestId = pendingRequestId; }
    /** 库存 */
    private Integer stockCount;
    /** activity 库存剩余 */
    private Integer stockSurplusCount;

    public Long getTakeId() {
        return takeId;
    }

    public void setTakeId(Long takeId) {
        this.takeId = takeId;
    }

    public Long getStrategyId() {
        return strategyId;
    }

    public void setStrategyId(Long strategyId) {
        this.strategyId = strategyId;
    }

    public PartakeResult(String code, String info) {
        super(code, info);
    }

    public Integer getStockCount() {
        return stockCount;
    }

    public void setStockCount(Integer stockCount) {
        this.stockCount = stockCount;
    }

    public Integer getStockSurplusCount() {
        return stockSurplusCount;
    }

    public void setStockSurplusCount(Integer stockSurplusCount) {
        this.stockSurplusCount = stockSurplusCount;
    }
}
