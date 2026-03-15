package com.anontokyo.lottery.domain.support.lock;

/**
 * Guards invoice MQ processing from duplicate concurrent execution.
 */
public interface IInvoiceMqProcessGuard {

    /**
     * Claim the processing right for an invoice message.
     *
     * @return true if the caller can process the message
     */
    boolean tryClaimProcessing(Long orderId);

    /**
     * Release the processing right for an invoice message.
     */
    void releaseProcessing(Long orderId);
}
