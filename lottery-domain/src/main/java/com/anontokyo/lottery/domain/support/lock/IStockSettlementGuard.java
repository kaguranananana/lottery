package com.anontokyo.lottery.domain.support.lock;

/**
 * Guards drawConfig-award-stock MQ processing and dispatch markers.
 */
public interface IStockSettlementGuard {

    /**
     * Claim the processing right for a drawConfig-award-stock message.
     */
    boolean tryClaimProcessing(Long orderId);

    /**
     * Release the processing right for a drawConfig-award-stock message.
     */
    void releaseProcessing(Long orderId);

    /**
     * Claim the dispatch marker for the outbox dispatcher.
     */
    boolean tryClaimDispatch(Long orderId);

    /**
     * Clear the dispatch marker for the outbox dispatcher.
     */
    void clearDispatchMarker(Long orderId);
}
