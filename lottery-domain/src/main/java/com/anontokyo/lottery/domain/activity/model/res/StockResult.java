package com.anontokyo.lottery.domain.activity.model.res;

import com.anontokyo.lottery.common.Result;

/**
 * Activity stock processing result.
 */
public class StockResult extends Result {

    /**
     * Remaining stock after processing.
     */
    private Integer stockSurplusCount;

    public StockResult(String code, String info) {
        super(code, info);
    }

    public StockResult(String code, String info, Integer stockSurplusCount) {
        super(code, info);
        this.stockSurplusCount = stockSurplusCount;
    }

    public Integer getStockSurplusCount() {
        return stockSurplusCount;
    }

    public void setStockSurplusCount(Integer stockSurplusCount) {
        this.stockSurplusCount = stockSurplusCount;
    }
}
