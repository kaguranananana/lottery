package cn.itedus.lottery.domain.rule.model.req;

import java.util.Map;

/**
 * @description: 决策物料请求对象
 * @author: anontokyo
 * @date: 2026/1/23
 */
public class DecisionMatterReq {

    /** 规则树ID */
    private Long treeId;

    /** 用户ID */
    private String userId;

    /** 决策值映射 */
    private Map<String, Object> valMap;

    public DecisionMatterReq() {
    }

    public DecisionMatterReq(Long treeId, String userId, Map<String, Object> valMap) {
        this.treeId = treeId;
        this.userId = userId;
        this.valMap = valMap;
    }

    public Long getTreeId() {
        return treeId;
    }

    public void setTreeId(Long treeId) {
        this.treeId = treeId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Map<String, Object> getValMap() {
        return valMap;
    }

    public void setValMap(Map<String, Object> valMap) {
        this.valMap = valMap;
    }
}
