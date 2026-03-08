package com.anontokyo.lottery.domain.rule.service.logic.impl;

import com.anontokyo.lottery.domain.rule.model.req.DecisionMatterReq;
import com.anontokyo.lottery.domain.rule.service.logic.BaseLogic;
import org.springframework.stereotype.Component;

/**
 * @description: 用户年龄过滤器
 * @author: anontokyo
 * @date: 2026/1/23
 */
@Component("userAge")
public class UserAgeFilter extends BaseLogic {

    @Override
    public String matterValue(DecisionMatterReq decisionMatter) {
        return decisionMatter.getValMap().get("age").toString();
    }

}
