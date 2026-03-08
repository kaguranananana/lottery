package com.anontokyo.lottery.domain.rule.service.engine;

import com.anontokyo.lottery.domain.rule.service.logic.LogicFilter;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @description: 规则引擎配置类
 * @author: anontokyo
 * @date: 2026/1/23
 */
public class EngineConfig {

    /** 规则逻辑过滤器映射 */
    protected static Map<String, LogicFilter> logicFilterMap = new ConcurrentHashMap<>();

    @Resource
    private Map<String, LogicFilter> logicFilterGroup;

    @PostConstruct
    public void init() {
        logicFilterMap.putAll(logicFilterGroup);
    }

}
