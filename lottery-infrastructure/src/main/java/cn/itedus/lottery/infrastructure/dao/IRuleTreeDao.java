package cn.itedus.lottery.infrastructure.dao;

import cn.itedus.lottery.infrastructure.po.RuleTree;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @description: 规则树DAO
 * @author: anontokyo
 * @date: 2026/1/23
 */
@Mapper
public interface IRuleTreeDao {

    /**
     * 根据规则树ID查询规则树配置
     * 
     * @param id 规则树ID
     * @return 规则树配置
     */
    RuleTree queryRuleTreeByTreeId(Long id);

    /**
     * 查询所有规则树列表
     * 
     * @return 规则树列表
     */
    List<RuleTree> queryRuleTreeList();

}
