package cn.itedus.lottery.application.process.draw;

import cn.itedus.lottery.application.process.draw.req.DrawProcessReq;
import cn.itedus.lottery.application.process.draw.res.DrawProcessResult;
import cn.itedus.lottery.application.process.draw.res.RuleQuantificationCrowdResult;
import cn.itedus.lottery.domain.rule.model.req.DecisionMatterReq;

/**
 * @description: 活动抽奖流程编排接口
 * @author：anontokyo
 * @date: 2026/1/22
 */
public interface IActivityDrawProcess {

    /**
     * 执行抽奖流程
     * 
     * @param req 抽奖请求
     * @return 抽奖结果
     */
    DrawProcessResult doDrawProcess(DrawProcessReq req);

    /**
     * 规则引擎量化人群决策
     * 根据用户画像（决策因子）通过规则引擎决策匹配活动
     * 
     * @param req 决策物料请求（包含规则树ID、用户ID、决策因子）
     * @return 决策结果（包含活动ID）
     */
    RuleQuantificationCrowdResult doRuleQuantificationCrowd(DecisionMatterReq req);
}
