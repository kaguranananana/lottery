package com.anontokyo.lottery.domain.draw.model.snapshot;

import com.anontokyo.lottery.domain.draw.model.vo.AwardBriefVO;

import java.util.Map;

/**
 * 一次抽奖所需的低变更活动奖池快照：
 * 活动奖品信息与其整数权重（BigDecimal 概率 × 1,000,000 取整）。
 * 数组按累计权重升序排列，其中权重占比 = 概率总和与 100% 的差值的部分以 {@code awardId == null} 表示未中奖(NO_PRIZE)，
 * 保证剩余概率不会被重新分配给任何真实奖品。
 */
public class ActivityPrizeSnapshot {

    private Long activityId;

    /** 与 cumulativeWeights 一一对应；null 表示未中奖 */
    private String[] awardIds;

    /** 权重前缀和，最后一个元素等于总权重 */
    private long[] cumulativeWeights;

    /** awardId -> 奖品详情 */
    private Map<String, AwardBriefVO> awardInfos;

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }

    public String[] getAwardIds() {
        return awardIds;
    }

    public void setAwardIds(String[] awardIds) {
        this.awardIds = awardIds;
    }

    public long[] getCumulativeWeights() {
        return cumulativeWeights;
    }

    public void setCumulativeWeights(long[] cumulativeWeights) {
        this.cumulativeWeights = cumulativeWeights;
    }

    public Map<String, AwardBriefVO> getAwardInfos() {
        return awardInfos;
    }

    public void setAwardInfos(Map<String, AwardBriefVO> awardInfos) {
        this.awardInfos = awardInfos;
    }

    public long totalWeight() {
        return cumulativeWeights[cumulativeWeights.length - 1];
    }
}
