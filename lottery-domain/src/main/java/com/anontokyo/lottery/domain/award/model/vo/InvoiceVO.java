package com.anontokyo.lottery.domain.award.model.vo;

import com.anontokyo.lottery.domain.award.model.vo.ShippingAddress;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/29
 */
public class InvoiceVO {

    /** 用户ID */
    private String uId;

    /** 抽奖单号 ID */
    private Long orderId;

    /** 奖品ID */
    private String awardId;

    /**
     * 奖品类型（1:文字描述、2:兑换码、3:优惠券、4:实物奖品）
     */
    private Integer awardType;

    /** 奖品名称 */
    private String awardName;

    /** 奖品内容「描述、奖品码、sku」 */
    private String awardContent;

    /** 四级送货地址（只有实物类商品需要地址） */
    private ShippingAddress shippingAddress;

    /** 扩展信息，用于一些个性商品发放所需要的透传字段内容 */
    private String extInfo;

    public String getAwardContent() {
        return awardContent;
    }

    public void setAwardContent(String awardContent) {
        this.awardContent = awardContent;
    }

    public String getuId() {
        return uId;
    }

    public void setuId(String uId) {
        this.uId = uId;
    }

    public ShippingAddress getShippingAddress() {
        return shippingAddress;
    }

    public void setShippingAddress(ShippingAddress shippingAddress) {
        this.shippingAddress = shippingAddress;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getExtInfo() {
        return extInfo;
    }

    public void setExtInfo(String extInfo) {
        this.extInfo = extInfo;
    }

    public Integer getAwardType() {
        return awardType;
    }

    public void setAwardType(Integer awardType) {
        this.awardType = awardType;
    }

    public String getAwardName() {
        return awardName;
    }

    public void setAwardName(String awardName) {
        this.awardName = awardName;
    }

    public String getAwardId() {
        return awardId;
    }

    public void setAwardId(String awardId) {
        this.awardId = awardId;
    }


    @Override
    public String toString() {
        return "InvoiceVO{" +
                "awardContent='" + awardContent + '\'' +
                ", uId='" + uId + '\'' +
                ", orderId=" + orderId +
                ", awardId='" + awardId + '\'' +
                ", awardType=" + awardType +
                ", awardName='" + awardName + '\'' +
                ", shippingAddress=" + shippingAddress +
                ", extInfo='" + extInfo + '\'' +
                '}';
    }
}
