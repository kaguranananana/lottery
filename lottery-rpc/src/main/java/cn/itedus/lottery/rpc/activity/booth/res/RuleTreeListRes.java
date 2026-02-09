package cn.itedus.lottery.rpc.activity.booth.res;

import cn.itedus.lottery.common.Result;
import cn.itedus.lottery.rpc.activity.booth.dto.RuleTreeDTO;

import java.io.Serializable;
import java.util.List;

/**
 * @description: 规则树列表响应对象
 * @author：anontokyo
 * @date: 2026/2/9
 */
public class RuleTreeListRes implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 响应结果 */
    private Result result;

    /** 规则树列表 */
    private List<RuleTreeDTO> ruleTreeList;

    public RuleTreeListRes() {
    }

    public RuleTreeListRes(Result result) {
        this.result = result;
    }

    public RuleTreeListRes(Result result, List<RuleTreeDTO> ruleTreeList) {
        this.result = result;
        this.ruleTreeList = ruleTreeList;
    }

    public Result getResult() {
        return result;
    }

    public void setResult(Result result) {
        this.result = result;
    }

    public List<RuleTreeDTO> getRuleTreeList() {
        return ruleTreeList;
    }

    public void setRuleTreeList(List<RuleTreeDTO> ruleTreeList) {
        this.ruleTreeList = ruleTreeList;
    }
}
