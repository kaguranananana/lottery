package com.anontokyo.lottery.domain.activity.model.vo;

import java.util.Date;
import java.util.List;

/**
 * @description: 策略信息配置
 * @author：anontokyo
 * @date: 2026/1/12
 */
public class DrawConfigVO {

    /**
     * 抽奖配置ID
     */
    private Long drawConfigId;

    /**
     * 抽奖配置描述
     */
    private String drawConfigDesc;


    /**
     * 发放奖品方式「1:即时、2:定时[含活动结束]、3:人工」
     */
    private Integer grantType;

    /**
     * 计划发奖时间。
     */
    private Date scheduledAt;


    /**
     * 策略详情配置
     */
    private List<DrawConfigPrizeVO> drawConfigPrizeList;

    public Long getDrawConfigId() {
        return drawConfigId;
    }

    public void setDrawConfigId(Long drawConfigId) {
        this.drawConfigId = drawConfigId;
    }

    public String getDrawConfigDesc() {
        return drawConfigDesc;
    }

    public void setDrawConfigDesc(String drawConfigDesc) {
        this.drawConfigDesc = drawConfigDesc;
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

    public List<DrawConfigPrizeVO> getDrawConfigPrizeList() {
        return drawConfigPrizeList;
    }

    public void setDrawConfigPrizeList(List<DrawConfigPrizeVO> drawConfigPrizeList) {
        this.drawConfigPrizeList = drawConfigPrizeList;
    }

    @Override
    public String toString() {
        return "DrawConfigVO{" +
                "drawConfigId=" + drawConfigId +
                ", drawConfigDesc='" + drawConfigDesc + '\'' +
                ", grantType=" + grantType +
                ", scheduledAt=" + scheduledAt +
                '}';
    }

}
