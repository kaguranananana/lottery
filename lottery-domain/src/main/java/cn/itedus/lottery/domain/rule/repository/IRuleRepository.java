package cn.itedus.lottery.domain.rule.repository;

import cn.itedus.lottery.domain.rule.model.aggregates.TreeRuleRich;
import cn.itedus.lottery.domain.rule.model.vo.RuleCriteriaVO;
import cn.itedus.lottery.domain.rule.model.vo.TreeRootVO;

import java.util.List;

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

    /**
     * 查询所有规则树列表
     * 
     * @return 规则树列表
     */
    List<TreeRootVO> queryRuleTreeList();

    /**
     * 查询量化准则（决策因子）
     * 
     * @return 准则列表
     */
    List<RuleCriteriaVO> queryRuleCriteria();

}
