package com.anontokyo.lottery.domain.support.lock;

/**
 * Guards an activity draw process from duplicate in-flight execution.
 * 锁值使用调用方生成的随机 token：仅属主可释放（Lua compare-and-delete）。
 */
public interface IActivityDrawProcessGuard {

    /**
     * Claim an in-flight draw slot for the specified user and activity.
     *
     * @return true if the slot is claimed successfully, false if another draw is already in progress
     */
    boolean tryClaim(Long activityId, String uId, String token);

    /**
     * Release the in-flight draw slot; only succeeds when the stored value equals token.
     */
    void release(Long activityId, String uId, String token);
}
