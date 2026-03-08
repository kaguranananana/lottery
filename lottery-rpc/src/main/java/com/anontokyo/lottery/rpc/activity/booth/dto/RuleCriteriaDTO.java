package com.anontokyo.lottery.rpc.activity.booth.dto;

import java.io.Serializable;

/**
 * @description: 量化准则DTO（决策因子）
 * @author：anontokyo
 * @date: 2026/2/9
 * 
 *        用于前端展示可用的决策因子，如性别、年龄等
 */
public class RuleCriteriaDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 规则键（如 userGender, userAge） */
    private String ruleKey;

    /** 规则描述（如 用户性别[男/女], 用户年龄） */
    private String ruleDesc;

    public RuleCriteriaDTO() {
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
