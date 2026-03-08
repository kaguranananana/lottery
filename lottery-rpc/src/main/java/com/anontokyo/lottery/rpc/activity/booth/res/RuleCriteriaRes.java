package com.anontokyo.lottery.rpc.activity.booth.res;

import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.rpc.activity.booth.dto.RuleCriteriaDTO;

import java.io.Serializable;
import java.util.List;

/**
 * @description: 量化准则响应对象
 * @author：anontokyo
 * @date: 2026/2/9
 */
public class RuleCriteriaRes implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 响应结果 */
    private Result result;

    /** 准则列表 */
    private List<RuleCriteriaDTO> criteriaList;

    public RuleCriteriaRes() {
    }

    public RuleCriteriaRes(Result result) {
        this.result = result;
    }

    public RuleCriteriaRes(Result result, List<RuleCriteriaDTO> criteriaList) {
        this.result = result;
        this.criteriaList = criteriaList;
    }

    public Result getResult() {
        return result;
    }

    public void setResult(Result result) {
        this.result = result;
    }

    public List<RuleCriteriaDTO> getCriteriaList() {
        return criteriaList;
    }

    public void setCriteriaList(List<RuleCriteriaDTO> criteriaList) {
        this.criteriaList = criteriaList;
    }
}
