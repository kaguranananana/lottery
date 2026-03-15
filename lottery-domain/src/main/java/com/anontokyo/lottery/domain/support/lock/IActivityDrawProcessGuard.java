package com.anontokyo.lottery.domain.support.lock;

/**
 * Guards an activity draw process from duplicate in-flight execution.
 */
public interface IActivityDrawProcessGuard {

    /**
     * Claim an in-flight draw slot for the specified user and activity.
     *
     * @return true if the slot is claimed successfully, false if another draw is already in progress
     */
    boolean tryClaim(Long activityId, String uId);

    /**
     * Release the in-flight draw slot for the specified user and activity.
     */
    void release(Long activityId, String uId);
}
