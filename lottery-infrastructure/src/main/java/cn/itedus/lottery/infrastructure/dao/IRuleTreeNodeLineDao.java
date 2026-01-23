package cn.itedus.lottery.infrastructure.dao;

import cn.itedus.lottery.infrastructure.po.RuleTreeNodeLine;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @description: 规则树节点连线DAO
 * @author: anontokyo
 * @date: 2026/1/23
 */
@Mapper
public interface IRuleTreeNodeLineDao {

    /**
     * 根据规则树ID查询所有节点连线
     * 
     * @param treeId 规则树ID
     * @return 节点连线列表
     */
    List<RuleTreeNodeLine> queryRuleTreeNodeLineList(Long treeId);

}
