package com.anontokyo.lottery.interfaces.facade;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.application.process.draw.IActivityDrawProcess;
import com.anontokyo.lottery.application.process.draw.req.DrawProcessReq;
import com.anontokyo.lottery.application.process.draw.res.DrawProcessResult;
import com.anontokyo.lottery.application.process.draw.res.RuleQuantificationCrowdResult;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityVO;
import com.anontokyo.lottery.domain.rule.model.req.DecisionMatterReq;
import com.anontokyo.lottery.domain.rule.model.vo.RuleCriteriaVO;
import com.anontokyo.lottery.domain.rule.model.vo.TreeRootVO;
import com.anontokyo.lottery.domain.strategy.model.vo.DrawAwardInfo;
import com.anontokyo.lottery.interfaces.assembler.AwardMapping;
import com.anontokyo.lottery.rpc.activity.booth.ILotteryActivityBooth;
import com.anontokyo.lottery.rpc.activity.booth.dto.ActivityDTO;
import com.anontokyo.lottery.rpc.activity.booth.dto.AwardDTO;
import com.anontokyo.lottery.rpc.activity.booth.dto.RuleCriteriaDTO;
import com.anontokyo.lottery.rpc.activity.booth.dto.RuleTreeDTO;
import com.anontokyo.lottery.rpc.activity.booth.req.DrawReq;
import com.anontokyo.lottery.rpc.activity.booth.req.QuantificationDrawReq;
import com.anontokyo.lottery.rpc.activity.booth.res.ActivityListRes;
import com.anontokyo.lottery.rpc.activity.booth.res.DrawRes;
import com.anontokyo.lottery.rpc.activity.booth.res.RuleCriteriaRes;
import com.anontokyo.lottery.rpc.activity.booth.res.RuleTreeListRes;
import org.apache.dubbo.config.annotation.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@Service
public class LotteryActivityBooth implements ILotteryActivityBooth {

    private final Logger logger = LoggerFactory.getLogger(LotteryActivityBooth.class);

    @Resource
    private IActivityDrawProcess activityProcess;

    @Resource
    private AwardMapping awardMapping;

    @Override
    public DrawRes doDraw(DrawReq drawReq) {
        try {
            if (logger.isDebugEnabled()) {
                logger.debug("draw start uId:{} activityId:{}", drawReq.getuId(), drawReq.getActivityId());
            }

            DrawProcessReq req = new DrawProcessReq(drawReq.getuId(), drawReq.getActivityId());
            DrawProcessResult drawProcessResult = activityProcess.doDrawProcess(req);
            if (!Constants.ResponseCode.SUCCESS.getCode().equals(drawProcessResult.getCode())) {
                logger.warn("draw failed uId:{} activityId:{} code:{} info:{}", drawReq.getuId(),
                        drawReq.getActivityId(), drawProcessResult.getCode(), drawProcessResult.getInfo());
                return new DrawRes(new Result(drawProcessResult.getCode(), drawProcessResult.getInfo()));
            }

            DrawAwardInfo drawAwardInfo = drawProcessResult.getDrawAwardInfo();
            AwardDTO awardDTO = awardMapping.sourceToTarget(drawAwardInfo);
            awardDTO.setUserId(drawReq.getuId());
            awardDTO.setActivityId(drawReq.getActivityId());

            DrawRes drawRes = new DrawRes(
                    new Result(Constants.ResponseCode.SUCCESS.getCode(), Constants.ResponseCode.SUCCESS.getInfo()),
                    awardDTO);

            if (logger.isDebugEnabled()) {
                logger.debug("draw finish uId:{} activityId:{} awardDTO:{}", drawReq.getuId(),
                        drawReq.getActivityId(), JSON.toJSONString(awardDTO));
            }
            return drawRes;
        } catch (Exception e) {
            logger.error("draw exception uId:{} activityId:{}", drawReq.getuId(), drawReq.getActivityId(), e);
            return new DrawRes(buildSystemFailResult(e));
        }
    }

