package com.anontokyo.lottery.domain.rule.service.engine.impl;

import com.anontokyo.lottery.domain.rule.model.aggregates.TreeRuleRich;
import com.anontokyo.lottery.domain.rule.model.req.DecisionMatterReq;
import com.anontokyo.lottery.domain.rule.model.res.EngineResult;
import com.anontokyo.lottery.domain.rule.model.vo.TreeNodeVO;
import com.anontokyo.lottery.domain.rule.repository.IRuleRepository;
import com.anontokyo.lottery.domain.rule.service.engine.EngineBase;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * @description: 规则引擎处理器
 * @author: anontokyo
 * @date: 2026/1/23
 */
@Service("ruleEngineHandle")
public class RuleEngineHandle extends EngineBase {

    @Resource
    private IRuleRepository ruleRepository;

    @Override
    public EngineResult process(DecisionMatterReq matter) {
        // 1. 查询规则树
        TreeRuleRich treeRuleRich = ruleRepository.queryTreeRuleRich(matter.getTreeId());
        if (null == treeRuleRich) {
            throw new RuntimeException("Tree Rule is null! TreeId: " + matter.getTreeId());
        }

        // 2. 执行决策
        TreeNodeVO treeNodeInfo = engineDecisionMaker(treeRuleRich, matter);

        // 3. 返回结果
        return new EngineResult(matter.getUserId(), treeNodeInfo.getTreeId(),
                treeNodeInfo.getTreeNodeId(), treeNodeInfo.getNodeValue());
    }

}
