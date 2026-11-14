package com.anontokyo.lottery.infrastructure.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.draw.model.snapshot.ActivityPrizeSnapshot;
import com.anontokyo.lottery.domain.draw.model.vo.AwardBriefVO;
import com.anontokyo.lottery.domain.draw.model.vo.ActivityPrizeBriefVO;
import com.anontokyo.lottery.domain.draw.repository.IActivityPrizeRepository;
import com.anontokyo.lottery.domain.draw.service.snapshot.ActivityPrizeSnapshotFactory;
import com.anontokyo.lottery.infrastructure.cache.TwoLevelCacheService;
import com.anontokyo.lottery.infrastructure.dao.IAwardStockSettlementDao;
import com.anontokyo.lottery.infrastructure.dao.IAwardDao;
import com.anontokyo.lottery.infrastructure.dao.IActivityPrizeDao;
import com.anontokyo.lottery.infrastructure.po.AwardStockSettlement;
import com.anontokyo.lottery.infrastructure.po.Award;
import com.anontokyo.lottery.infrastructure.po.ActivityPrize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */

@Repository
public class ActivityPrizeRepository implements IActivityPrizeRepository {

    private Logger logger = LoggerFactory.getLogger(ActivityPrizeRepository.class);

    @Resource
    private IActivityPrizeDao activityPrizeDao;

    @Resource
    private IAwardStockSettlementDao awardStockSettlementDao;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private IAwardDao awardDao;

    @Resource
    private TwoLevelCacheService twoLevelCacheService;

    private final ActivityPrizeSnapshotFactory snapshotFactory = new ActivityPrizeSnapshotFactory();

    @Override
    public ActivityPrizeSnapshot queryActivityPrizeSnapshot(Long activityId) {
        return twoLevelCacheService.getActivityPrizeSnapshotCache(
                Constants.RedisKey.KEY_LOTTERY_CACHE_ACTIVITY_PRIZE_SNAPSHOT(activityId),
                ActivityPrizeSnapshot.class,
                () -> buildSnapshotFromDb(activityId));
    }

    private ActivityPrizeSnapshot buildSnapshotFromDb(Long activityId) {
        List<ActivityPrize> details = activityPrizeDao.queryActivityPrizeList(activityId);

        return snapshotFactory.build(activityId, toActivityPrizeVOList(details),
                this::queryAwardInfoFromDb);
    }

    private AwardBriefVO queryAwardInfoFromDb(String awardId) {
        return toAwardVO(awardDao.queryAwardInfo(awardId));
    }

    @Override
    public boolean settleAwardStock(AwardStockSettlementVO settlement) {
        Boolean settled = transactionTemplate.execute(status -> {
            AwardStockSettlement ledgerRow = new AwardStockSettlement();
            ledgerRow.setOrderId(settlement.getOrderId());
            ledgerRow.setuId(settlement.getuId());
            ledgerRow.setActivityId(settlement.getActivityId());
            ledgerRow.setAwardId(settlement.getAwardId());
            try {
                awardStockSettlementDao.insert(ledgerRow);
            } catch (DuplicateKeyException e) {
                logger.info("award stock settlement already done for orderId:{}, skip deduction",
                        settlement.getOrderId());
                return true;
            }

            boolean deducted = deductStock(settlement.getActivityId(), settlement.getAwardId());
            if (!deducted) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(settled);
    }

    private List<ActivityPrizeBriefVO> toActivityPrizeVOList(List<ActivityPrize> list) {
        if (list == null || list.isEmpty()) {
            return new ArrayList<>();
        }
        List<ActivityPrizeBriefVO> voList = new ArrayList<>(list.size());
        for (ActivityPrize detail : list) {
            ActivityPrizeBriefVO vo = new ActivityPrizeBriefVO();
            vo.setId(detail.getId());
            vo.setActivityId(detail.getActivityId());
            vo.setAwardId(detail.getAwardId());
            vo.setAwardCount(detail.getAwardCount());
            vo.setAwardRate(detail.getAwardRate());
            voList.add(vo);
        }
        return voList;
    }

    private AwardBriefVO toAwardVO(Award award) {
        if (award == null) {
            return null;
        }
        AwardBriefVO vo = new AwardBriefVO();
        vo.setId(award.getId());
        vo.setAwardId(award.getAwardId());
        vo.setAwardType(award.getAwardType());
        vo.setAwardName(award.getAwardName());
        vo.setAwardContent(award.getAwardContent());
        return vo;
    }

    /**
     * 结算账本本地事务内完成「INSERT 账本 + 条件扣减库存」，供 settleAwardStock 使用。
     */
    private boolean deductStock(Long activityId, String awardId) {
        ActivityPrize req = new ActivityPrize();
        req.setActivityId(activityId);
        req.setAwardId(awardId);
        int count = activityPrizeDao.deductStock(req);
        return count == 1;
    }
}
