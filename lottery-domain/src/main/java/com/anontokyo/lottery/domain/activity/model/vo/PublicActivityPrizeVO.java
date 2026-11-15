package com.anontokyo.lottery.domain.activity.model.vo;

/** 公开奖池摘要，仅文字奖品带描述。 */
public final class PublicActivityPrizeVO {
    public final String awardId;
    public final String awardName;
    public final Integer awardType;
    public final String description;

    public PublicActivityPrizeVO(String awardId, String awardName, Integer awardType, String description) {
        this.awardId = awardId;
        this.awardName = awardName;
        this.awardType = awardType;
        this.description = description;
    }
}
