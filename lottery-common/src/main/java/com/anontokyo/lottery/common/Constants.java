package com.anontokyo.lottery.common;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */
public class Constants {

    public enum ResponseCode {
        SUCCESS("0000", "成功"),
        UN_ERROR("0001", "未知失败"),
        ILLEGAL_PARAMETER("0002", "非法参数"),
        INDEX_DUP("0003", "主键冲突"),
        NO_UPDATE("0004", "SQL操作无更新"),
        LOSING_DRAW("D001", "未中奖"),
        NOT_CONSUMED_TAKE("D003", "未消费活动领取记录"),
        DRAW_IN_PROGRESS("D006", "抽奖处理中，请使用原请求号重试"),
        UNFINISHED_DRAW("D007", "存在未完成抽奖，请恢复原请求"),
        TAKE_STATE_ERROR("D008", "参与记录归属或状态异常，请联系服务端核查");


        private String code;
        private String info;

        ResponseCode(String code, String info) {
            this.code = code;
            this.info = info;
        }

        public String getCode() {
            return code;
        }

        public String getInfo() {
            return info;
        }

    }

    /**
     * 抽奖结果状态
     */
    public enum DrawState {

        FAIL(0, "未中奖"),

        SUCCESS(1, "已中奖"),

        Cover(2, "兜底奖品");

        private Integer code;
        private String info;

        DrawState(Integer code, String info) {
            this.info = info;
            this.code = code;
        }

        public Integer getCode() {
            return code;
        }

        public void setCode(Integer code) {
            this.code = code;
        }

        public String getInfo() {
            return info;
        }

        public void setInfo(String info) {
            this.info = info;
        }
    }

    /**
     * 发奖状态 : 0 等待发奖, 1 发奖成功, 2 发奖失败
     */
    public enum AwardState {

        WAIT(0, "等待发奖"),
        SUCCESS(1, "发奖成功"),
        FAILURE(2, "发奖失败");

        private Integer code;
        private String info;

        AwardState(Integer code, String info) {
            this.code = code;
            this.info = info;
        }

        public Integer getCode() {
            return code;
        }

        public void setCode(Integer code) {
            this.code = code;
        }

        public String getInfo() {
            return info;
        }

        public void setInfo(String info) {
            this.info = info;
        }
    }

    /**
     * 奖品类型（1:文字描述、2:兑换码、3:优惠券、4:实物奖品）
     */
    public enum AwardType {
        /**
         * 文字描述
         */
        DESC(1, "文字描述"),
        /**
         * 兑换码
         */
        RedeemCodeGoods(2, "兑换码"),
        /**
         * 优惠券
         */
        CouponGoods(3, "优惠券"),
        /**
         * 实物奖品
         */
        PhysicalGoods(4, "实物奖品");

        private Integer code;
        private String info;

        AwardType(Integer code, String info) {
            this.code = code;
            this.info = info;
        }

        public Integer getCode() {
            return code;
        }

        public void setCode(Integer code) {
            this.code = code;
        }

        public String getInfo() {
            return info;
        }

        public void setInfo(String info) {
            this.info = info;
        }
    }

    /**
     * 活动状态（保留运行时真实使用的状态）：
     * 1草稿(DRAFT)、4就绪(READY)、5运行(RUNNING)、7关闭(CLOSED)。
     */
    public enum ActivityState {

        /** 1：草稿 */
        DRAFT(1, "草稿"),
        /** 4：就绪，等待启动 */
        READY(4, "就绪"),
        /** 5：运行(活动中) */
        RUNNING(5, "运行"),
        /** 7：关闭 */
        CLOSED(7, "关闭");

        private Integer code;
        private String info;

        ActivityState(Integer code, String info) {
            this.code = code;
            this.info = info;
        }

        public Integer getCode() {
            return code;
        }

        public void setCode(Integer code) {
            this.code = code;
        }

        public String getInfo() {
            return info;
        }