    @Override
    public DrawRes doQuantificationDraw(QuantificationDrawReq quantificationDrawReq) {
        try {
            if (logger.isDebugEnabled()) {
                logger.debug("quantification draw start uId:{} treeId:{}", quantificationDrawReq.getuId(),
                        quantificationDrawReq.getTreeId());
            }

            DecisionMatterReq decisionMatterReq = new DecisionMatterReq(
                    quantificationDrawReq.getTreeId(),
                    quantificationDrawReq.getuId(),
                    quantificationDrawReq.getValMap());
            RuleQuantificationCrowdResult ruleResult = activityProcess.doRuleQuantificationCrowd(decisionMatterReq);
            if (!Constants.ResponseCode.SUCCESS.getCode().equals(ruleResult.getCode())) {
                logger.warn("rule quantification failed uId:{} treeId:{} code:{} info:{}",
                        quantificationDrawReq.getuId(), quantificationDrawReq.getTreeId(), ruleResult.getCode(),
                        ruleResult.getInfo());
                return new DrawRes(new Result(ruleResult.getCode(), ruleResult.getInfo()));
            }

            Long activityId = ruleResult.getActivityId();
            DrawProcessReq drawProcessReq = new DrawProcessReq(quantificationDrawReq.getuId(), activityId);
            DrawProcessResult drawProcessResult = activityProcess.doDrawProcess(drawProcessReq);
            if (!Constants.ResponseCode.SUCCESS.getCode().equals(drawProcessResult.getCode())) {
                logger.warn("draw failed uId:{} activityId:{} code:{} info:{}", quantificationDrawReq.getuId(),
                        activityId, drawProcessResult.getCode(), drawProcessResult.getInfo());
                return new DrawRes(new Result(drawProcessResult.getCode(), drawProcessResult.getInfo()));
            }

            DrawAwardInfo drawAwardInfo = drawProcessResult.getDrawAwardInfo();
            AwardDTO awardDTO = awardMapping.sourceToTarget(drawAwardInfo);
            awardDTO.setUserId(quantificationDrawReq.getuId());
            awardDTO.setActivityId(activityId);

            DrawRes drawRes = new DrawRes(
                    new Result(Constants.ResponseCode.SUCCESS.getCode(), Constants.ResponseCode.SUCCESS.getInfo()),
                    awardDTO);

            if (logger.isDebugEnabled()) {
                logger.debug("quantification draw finish uId:{} treeId:{} activityId:{} awardDTO:{}",
                        quantificationDrawReq.getuId(), quantificationDrawReq.getTreeId(), activityId,
                        JSON.toJSONString(awardDTO));
            }
            return drawRes;
        } catch (Exception e) {
            logger.error("quantification draw exception uId:{} treeId:{}", quantificationDrawReq.getuId(),
                    quantificationDrawReq.getTreeId(), e);
            return new DrawRes(buildSystemFailResult(e));
        }
    }

    @Override
    public ActivityListRes queryRunningActivityList() {
        try {
            logger.info("query running activity list start");
            List<ActivityVO> activityVOList = activityProcess.queryRunningActivityList();

            List<ActivityDTO> activityDTOList = new ArrayList<>();
            for (ActivityVO activityVO : activityVOList) {
                ActivityDTO dto = new ActivityDTO();
                dto.setActivityId(activityVO.getActivityId());
                dto.setActivityName(activityVO.getActivityName());
                dto.setActivityDesc(activityVO.getActivityDesc());
                dto.setBeginDateTime(activityVO.getBeginDateTime());
                dto.setEndDateTime(activityVO.getEndDateTime());
                dto.setState(activityVO.getState());
                activityDTOList.add(dto);
            }

            logger.info("query running activity list finish size:{}", activityDTOList.size());
            return new ActivityListRes(
                    new Result(Constants.ResponseCode.SUCCESS.getCode(), Constants.ResponseCode.SUCCESS.getInfo()),
                    activityDTOList);
        } catch (Exception e) {
            logger.error("query running activity list exception", e);
            return new ActivityListRes(buildSystemFailResult(e));
        }
    }

    @Override
    public RuleTreeListRes queryRuleTreeList() {
        try {
            logger.info("query rule tree list start");
            List<TreeRootVO> treeRootVOList = activityProcess.queryRuleTreeList();

            List<RuleTreeDTO> ruleTreeDTOList = new ArrayList<>();
            for (TreeRootVO treeRootVO : treeRootVOList) {
                RuleTreeDTO dto = new RuleTreeDTO();
                dto.setTreeId(treeRootVO.getTreeId());
                dto.setTreeName(treeRootVO.getTreeName());
                ruleTreeDTOList.add(dto);
            }

            logger.info("query rule tree list finish size:{}", ruleTreeDTOList.size());
            return new RuleTreeListRes(
                    new Result(Constants.ResponseCode.SUCCESS.getCode(), Constants.ResponseCode.SUCCESS.getInfo()),
                    ruleTreeDTOList);
        } catch (Exception e) {
            logger.error("query rule tree list exception", e);
            return new RuleTreeListRes(buildSystemFailResult(e));
        }
    }

    @Override
    public RuleCriteriaRes queryRuleCriteria() {
        try {
            logger.info("query rule criteria start");
            List<RuleCriteriaVO> criteriaVOList = activityProcess.queryRuleCriteria();

            List<RuleCriteriaDTO> criteriaDTOList = new ArrayList<>();
            for (RuleCriteriaVO criteriaVO : criteriaVOList) {
                RuleCriteriaDTO dto = new RuleCriteriaDTO();
                dto.setRuleKey(criteriaVO.getRuleKey());
                dto.setRuleDesc(criteriaVO.getRuleDesc());
                criteriaDTOList.add(dto);
            }

            logger.info("query rule criteria finish size:{}", criteriaDTOList.size());
            return new RuleCriteriaRes(
                    new Result(Constants.ResponseCode.SUCCESS.getCode(), Constants.ResponseCode.SUCCESS.getInfo()),
                    criteriaDTOList);
        } catch (Exception e) {
            logger.error("query rule criteria exception", e);
            return new RuleCriteriaRes(buildSystemFailResult(e));
        }
    }

    private Result buildSystemFailResult(Exception e) {
        return Result.buildErrorResult(buildSystemErrorInfo(e));
    }

    private String buildSystemErrorInfo(Throwable throwable) {
        Throwable root = getRootCause(throwable);
        String detail = root.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            detail = root.getClass().getSimpleName();
        }
        detail = detail.replaceAll("[\\r\\n]+", " ").trim();
        if (detail.length() > 180) {
            detail = detail.substring(0, 180) + "...";
        }
        return "System error(" + root.getClass().getSimpleName() + "): " + detail;
    }

    private Throwable getRootCause(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }
}
