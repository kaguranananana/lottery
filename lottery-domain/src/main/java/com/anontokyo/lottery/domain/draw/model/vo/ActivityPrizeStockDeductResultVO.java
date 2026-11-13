package com.anontokyo.lottery.domain.draw.model.vo;

/**
 * 活动奖品的 Redis 原子预扣结果；库存键缺失由仓储抛出异常。
 */
public class ActivityPrizeStockDeductResultVO {

    public enum Status {
        SUCCESS,
        SOLD_OUT
    }

    private final Status status;

    private ActivityPrizeStockDeductResultVO(Status status) {
        this.status = status;
    }

    public static ActivityPrizeStockDeductResultVO success() {
        return new ActivityPrizeStockDeductResultVO(Status.SUCCESS);
    }

    public static ActivityPrizeStockDeductResultVO soldOut() {
        return new ActivityPrizeStockDeductResultVO(Status.SOLD_OUT);
    }

    public Status getStatus() {
        return status;
    }

    public boolean isSuccess() {
        return Status.SUCCESS == status;
    }

    public boolean isSoldOut() {
        return Status.SOLD_OUT == status;
    }
}
