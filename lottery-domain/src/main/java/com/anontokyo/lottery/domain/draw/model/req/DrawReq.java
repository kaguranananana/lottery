package com.anontokyo.lottery.domain.draw.model.req;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */
public class DrawReq {

    // 用户ID
    private String uId;

    // 抽奖配置ID
    private Long drawConfigId;

    public DrawReq() {
    }

    public DrawReq(String uId, Long drawConfigId) {
        this.uId = uId;
        this.drawConfigId = drawConfigId;
    }

    public String getuId() {
        return uId;
    }

    public void setuId(String uId) {
        this.uId = uId;
    }

    public Long getDrawConfigId() {
        return drawConfigId;
    }

    public void setDrawConfigId(Long drawConfigId) {
        this.drawConfigId = drawConfigId;
    }

}
