package com.anontokyo.lottery.rpc.activity.booth.dto;

import java.io.Serializable;

/**
 * @description: 规则树DTO
 * @author：anontokyo
 * @date: 2026/2/9
 */
public class RuleTreeDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 规则树ID */
    private Long treeId;

    /** 规则树名称 */
    private String treeName;

    /** 规则树描述 */
    private String treeDesc;

    public RuleTreeDTO() {
    }

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

    public String getTreeDesc() {
        return treeDesc;
    }

    public void setTreeDesc(String treeDesc) {
        this.treeDesc = treeDesc;
    }
}
