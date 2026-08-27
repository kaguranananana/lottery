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
        OUT_OF_STOCK("D004", "活动无库存"),
        DRAW_IN_PROGRESS("D006", "抽奖处理中，请勿重复点击");


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
     * 抽奖策略模式: 总体概率, 单项概率
     */
    public enum StrategyMode {

        SINGLE(1, "单项概率"),
        ENTIRETY(2, "总体概率");

        private Integer code;
        private String info;

        StrategyMode(Integer code, String info) {
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
     * 1编辑(DRAFT)、4通过(READY)、5运行(RUNNING)、7关闭(CLOSED)
     * 历史 2提审/3撤审/6拒绝/8开启 无生产语义，见 docs/sql migration 注释。
     */
    public enum ActivityState {

        /** 1：编辑 */
        EDIT(1, "编辑"),
        /** 4：通过（就绪，可由调度进入运行） */
        PASS(4, "通过"),
        /** 5：运行(活动中) */
        DOING(5, "运行(活动中)"),
        /** 7：关闭 */
        CLOSE(7, "关闭");

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
    public enum GrantState {

        INIT(0, "初始"),
        COMPLETE(1, "完成"),
        FAIL(2, "失败");

        private Integer code;
        private String info;

        GrantState(Integer code, String info) {
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
     * 消息发送状态（0未发送、1发送成功、2发送失败）
     */
    public enum MQState {
        INIT(0, "初始"),
        COMPLETE(1, "完成"),
        FAIL(2, "失败");

        private Integer code;
        private String info;

        MQState(Integer code, String info) {
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


    public static final class RedisKey {
        // 抽奖活动库存Key
        private static final String LOTTERY_ACTIVITY_STOCK_COUNT = "lottery_activity_stock_count_";

        public static String KEY_LOTTERY_ACTIVITY_STOCK_COUNT(Long activityId) {
            return LOTTERY_ACTIVITY_STOCK_COUNT + activityId;
        }

        // 策略奖品库存 Key
        private static final String LOTTERY_STRATEGY_AWARD_STOCK_COUNT = "lottery_strategy_award_stock_count_";

        public static String KEY_LOTTERY_STRATEGY_AWARD_STOCK_COUNT(Long strategyId, String awardId) {
            return LOTTERY_STRATEGY_AWARD_STOCK_COUNT + strategyId + "_" + awardId;
        }

        // Unconsumed user take-order cache key
        private static final String LOTTERY_USER_TAKE_NO_CONSUMED = "lottery_user_take_no_consumed_";

        public static String KEY_LOTTERY_USER_TAKE_NO_CONSUMED(Long activityId, String uId) {
            return LOTTERY_USER_TAKE_NO_CONSUMED + activityId + "_" + uId;
        }

        // Activity config cache key
        private static final String LOTTERY_CACHE_ACTIVITY = "lottery_cache_activity_";

        public static String KEY_LOTTERY_CACHE_ACTIVITY(Long activityId) {
            return LOTTERY_CACHE_ACTIVITY + activityId;
        }

        // Strategy aggregate cache key
        private static final String LOTTERY_CACHE_STRATEGY_RICH = "lottery_cache_strategy_rich_";

        public static String KEY_LOTTERY_CACHE_STRATEGY_RICH(Long strategyId) {
            return LOTTERY_CACHE_STRATEGY_RICH + strategyId;
        }

        // Strategy no-stock award list cache key
        private static final String LOTTERY_CACHE_STRATEGY_NO_STOCK_AWARD = "lottery_cache_strategy_no_stock_award_";

        public static String KEY_LOTTERY_CACHE_STRATEGY_NO_STOCK_AWARD(Long strategyId) {
            return LOTTERY_CACHE_STRATEGY_NO_STOCK_AWARD + strategyId;
        }

        // Award detail cache key
        private static final String LOTTERY_CACHE_AWARD = "lottery_cache_award_";

        public static String KEY_LOTTERY_CACHE_AWARD(String awardId) {
            return LOTTERY_CACHE_AWARD + awardId;
        }

        // Strategy-award stock MQ dedup key
        private static final String LOTTERY_STRATEGY_AWARD_STOCK_MQ_DONE = "lottery_strategy_award_stock_mq_done_";

        public static String KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_DONE(Long orderId) {
            return LOTTERY_STRATEGY_AWARD_STOCK_MQ_DONE + orderId;
        }

        // Strategy-award stock MQ processing key
        private static final String LOTTERY_STRATEGY_AWARD_STOCK_MQ_PROCESSING = "lottery_strategy_award_stock_mq_processing_";

        public static String KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_PROCESSING(Long orderId) {
            return LOTTERY_STRATEGY_AWARD_STOCK_MQ_PROCESSING + orderId;
        }

        // Strategy-award stock MQ dispatch marker
        private static final String LOTTERY_STRATEGY_AWARD_STOCK_MQ_DISPATCHING = "lottery_strategy_award_stock_mq_dispatching_";

        public static String KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_DISPATCHING(Long orderId) {
            return LOTTERY_STRATEGY_AWARD_STOCK_MQ_DISPATCHING + orderId;
        }

        // Activity-partake MQ dispatch marker
        private static final String LOTTERY_ACTIVITY_PARTAKE_MQ_DISPATCHING = "lottery_activity_partake_mq_dispatching_";

        public static String KEY_LOTTERY_ACTIVITY_PARTAKE_MQ_DISPATCHING(Long takeId) {
            return LOTTERY_ACTIVITY_PARTAKE_MQ_DISPATCHING + takeId;
        }

        // Invoice MQ processing key
        private static final String LOTTERY_INVOICE_MQ_PROCESSING = "lottery_invoice_mq_processing_";

        public static String KEY_LOTTERY_INVOICE_MQ_PROCESSING(Long orderId) {
            return LOTTERY_INVOICE_MQ_PROCESSING + orderId;
        }

        // User-activity draw inflight key
        private static final String LOTTERY_DRAW_IN_FLIGHT = "lottery_draw_inflight_";

        public static String KEY_LOTTERY_DRAW_IN_FLIGHT(Long activityId, String uId) {
            return LOTTERY_DRAW_IN_FLIGHT + activityId + "_" + uId;
        }

    }

}
