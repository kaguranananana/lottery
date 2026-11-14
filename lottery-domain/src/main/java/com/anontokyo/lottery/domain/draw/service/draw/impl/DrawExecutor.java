package com.anontokyo.lottery.domain.draw.service.draw.impl;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.draw.model.req.DrawReq;
import com.anontokyo.lottery.domain.draw.model.res.DrawResult;
import com.anontokyo.lottery.domain.draw.model.snapshot.ActivityPrizeSnapshot;
import com.anontokyo.lottery.domain.draw.model.vo.AwardBriefVO;
import com.anontokyo.lottery.domain.draw.model.vo.DrawAwardInfo;
import com.anontokyo.lottery.domain.draw.repository.IActivityPrizeRepository;
import com.anontokyo.lottery.domain.draw.service.draw.IDrawExec;
import com.anontokyo.lottery.domain.draw.service.draw.WeightedRandomSelector;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 抽奖执行：读取活动奖池快照 → 权重随机选择。
 * 快照中原始概率语义保持不变；命中已售罄奖品直接视为未中奖，不做重新归一化。
 */
@Service("drawExec")
public class DrawExecutor implements IDrawExec {

    @Resource
    private IActivityPrizeRepository activityPrizeRepository;

    @Override
    public DrawResult doDrawExec(DrawReq req) {
        ActivityPrizeSnapshot snapshot = activityPrizeRepository.queryActivityPrizeSnapshot(req.getActivityId());

        String awardId = WeightedRandomSelector.select(snapshot);
        if (awardId == null) {
            return new DrawResult(req.getuId(), req.getActivityId(), Constants.DrawState.FAIL.getCode());
        }

        AwardBriefVO award = snapshot.getAwardInfos().get(awardId);
        // 返回候选奖品。Half Message 确认后由 TX2 预扣库存并决定是否中奖。
        DrawAwardInfo drawAwardInfo = new DrawAwardInfo(
                awardId,
                award.getAwardType(),
                award.getAwardName(),
                award.getAwardContent());

        return new DrawResult(req.getuId(), req.getActivityId(),
                Constants.DrawState.SUCCESS.getCode(), drawAwardInfo);
    }

}
