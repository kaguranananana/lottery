package com.anontokyo.lottery.domain.strategy.model.vo;

/**
 * Redis-based strategy award stock deduction result.
 */
public class StrategyAwardStockDeductResultVO {

    public enum Status {
        SUCCESS,
        SOLD_OUT,
        LOCK_FAILED
    }

    private final Status status;

    private StrategyAwardStockDeductResultVO(Status status) {
        this.status = status;
    }

    public static StrategyAwardStockDeductResultVO success() {
        return new StrategyAwardStockDeductResultVO(Status.SUCCESS);
    }

    public static StrategyAwardStockDeductResultVO soldOut() {
        return new StrategyAwardStockDeductResultVO(Status.SOLD_OUT);
    }

    public static StrategyAwardStockDeductResultVO lockFailed() {
        return new StrategyAwardStockDeductResultVO(Status.LOCK_FAILED);
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
