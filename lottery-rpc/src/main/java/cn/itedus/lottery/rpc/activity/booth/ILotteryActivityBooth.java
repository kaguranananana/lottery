package cn.itedus.lottery.rpc.activity.booth;

import cn.itedus.lottery.rpc.activity.booth.req.DrawReq;
import cn.itedus.lottery.rpc.activity.booth.req.QuantificationDrawReq;
import cn.itedus.lottery.rpc.activity.booth.res.ActivityListRes;
import cn.itedus.lottery.rpc.activity.booth.res.DrawRes;
import cn.itedus.lottery.rpc.activity.booth.res.RuleCriteriaRes;
import cn.itedus.lottery.rpc.activity.booth.res.RuleTreeListRes;

/**
 * @description: 抽奖活动展台接口
 * @author：anontokyo
 * @date: 2026/1/25
 * 
 *        门面模式：对外提供统一的抽奖服务入口
 *        1. 普通抽奖 - 用户指定活动ID进行抽奖
 *        2. 量化人群抽奖 - 根据用户画像自动匹配活动后抽奖
 *        3. 查询接口 - 供前端获取活动、规则树、准则信息
 */
public interface ILotteryActivityBooth {

    /**
     * 普通抽奖
     * 
     * @param drawReq 抽奖请求（包含用户ID和活动ID）
     * @return 抽奖结果
     */
    DrawRes doDraw(DrawReq drawReq);

    /**
     * 量化人群抽奖（智能匹配）
     * 根据用户画像（如年龄、性别等决策因子），通过规则引擎决策匹配活动后进行抽奖
     * 
     * @param quantificationDrawReq 量化抽奖请求（包含用户ID、规则树ID、决策因子）
     * @return 抽奖结果
     */
    DrawRes doQuantificationDraw(QuantificationDrawReq quantificationDrawReq);

    /**
     * 查询运行中的活动列表
     * 
     * @return 运行中的活动列表
     */
    ActivityListRes queryRunningActivityList();

    /**
     * 查询量化规则树列表
     * 
     * @return 规则树列表
     */
    RuleTreeListRes queryRuleTreeList();

    /**
     * 查询量化准则（决策因子）
     * 
     * @return 准则列表（如性别、年龄等）
     */
    RuleCriteriaRes queryRuleCriteria();

}
