package cn.itedus.lottery.interfaces.facade;

import cn.itedus.lottery.application.process.IActivityProcess;
import cn.itedus.lottery.application.process.req.DrawProcessReq;
import cn.itedus.lottery.application.process.res.DrawProcessResult;
import cn.itedus.lottery.application.process.res.RuleQuantificationCrowdResult;
import cn.itedus.lottery.common.Constants;
import cn.itedus.lottery.common.Result;
import cn.itedus.lottery.domain.rule.model.req.DecisionMatterReq;
import cn.itedus.lottery.domain.strategy.model.vo.DrawAwardInfo;
import cn.itedus.lottery.interfaces.assembler.AwardMapping;
import cn.itedus.lottery.rpc.ILotteryActivityBooth;
import cn.itedus.lottery.rpc.dto.AwardDTO;
import cn.itedus.lottery.rpc.req.DrawReq;
import cn.itedus.lottery.rpc.req.QuantificationDrawReq;
import cn.itedus.lottery.rpc.res.DrawRes;
import com.alibaba.fastjson.JSON;
import org.apache.dubbo.config.annotation.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;

/**
 * @description: 抽奖活动展台门面实现
 * @author：anontokyo
 * @date: 2026/1/25
 * 
 *        门面模式：
 *        - 对外提供统一入口，简化复杂系统调用
 *        - 协调应用层服务调用和对象转换
 *        - 处理异常、日志等横切关注点
 *        - 不包含业务逻辑，只做流程编排
 */
@Service
public class LotteryActivityBooth implements ILotteryActivityBooth {

    private Logger logger = LoggerFactory.getLogger(LotteryActivityBooth.class);

    @Resource
    private IActivityProcess activityProcess;

    @Resource
    private AwardMapping awardMapping;

    /**
     * 普通抽奖
     * 
     * @param drawReq 抽奖请求（包含用户ID和活动ID）
     * @return 抽奖结果
     */
    @Override
    public DrawRes doDraw(DrawReq drawReq) {
        try {
            logger.info("抽奖活动开始 uId:{} activityId:{}", drawReq.getuId(), drawReq.getActivityId());

            // 1. 调用应用层执行抽奖流程
            DrawProcessReq req = new DrawProcessReq(drawReq.getuId(), drawReq.getActivityId());
            DrawProcessResult drawProcessResult = activityProcess.doDrawProcess(req);

            // 2. 检查抽奖结果
            if (!Constants.ResponseCode.SUCCESS.getCode().equals(drawProcessResult.getCode())) {
                logger.warn("抽奖失败 uId:{} activityId:{} code:{} info:{}",
                        drawReq.getuId(), drawReq.getActivityId(),
                        drawProcessResult.getCode(), drawProcessResult.getInfo());
                return new DrawRes(new Result(drawProcessResult.getCode(), drawProcessResult.getInfo()));
            }

            // 3. 对象转换：领域层 VO → 接口层 DTO
            DrawAwardInfo drawAwardInfo = drawProcessResult.getDrawAwardInfo();
            AwardDTO awardDTO = awardMapping.sourceToTarget(drawAwardInfo);

            // 4. 手动设置 activityId（转换时忽略的字段）
            awardDTO.setUserId(drawReq.getuId());
            awardDTO.setActivityId(drawReq.getActivityId());

            // 5. 封装响应
            DrawRes drawRes = new DrawRes(
                    new Result(Constants.ResponseCode.SUCCESS.getCode(), Constants.ResponseCode.SUCCESS.getInfo()),
                    awardDTO);

            logger.info("抽奖活动完成 uId:{} activityId:{} awardDTO:{}",
                    drawReq.getuId(), drawReq.getActivityId(), JSON.toJSONString(awardDTO));

            return drawRes;

        } catch (Exception e) {
            logger.error("抽奖活动异常 uId:{} activityId:{}", drawReq.getuId(), drawReq.getActivityId(), e);
            return new DrawRes(
                    new Result(Constants.ResponseCode.UN_ERROR.getCode(), Constants.ResponseCode.UN_ERROR.getInfo()));
        }
    }

    /**
     * 量化人群抽奖（智能匹配）
     * 
     * @param quantificationDrawReq 量化抽奖请求（包含用户ID、规则树ID、决策因子）
     * @return 抽奖结果
     */
    @Override
    public DrawRes doQuantificationDraw(QuantificationDrawReq quantificationDrawReq) {
        try {
            logger.info("量化人群抽奖开始 uId:{} treeId:{}",
                    quantificationDrawReq.getuId(), quantificationDrawReq.getTreeId());

            // 1. 构建规则决策请求
            DecisionMatterReq decisionMatterReq = new DecisionMatterReq(
                    quantificationDrawReq.getTreeId(),
                    quantificationDrawReq.getuId(),
                    quantificationDrawReq.getValMap());

            // 2. 调用规则引擎进行人群量化决策
            RuleQuantificationCrowdResult ruleResult = activityProcess.doRuleQuantificationCrowd(decisionMatterReq);

            // 3. 检查规则决策结果
            if (!Constants.ResponseCode.SUCCESS.getCode().equals(ruleResult.getCode())) {
                logger.warn("规则决策失败 uId:{} treeId:{} code:{} info:{}",
                        quantificationDrawReq.getuId(), quantificationDrawReq.getTreeId(),
                        ruleResult.getCode(), ruleResult.getInfo());
                return new DrawRes(new Result(ruleResult.getCode(), ruleResult.getInfo()));
            }

            // 4. 使用决策出的活动ID执行抽奖
            Long activityId = ruleResult.getActivityId();
            DrawProcessReq drawProcessReq = new DrawProcessReq(quantificationDrawReq.getuId(), activityId);
            DrawProcessResult drawProcessResult = activityProcess.doDrawProcess(drawProcessReq);

            // 5. 检查抽奖结果
            if (!Constants.ResponseCode.SUCCESS.getCode().equals(drawProcessResult.getCode())) {
                logger.warn("抽奖失败 uId:{} activityId:{} code:{} info:{}",
                        quantificationDrawReq.getuId(), activityId,
                        drawProcessResult.getCode(), drawProcessResult.getInfo());
                return new DrawRes(new Result(drawProcessResult.getCode(), drawProcessResult.getInfo()));
            }

            // 6. 对象转换
            DrawAwardInfo drawAwardInfo = drawProcessResult.getDrawAwardInfo();
            AwardDTO awardDTO = awardMapping.sourceToTarget(drawAwardInfo);
            awardDTO.setUserId(quantificationDrawReq.getuId());
            awardDTO.setActivityId(activityId);

            // 7. 封装响应
            DrawRes drawRes = new DrawRes(
                    new Result(Constants.ResponseCode.SUCCESS.getCode(), Constants.ResponseCode.SUCCESS.getInfo()),
                    awardDTO);

            logger.info("量化人群抽奖完成 uId:{} treeId:{} activityId:{} awardDTO:{}",
                    quantificationDrawReq.getuId(), quantificationDrawReq.getTreeId(),
                    activityId, JSON.toJSONString(awardDTO));

            return drawRes;

        } catch (Exception e) {
            logger.error("量化人群抽奖异常 uId:{} treeId:{}",
                    quantificationDrawReq.getuId(), quantificationDrawReq.getTreeId(), e);
            return new DrawRes(
                    new Result(Constants.ResponseCode.UN_ERROR.getCode(), Constants.ResponseCode.UN_ERROR.getInfo()));
        }
    }

}
