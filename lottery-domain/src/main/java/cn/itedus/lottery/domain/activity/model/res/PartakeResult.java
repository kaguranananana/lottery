package cn.itedus.lottery.domain.activity.model.res;

import cn.itedus.lottery.common.Result;

/**
 * @description: 活动参与结果
 * @author：anontokyo
 * @date: 2026/1/17
 */
public class PartakeResult extends Result {
    /**
     * 活动策略ID
     */
    private Long strategyId;

    public Long getStrategyId() {
        return strategyId;
    }

    public void setStrategyId(Long strategyId) {
        this.strategyId = strategyId;
    }

    public PartakeResult(String code, String info) {
        super(code, info);
    }
}
