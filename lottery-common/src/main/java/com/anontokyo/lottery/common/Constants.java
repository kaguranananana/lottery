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
        ERR_TOKEN("D005", "分布式锁失败");


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
     * 活动状态：1编辑、2提审、3撤审、4通过、5运行(审核通过后worker扫描状态)、6拒绝、7关闭、8开启
     */
    public enum ActivityState {

        /** 1：编辑 */
        EDIT(1, "编辑"),
        /** 2：提审 */
        ARRAIGNMENT(2, "提审"),
        /** 3：撤审 */
        REVOKE(3, "撤审"),
        /** 4：通过 */
        PASS(4, "通过"),
        /** 5：运行(活动中) */
        DOING(5, "运行(活动中)"),
        /** 6：拒绝 */
        REFUSE(6, "拒绝"),
        /** 7：关闭 */
        CLOSE(7, "关闭"),
        /** 8：开启 */
        OPEN(8, "开启");

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
     * Ids 生成策略枚举
     */
    public enum Ids {
        /** 雪花算法 */
        SnowFlake,
        /** 日期算法 */
        ShortCode,
        /** 随机算法 */
        RandomNumeric;
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
     * 全局常量
     */
    public static final class Global {
        /** 空节点值 */
        public static final Long TREE_NULL_NODE = 0L;
    }

    /**
     * 决策树节点类型
     */
    public static final class NodeType {
        /** 子叶节点 */
        public static final Integer STEM = 1;
        /** 果实节点 */
        public static final Integer FRUIT = 2;
    }

    /**
     * 规则限定类型
     */
    public static final class RuleLimitType {
        /** 等于 */
        public static final int EQUAL = 1;
        /** 大于 */
        public static final int GT = 2;
        /** 小于 */
        public static final int LT = 3;
        /** 大于等于 */
        public static final int GE = 4;
        /** 小于等于 */
        public static final int LE = 5;
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

        // 抽奖活动库存锁 Key
        private static final String LOTTERY_ACTIVITY_STOCK_COUNT_TOKEN = "lottery_activity_stock_count_token_";

        public static String KEY_LOTTERY_ACTIVITY_STOCK_COUNT_TOKEN(Long activityId, Integer stockUsedCount) {
            return LOTTERY_ACTIVITY_STOCK_COUNT_TOKEN + activityId + "_" + stockUsedCount;
        }

        // 策略奖品库存 Key
        private static final String LOTTERY_STRATEGY_AWARD_STOCK_COUNT = "lottery_strategy_award_stock_count_";

        public static String KEY_LOTTERY_STRATEGY_AWARD_STOCK_COUNT(Long strategyId, String awardId) {
            return LOTTERY_STRATEGY_AWARD_STOCK_COUNT + strategyId + "_" + awardId;
        }

        // 策略奖品库存锁 Key
        private static final String LOTTERY_STRATEGY_AWARD_STOCK_COUNT_TOKEN = "lottery_strategy_award_stock_count_token_";

        public static String KEY_LOTTERY_STRATEGY_AWARD_STOCK_COUNT_TOKEN(Long strategyId, String awardId, Integer stockUsedCount) {
            return LOTTERY_STRATEGY_AWARD_STOCK_COUNT_TOKEN + strategyId + "_" + awardId + "_" + stockUsedCount;
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

        // Rule tree aggregate cache key
        private static final String LOTTERY_CACHE_RULE_TREE_RICH = "lottery_cache_rule_tree_rich_";

        public static String KEY_LOTTERY_CACHE_RULE_TREE_RICH(Long treeId) {
            return LOTTERY_CACHE_RULE_TREE_RICH + treeId;
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

    }

}
