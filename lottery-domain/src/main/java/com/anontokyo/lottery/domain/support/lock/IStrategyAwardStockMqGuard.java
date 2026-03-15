package com.anontokyo.lottery.domain.support.lock;

/**
 * Guards strategy-award-stock MQ processing and dispatch markers.
 */
public interface IStrategyAwardStockMqGuard {

    /**
     * Claim the processing right for a strategy-award-stock message.
     */
    boolean tryClaimProcessing(Long orderId);

    /**
     * Release the processing right for a strategy-award-stock message.
     */
    void releaseProcessing(Long orderId);

    /**
     * Check whether the message has already been processed.
     */
    boolean isProcessed(Long orderId);

    /**
     * Mark the message as processed.
     */
    void markProcessed(Long orderId);

    /**
     * Claim the dispatch marker for the outbox dispatcher.
     */
    boolean tryClaimDispatch(Long orderId);

    /**
     * Clear the dispatch marker for the outbox dispatcher.
     */
    void clearDispatchMarker(Long orderId);
}
