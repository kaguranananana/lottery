package com.anontokyo.lottery.domain.draw.model.vo;

/**
 * Redis-based drawConfig award stock deduction result.
 */
public class DrawConfigPrizeStockDeductResultVO {

    public enum Status {
        SUCCESS,
        SOLD_OUT,
        LOCK_FAILED
    }

    private final Status status;

    private DrawConfigPrizeStockDeductResultVO(Status status) {
        this.status = status;
    }

    public static DrawConfigPrizeStockDeductResultVO success() {
        return new DrawConfigPrizeStockDeductResultVO(Status.SUCCESS);
    }

    public static DrawConfigPrizeStockDeductResultVO soldOut() {
        return new DrawConfigPrizeStockDeductResultVO(Status.SOLD_OUT);
    }

    public static DrawConfigPrizeStockDeductResultVO lockFailed() {
        return new DrawConfigPrizeStockDeductResultVO(Status.LOCK_FAILED);
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
