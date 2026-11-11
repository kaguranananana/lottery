package com.anontokyo.lottery.infrastructure.po;

import java.util.Date;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */
public class DrawConfig {

    // 自增ID
    private Long id;

    // 抽奖配置ID
    private Long drawConfigId;

    // 抽奖配置描述
    private String drawConfigDesc;


    // 发放奖品方式「1:即时、2:定时[含活动结束]、3:人工」
    private Integer grantType;

    // 发放奖品时间
    private Date scheduledAt;


    // 创建时间
    private Date createTime;

    // 修改时间
    private Date updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }
}
