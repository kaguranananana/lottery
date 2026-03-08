package com.anontokyo.lottery.domain.rule.service.engine;

import com.anontokyo.lottery.domain.rule.model.req.DecisionMatterReq;
import com.anontokyo.lottery.domain.rule.model.res.EngineResult;

/**
 * @description: 规则引擎过滤器接口
 * @author: anontokyo
 * @date: 2026/1/23
 */
public interface EngineFilter {

    /**
     * 执行规则过滤
     * 
     * @param matter 决策物料
     * @return 规则决策结果
     */
    EngineResult process(DecisionMatterReq matter);

}
