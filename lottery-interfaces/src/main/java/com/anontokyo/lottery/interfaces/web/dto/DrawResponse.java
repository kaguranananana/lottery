package com.anontokyo.lottery.interfaces.web.dto;

/**
 * @description: HTTP 抽奖响应
 * @author：anontokyo
 */
public class DrawResponse {

    private String code;
    private String info;
    private String requestId;
    private Long takeId;
    /** 被阻塞时待恢复的原请求号，不表示当前请求取得该参与记录。 */
    private String pendingRequestId;

    public String getPendingRequestId() { return pendingRequestId; }
    public void setPendingRequestId(String pendingRequestId) { this.pendingRequestId = pendingRequestId; }
    private Long orderId;
    private boolean win;
    private AwardVO award;

    public static class AwardVO {
        private String awardId;
        private Integer awardType;
        private String awardName;
        private String awardContent;
        private Integer grantType;
        private String grantDate;

        public String getAwardId() { return awardId; }
        public void setAwardId(String awardId) { this.awardId = awardId; }
        public Integer getAwardType() { return awardType; }
        public void setAwardType(Integer awardType) { this.awardType = awardType; }
        public String getAwardName() { return awardName; }
        public void setAwardName(String awardName) { this.awardName = awardName; }
        public String getAwardContent() { return awardContent; }
        public void setAwardContent(String awardContent) { this.awardContent = awardContent; }
        public Integer getGrantType() { return grantType; }
        public void setGrantType(Integer grantType) { this.grantType = grantType; }
        public String getGrantDate() { return grantDate; }
        public void setGrantDate(String grantDate) { this.grantDate = grantDate; }
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getInfo() { return info; }
    public void setInfo(String info) { this.info = info; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Long getTakeId() { return takeId; }
    public void setTakeId(Long takeId) { this.takeId = takeId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public boolean isWin() { return win; }
    public void setWin(boolean win) { this.win = win; }
    public AwardVO getAward() { return award; }
    public void setAward(AwardVO award) { this.award = award; }
}