        public void setInfo(String info) {
            this.info = info;
        }
    }

    /**
     * 活动单使用状态 0未使用、1已使用
     */
    public enum TaskState {

        NO_USED(0, "未使用"),
        USED(1, "已使用");

        private Integer code;
        private String info;

        TaskState(Integer code, String info) {
            this.code = code;
            this.info = info;
        }

        public Integer getCode() {
            return code;
        }

        public void setCode(Integer code) {
            this.code = code;
        }

        public String getInfo() {
            return info;
        }

        public void setInfo(String info) {
            this.info = info;
        }
    }

    /**
     * 发奖状态 0初始、1完成、2失败
     */
    public enum AwardFulfillmentStatus {

        INIT(0, "初始"),
        COMPLETE(1, "完成"),
        FAIL(2, "失败");

        private Integer code;
        private String info;

        AwardFulfillmentStatus(Integer code, String info) {
            this.code = code;
            this.info = info;
        }

        public Integer getCode() {
            return code;
        }

        public void setCode(Integer code) {
            this.code = code;
        }

        public String getInfo() {
            return info;
        }

        public void setInfo(String info) {
            this.info = info;
        }
    }

    /**
     * 消息投递状态（0初始待投递、1消费完成、2投递失败待重试、3已投递等待消费结果）
     */
    public enum FulfillmentEventStatus {
        INIT(0), COMPLETE(1), FAIL(2), SENT(3);

        private final Integer code;

        FulfillmentEventStatus(int code) {
            this.code = code;
        }

        public Integer getCode() {
            return code;
        }
    }

    /** 库存消息投递、结算事实与人工对账状态。 */
    public enum StockSettlementEventStatus {
        INIT(0), COMPLETE(1), FAIL(2), SENT(3), BLOCKED(4);

        private final Integer code;

        StockSettlementEventStatus(int code) {
            this.code = code;
        }

        public Integer getCode() {
            return code;
        }
    }

    public static final class RedisKey {
        // 配置奖品库存 Key
        private static final String LOTTERY_ACTIVITY_PRIZE_STOCK_COUNT = "lottery_activity_prize_stock_count_";

        public static String KEY_LOTTERY_ACTIVITY_PRIZE_STOCK_COUNT(Long activityId, String awardId) {
            return LOTTERY_ACTIVITY_PRIZE_STOCK_COUNT + activityId + "_" + awardId;
        }

        // Unconsumed user take-order cache key
        private static final String LOTTERY_USER_TAKE_NO_CONSUMED = "lottery_user_take_no_consumed_";

        public static String KEY_LOTTERY_USER_TAKE_NO_CONSUMED(Long activityId, String uId) {
            return LOTTERY_USER_TAKE_NO_CONSUMED + activityId + "_" + uId;
        }

        // Activity config cache key
        private static final String LOTTERY_CACHE_ACTIVITY = "lottery_cache_activity_v3_";

        public static String KEY_LOTTERY_CACHE_ACTIVITY(Long activityId) {
            return LOTTERY_CACHE_ACTIVITY + activityId;
        }

        // 活动奖池快照使用独立缓存前缀，旧结构可自然过期。
        private static final String LOTTERY_CACHE_ACTIVITY_PRIZE_SNAPSHOT = "lottery_cache_activity_prize_snapshot_";

        public static String KEY_LOTTERY_CACHE_ACTIVITY_PRIZE_SNAPSHOT(Long activityId) {
            return LOTTERY_CACHE_ACTIVITY_PRIZE_SNAPSHOT + activityId;
        }


        // User-activity draw inflight key
        private static final String LOTTERY_DRAW_IN_FLIGHT = "lottery_draw_inflight_";

        public static String KEY_LOTTERY_DRAW_IN_FLIGHT(Long activityId, String uId) {
            return LOTTERY_DRAW_IN_FLIGHT + activityId + "_" + uId;
        }

    }

}
