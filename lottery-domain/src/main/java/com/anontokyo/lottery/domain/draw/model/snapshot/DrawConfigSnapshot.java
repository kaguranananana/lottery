package com.anontokyo.lottery.domain.draw.model.snapshot;

import com.anontokyo.lottery.domain.draw.model.vo.AwardBriefVO;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 一次抽奖所需的低变更抽奖配置快照：
 * drawConfig 配置、各奖品信息与其整数权重（BigDecimal 概率 × 1,000,000 取整）。
 * 数组按累计权重升序排列，其中权重占比 = 概率总和与 100% 的差值的部分以 {@code awardId == null} 表示未中奖(NO_PRIZE)，
 * 保证剩余概率不会被重新分配给任何真实奖品。
 */
public class DrawConfigSnapshot {

    private Long drawConfigId;


    private Integer grantType;

    private Date scheduledAt;

    /** 与 cumulativeWeights 一一对应；null 表示未中奖 */
    private String[] awardIds;

    /** 权重前缀和，最后一个元素等于总权重 */
    private long[] cumulativeWeights;

    /** awardId -> 奖品详情 */
    private Map<String, AwardBriefVO> awardInfos;

    public Long getDrawConfigId() {
        return drawConfigId;
    }

    public void setDrawConfigId(Long drawConfigId) {
        this.drawConfigId = drawConfigId;
    }

    public Integer getGrantType() {
        return grantType;
    }

    public void setGrantType(Integer grantType) {
        this.grantType = grantType;
    }

    public Date getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Date scheduledAt) {
        this.scheduledAt = scheduledAt;
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
