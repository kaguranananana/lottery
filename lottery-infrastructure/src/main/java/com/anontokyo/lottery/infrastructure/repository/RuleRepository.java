package com.anontokyo.lottery.infrastructure.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.rule.model.aggregates.TreeRuleRich;
import com.anontokyo.lottery.domain.rule.model.vo.RuleCriteriaVO;
import com.anontokyo.lottery.domain.rule.model.vo.TreeNodeLineVO;
import com.anontokyo.lottery.domain.rule.model.vo.TreeNodeVO;
import com.anontokyo.lottery.domain.rule.model.vo.TreeRootVO;
import com.anontokyo.lottery.domain.rule.repository.IRuleRepository;
import com.anontokyo.lottery.infrastructure.cache.TwoLevelCacheService;
import com.anontokyo.lottery.infrastructure.dao.IRuleTreeDao;
import com.anontokyo.lottery.infrastructure.dao.IRuleTreeNodeDao;
import com.anontokyo.lottery.infrastructure.dao.IRuleTreeNodeLineDao;
import com.anontokyo.lottery.infrastructure.po.RuleTree;
import com.anontokyo.lottery.infrastructure.po.RuleTreeNode;
import com.anontokyo.lottery.infrastructure.po.RuleTreeNodeLine;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @description: rule repository
 * @author: anontokyo
 * @date: 2026/1/23
 */
@Repository
public class RuleRepository implements IRuleRepository {

    @Resource
    private IRuleTreeDao ruleTreeDao;

    @Resource
    private IRuleTreeNodeDao ruleTreeNodeDao;

    @Resource
    private IRuleTreeNodeLineDao ruleTreeNodeLineDao;

    @Resource
    private TwoLevelCacheService twoLevelCacheService;

    @Override
    public TreeRuleRich queryTreeRuleRich(Long treeId) {
        return twoLevelCacheService.getRuleTreeCache(
                Constants.RedisKey.KEY_LOTTERY_CACHE_RULE_TREE_RICH(treeId),
                TreeRuleRich.class,
                () -> queryTreeRuleRichFromDb(treeId));
    }

    private TreeRuleRich queryTreeRuleRichFromDb(Long treeId) {
        RuleTree ruleTree = ruleTreeDao.queryRuleTreeByTreeId(treeId);
        if (null == ruleTree) {
            return null;
        }

        List<RuleTreeNode> ruleTreeNodeList = ruleTreeNodeDao.queryRuleTreeNodeList(treeId);
        List<RuleTreeNodeLine> ruleTreeNodeLineList = ruleTreeNodeLineDao.queryRuleTreeNodeLineList(treeId);

        Map<Long, List<TreeNodeLineVO>> nodeLineMap = new HashMap<>();
        for (RuleTreeNodeLine line : ruleTreeNodeLineList) {
            TreeNodeLineVO lineVO = new TreeNodeLineVO();
            lineVO.setNodeIdFrom(line.getNodeIdFrom());
            lineVO.setNodeIdTo(line.getNodeIdTo());
            lineVO.setRuleLimitType(line.getRuleLimitType());
            lineVO.setRuleLimitValue(line.getRuleLimitValue());

            List<TreeNodeLineVO> lineList = nodeLineMap.computeIfAbsent(line.getNodeIdFrom(), k -> new ArrayList<>());
            lineList.add(lineVO);
        }

        Map<Long, TreeNodeVO> treeNodeMap = new HashMap<>();
        for (RuleTreeNode node : ruleTreeNodeList) {
            TreeNodeVO nodeVO = new TreeNodeVO();
            nodeVO.setTreeId(node.getTreeId());
            nodeVO.setTreeNodeId(node.getId());
            nodeVO.setNodeType(node.getNodeType());
            nodeVO.setNodeValue(node.getNodeValue());
            nodeVO.setRuleKey(node.getRuleKey());
            nodeVO.setRuleDesc(node.getRuleDesc());
            nodeVO.setTreeNodeLineInfoList(nodeLineMap.get(node.getId()));

            treeNodeMap.put(node.getId(), nodeVO);
        }

        TreeRootVO treeRoot = new TreeRootVO();
        treeRoot.setTreeId(ruleTree.getId());
        treeRoot.setTreeName(ruleTree.getTreeName());
        treeRoot.setTreeRootNodeId(ruleTree.getTreeRootNodeId());

        TreeRuleRich treeRuleRich = new TreeRuleRich();
        treeRuleRich.setTreeRoot(treeRoot);
        treeRuleRich.setTreeNodeMap(treeNodeMap);
        return treeRuleRich;
    }

    @Override
    public List<TreeRootVO> queryRuleTreeList() {
        List<RuleTree> ruleTreeList = ruleTreeDao.queryRuleTreeList();
        List<TreeRootVO> treeRootVOList = new ArrayList<>();
        for (RuleTree ruleTree : ruleTreeList) {
            TreeRootVO treeRootVO = new TreeRootVO();
            treeRootVO.setTreeId(ruleTree.getId());
            treeRootVO.setTreeName(ruleTree.getTreeName());
            treeRootVO.setTreeRootNodeId(ruleTree.getTreeRootNodeId());
            treeRootVOList.add(treeRootVO);
        }
        return treeRootVOList;
    }

    @Override
    public List<RuleCriteriaVO> queryRuleCriteria() {
        List<RuleTreeNode> ruleTreeNodeList = ruleTreeNodeDao.queryDistinctRuleKeys();
        List<RuleCriteriaVO> criteriaVOList = new ArrayList<>();
        for (RuleTreeNode node : ruleTreeNodeList) {
            RuleCriteriaVO criteriaVO = new RuleCriteriaVO();
            criteriaVO.setRuleKey(node.getRuleKey());
            criteriaVO.setRuleDesc(node.getRuleDesc());
            criteriaVOList.add(criteriaVO);
        }
        return criteriaVOList;
    }

}
