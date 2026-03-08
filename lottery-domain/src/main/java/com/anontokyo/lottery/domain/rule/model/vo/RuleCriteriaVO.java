package com.anontokyo.lottery.domain.rule.model.vo;

/**
 * @description: 量化准则值对象（决策因子）
 * @author: anontokyo
 * @date: 2026/2/9
 */
public class RuleCriteriaVO {

    /** 规则键（如 userGender, userAge） */
    private String ruleKey;

    /** 规则描述（如 用户性别, 用户年龄） */
    private String ruleDesc;

    public RuleCriteriaVO() {
    }

    public RuleCriteriaVO(String ruleKey, String ruleDesc) {
        this.ruleKey = ruleKey;
        this.ruleDesc = ruleDesc;
    }

    public String getRuleKey() {
        return ruleKey;
    }

    public void setRuleKey(String ruleKey) {
        this.ruleKey = ruleKey;
    }

    public String getRuleDesc() {
        return ruleDesc;
    }

    public void setRuleDesc(String ruleDesc) {
        this.ruleDesc = ruleDesc;
    }
}
