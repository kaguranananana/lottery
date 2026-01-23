package cn.itedus.lottery.domain.rule.repository;

import cn.itedus.lottery.domain.rule.model.aggregates.TreeRuleRich;

/**
 * @description: 规则仓储接口
 * @author: anontokyo
 * @date: 2026/1/23
 */
public interface IRuleRepository {

    /**
     * 查询规则树聚合
     * 
     * @param treeId 规则树ID
     * @return 规则树聚合根
     */
    TreeRuleRich queryTreeRuleRich(Long treeId);

}
