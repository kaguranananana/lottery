package cn.itedus.lottery.domain.rule.model.vo;

/**
 * @description: 规则树连线VO
 * @author: anontokyo
 * @date: 2026/1/23
 */
public class TreeNodeLineVO {

    /** 节点From */
    private Long nodeIdFrom;

    /** 节点To */
    private Long nodeIdTo;

    /** 限定类型；1:=;2:>;3:<;4:>=;5<= */
    private Integer ruleLimitType;

    /** 限定值 */
    private String ruleLimitValue;

    public Long getNodeIdFrom() {
        return nodeIdFrom;
    }

    public void setNodeIdFrom(Long nodeIdFrom) {
        this.nodeIdFrom = nodeIdFrom;
    }

    public Long getNodeIdTo() {
        return nodeIdTo;
    }

    public void setNodeIdTo(Long nodeIdTo) {
        this.nodeIdTo = nodeIdTo;
    }

    public Integer getRuleLimitType() {
        return ruleLimitType;
    }

    public void setRuleLimitType(Integer ruleLimitType) {
        this.ruleLimitType = ruleLimitType;
    }

    public String getRuleLimitValue() {
        return ruleLimitValue;
    }

    public void setRuleLimitValue(String ruleLimitValue) {
        this.ruleLimitValue = ruleLimitValue;
    }
}
