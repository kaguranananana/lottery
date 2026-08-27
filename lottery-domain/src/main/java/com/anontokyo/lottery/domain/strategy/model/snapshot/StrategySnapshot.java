package com.anontokyo.lottery.domain.strategy.model.snapshot;

import com.anontokyo.lottery.domain.strategy.model.vo.AwardBriefVO;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 一次抽奖所需的低变更策略快照：
 * strategy 配置、各奖品信息与其整数权重（BigDecimal 概率 × 1,000,000 取整）。
 * 数组按累计权重升序排列，其中权重占比 = 概率总和与 100% 的差值的部分以 {@code awardId == null} 表示未中奖(NO_PRIZE)，
 * 保证剩余概率不会被重新分配给任何真实奖品。
 */
public class StrategySnapshot {

    private Long strategyId;

    /** 原 strategy.strategy_mode 透传（DB 列兼容），仅落单展示用 */
    private Integer strategyMode;

    private Integer grantType;

    private Date grantDate;

    /** 与 cumulativeWeights 一一对应；null 表示未中奖 */
    private String[] awardIds;

    /** 权重前缀和，最后一个元素等于总权重 */
    private long[] cumulativeWeights;

    /** awardId -> 奖品详情 */
    private Map<String, AwardBriefVO> awardInfos;

    public Long getStrategyId() {
        return strategyId;
    }

    public void setStrategyId(Long strategyId) {
        this.strategyId = strategyId;
    }

    public Integer getStrategyMode() {
        return strategyMode;
    }

    public void setStrategyMode(Integer strategyMode) {
        this.strategyMode = strategyMode;
    }

    public Integer getGrantType() {
        return grantType;
    }

    public void setGrantType(Integer grantType) {
        this.grantType = grantType;
    }

    public Date getGrantDate() {
        return grantDate;
    }

    public void setGrantDate(Date grantDate) {
        this.grantDate = grantDate;
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
