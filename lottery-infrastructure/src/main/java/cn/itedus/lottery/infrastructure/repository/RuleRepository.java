package cn.itedus.lottery.infrastructure.repository;

import cn.itedus.lottery.domain.rule.model.aggregates.TreeRuleRich;
import cn.itedus.lottery.domain.rule.model.vo.TreeNodeLineVO;
import cn.itedus.lottery.domain.rule.model.vo.TreeNodeVO;
import cn.itedus.lottery.domain.rule.model.vo.TreeRootVO;
import cn.itedus.lottery.domain.rule.repository.IRuleRepository;
import cn.itedus.lottery.infrastructure.dao.IRuleTreeDao;
import cn.itedus.lottery.infrastructure.dao.IRuleTreeNodeDao;
import cn.itedus.lottery.infrastructure.dao.IRuleTreeNodeLineDao;
import cn.itedus.lottery.infrastructure.po.RuleTree;
import cn.itedus.lottery.infrastructure.po.RuleTreeNode;
import cn.itedus.lottery.infrastructure.po.RuleTreeNodeLine;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @description: 规则仓储实现
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

    @Override
    public TreeRuleRich queryTreeRuleRich(Long treeId) {
        // 1. 查询规则树
        RuleTree ruleTree = ruleTreeDao.queryRuleTreeByTreeId(treeId);
        if (null == ruleTree) {
            return null;
        }

        // 2. 查询规则树节点
        List<RuleTreeNode> ruleTreeNodeList = ruleTreeNodeDao.queryRuleTreeNodeList(treeId);

        // 3. 查询规则树节点连线
        List<RuleTreeNodeLine> ruleTreeNodeLineList = ruleTreeNodeLineDao.queryRuleTreeNodeLineList(treeId);

        // 4. 组装节点连线映射 (nodeIdFrom -> List<TreeNodeLineVO>)
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

        // 5. 组装节点映射 (nodeId -> TreeNodeVO)
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

        // 6. 组装树根
        TreeRootVO treeRoot = new TreeRootVO();
        treeRoot.setTreeId(ruleTree.getId());
        treeRoot.setTreeName(ruleTree.getTreeName());
        treeRoot.setTreeRootNodeId(ruleTree.getTreeRootNodeId());

        // 7. 组装聚合根
        TreeRuleRich treeRuleRich = new TreeRuleRich();
        treeRuleRich.setTreeRoot(treeRoot);
        treeRuleRich.setTreeNodeMap(treeNodeMap);

        return treeRuleRich;
    }

}
