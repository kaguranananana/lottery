package com.anontokyo.lottery.domain.draw.model.aggregates;

import com.anontokyo.lottery.domain.draw.model.vo.DrawConfigBriefVO;
import com.anontokyo.lottery.domain.draw.model.vo.DrawConfigPrizeBriefVO;

import java.util.List;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */

public class DrawConfigAggregate {
    // 抽奖配置ID
    private Long drawConfigId;

    // 抽奖配置
    private DrawConfigBriefVO drawConfig;

    // 抽奖配置奖品
    private List<DrawConfigPrizeBriefVO> drawConfigPrizeList;

    public DrawConfigAggregate() {
    }

    public DrawConfigAggregate(Long drawConfigId, DrawConfigBriefVO drawConfig, List<DrawConfigPrizeBriefVO> drawConfigPrizeList) {
        this.drawConfigId = drawConfigId;
        this.drawConfig = drawConfig;
        this.drawConfigPrizeList = drawConfigPrizeList;
    }

    public DrawConfigBriefVO getDrawConfig() {
        return drawConfig;
    }

    public void setDrawConfig(DrawConfigBriefVO drawConfig) {
        this.drawConfig = drawConfig;
    }

    public List<DrawConfigPrizeBriefVO> getDrawConfigPrizeList() {
        return drawConfigPrizeList;
    }

    public void setDrawConfigPrizeList(List<DrawConfigPrizeBriefVO> drawConfigPrizeList) {
        this.drawConfigPrizeList = drawConfigPrizeList;
    }

    public Long getDrawConfigId() {
        return drawConfigId;
    }

    public void setDrawConfigId(Long drawConfigId) {
        this.drawConfigId = drawConfigId;
    }
}
