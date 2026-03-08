package com.anontokyo.lottery.domain.rule.service.logic;

import com.anontokyo.lottery.domain.rule.model.req.DecisionMatterReq;
import com.anontokyo.lottery.domain.rule.model.vo.TreeNodeLineVO;

import java.util.List;

/**
 * @description: 逻辑过滤器接口（策略模式）
 * @author: anontokyo
 * @date: 2026/1/23
 */
public interface LogicFilter {

    /**
     * 逻辑过滤器，根据用户属性值和规则限定值进行匹配
     * 
     * @param matterValue          用户属性值
     * @param treeNodeLineInfoList 节点连线集合
     * @return 下一个节点ID
     */
    Long filter(String matterValue, List<TreeNodeLineVO> treeNodeLineInfoList);

    /**
     * 获取用户属性值
     * 
     * @param decisionMatter 决策物料
     * @return 属性值
     */
    String matterValue(DecisionMatterReq decisionMatter);

}
