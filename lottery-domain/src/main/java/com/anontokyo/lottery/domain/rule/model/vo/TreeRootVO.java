package com.anontokyo.lottery.domain.rule.model.vo;

/**
 * @description: 规则树根VO
 * @author: anontokyo
 * @date: 2026/1/23
 */
public class TreeRootVO {

    /** 规则树ID */
    private Long treeId;

    /** 规则树名称 */
    private String treeName;

    /** 规则树根节点ID */
    private Long treeRootNodeId;

    public Long getTreeId() {
        return treeId;
    }

    public void setTreeId(Long treeId) {
        this.treeId = treeId;
    }

    public String getTreeName() {
        return treeName;
    }

    public void setTreeName(String treeName) {
        this.treeName = treeName;
    }

    public Long getTreeRootNodeId() {
        return treeRootNodeId;
    }

    public void setTreeRootNodeId(Long treeRootNodeId) {
        this.treeRootNodeId = treeRootNodeId;
    }
}
