package cn.itedus.lottery.infrastructure.dao;

import cn.itedus.lottery.infrastructure.po.RuleTreeNode;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @description: 规则树节点DAO
 * @author: anontokyo
 * @date: 2026/1/23
 */
@Mapper
public interface IRuleTreeNodeDao {

    /**
     * 根据规则树ID查询所有节点
     * 
     * @param treeId 规则树ID
     * @return 节点列表
     */
    List<RuleTreeNode> queryRuleTreeNodeList(Long treeId);

    /**
     * 查询所有不同的规则键（去重）
     * 
     * @return 规则节点列表（包含不同的规则键）
     */
    List<RuleTreeNode> queryDistinctRuleKeys();

}
