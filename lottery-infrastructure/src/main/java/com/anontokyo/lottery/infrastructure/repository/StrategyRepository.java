package com.anontokyo.lottery.infrastructure.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.strategy.model.aggregates.StrategyRich;
import com.anontokyo.lottery.domain.strategy.model.snapshot.StrategySnapshot;
import com.anontokyo.lottery.domain.strategy.model.vo.AwardBriefVO;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyAwardStockDeductResultVO;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyBriefVO;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyDetailBriefVO;
import com.anontokyo.lottery.domain.strategy.repository.IStrategyRepository;
import com.anontokyo.lottery.domain.strategy.service.snapshot.StrategySnapshotFactory;
import com.anontokyo.lottery.infrastructure.cache.TwoLevelCacheService;
import com.anontokyo.lottery.infrastructure.dao.IAwardStockSettlementDao;
import com.anontokyo.lottery.infrastructure.dao.IAwardDao;
import com.anontokyo.lottery.infrastructure.dao.IStrategyDao;
import com.anontokyo.lottery.infrastructure.dao.IStrategyDetailDao;
import com.anontokyo.lottery.infrastructure.po.AwardStockSettlement;
import com.anontokyo.lottery.infrastructure.po.Award;
import com.anontokyo.lottery.infrastructure.po.Strategy;
import com.anontokyo.lottery.infrastructure.po.StrategyDetail;
import com.anontokyo.lottery.infrastructure.utils.RedisUtil;
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
public class StrategyRepository implements IStrategyRepository {

    private Logger logger = LoggerFactory.getLogger(StrategyRepository.class);

    @Resource
    private IStrategyDao strategyDao;

    @Resource
    private IStrategyDetailDao strategyDetailDao;

    @Resource
    private IAwardStockSettlementDao awardStockSettlementDao;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private IAwardDao awardDao;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private TwoLevelCacheService twoLevelCacheService;

    private final StrategySnapshotFactory snapshotFactory = new StrategySnapshotFactory();

    @Override
    public StrategySnapshot queryStrategySnapshot(Long strategyId) {
        return twoLevelCacheService.getStrategySnapshotCache(
                Constants.RedisKey.KEY_LOTTERY_CACHE_STRATEGY_SNAPSHOT(strategyId),
                StrategySnapshot.class,
                () -> buildSnapshotFromDb(strategyId));
    }

    private StrategySnapshot buildSnapshotFromDb(Long strategyId) {
        Strategy strategy = strategyDao.queryStrategy(strategyId);
        List<StrategyDetail> details = strategyDetailDao.queryStrategyDetailList(strategyId);

        return snapshotFactory.build(new StrategyRich(strategyId, toStrategyVO(strategy), toStrategyDetailVOList(details)),
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
            ledgerRow.setStrategyId(settlement.getStrategyId());
            ledgerRow.setAwardId(settlement.getAwardId());
            try {
                awardStockSettlementDao.insert(ledgerRow);
            } catch (DuplicateKeyException e) {
                logger.info("award stock settlement already done for orderId:{}, skip deduction",
                        settlement.getOrderId());
                return true;
            }

            boolean deducted = deductStock(settlement.getStrategyId(), settlement.getAwardId());
            if (!deducted) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(settled);
    }

    /**
     * Redis 原子预扣奖品库存。key 不存在时不回填 MySQL（结算滞后值会造成库存复活）：
     * 视为不可售并记录 error，由活动启动预热或人工对账恢复。
     */
    @Override
    public StrategyAwardStockDeductResultVO deductStockByRedis(Long strategyId, String awardId) {
        String stockKey = Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_COUNT(strategyId, awardId);
        long currentStockSurplusCount;
        try {
            String cacheValue = redisUtil.get(stockKey);
            if (cacheValue == null || cacheValue.length() == 0) {
                logger.error("strategy award stock key missing on running strategy, treated as sold out. "
                        + "run stock warmup/recovery, strategyId:{} awardId:{} key:{}", strategyId, awardId, stockKey);
                return StrategyAwardStockDeductResultVO.soldOut();
            }
            currentStockSurplusCount = Long.parseLong(cacheValue);
        } catch (Exception e) {
            logger.error("read strategy award stock from redis failed, fail closed. strategyId:{} awardId:{}",
                    strategyId, awardId, e);
            throw new IllegalStateException("strategy award stock state unavailable", e);
        }

        if (currentStockSurplusCount <= 0) {
            return StrategyAwardStockDeductResultVO.soldOut();
        }

        long stockSurplusCount = redisUtil.decr(stockKey, 1);
        if (stockSurplusCount < 0) {
            redisUtil.incr(stockKey, 1);
            return StrategyAwardStockDeductResultVO.soldOut();
        }
        return StrategyAwardStockDeductResultVO.success();
    }

    @Override
    public void recoverStrategyAwardStockByRedis(Long strategyId, String awardId) {
        String stockKey = Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_COUNT(strategyId, awardId);
        try {
            redisUtil.incr(stockKey, 1);
        } catch (Exception e) {
            logger.error("recover strategy award stock by redis failed, strategyId:{} awardId:{}", strategyId,
                    awardId, e);
        }
    }

    private StrategyBriefVO toStrategyVO(Strategy strategy) {
        if (strategy == null) {
            return null;
        }
        StrategyBriefVO vo = new StrategyBriefVO();
        vo.setId(strategy.getId());
        vo.setStrategyId(strategy.getStrategyId());
        vo.setStrategyDesc(strategy.getStrategyDesc());
        vo.setStrategyMode(strategy.getStrategyMode());
        vo.setGrantType(strategy.getGrantType());
        vo.setGrantDate(strategy.getGrantDate());
        vo.setExtInfo(strategy.getExtInfo());
        return vo;
    }

    private List<StrategyDetailBriefVO> toStrategyDetailVOList(List<StrategyDetail> list) {
        if (list == null || list.isEmpty()) {
            return new ArrayList<>();
        }
        List<StrategyDetailBriefVO> voList = new ArrayList<>(list.size());
        for (StrategyDetail detail : list) {
            StrategyDetailBriefVO vo = new StrategyDetailBriefVO();
            vo.setId(detail.getId());
            vo.setStrategyId(detail.getStrategyId());
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
        vo.setAwardCount(award.getAwardCount());
        vo.setAwardName(award.getAwardName());
        vo.setAwardContent(award.getAwardContent());
        return vo;
    }

    /**
     * 结算账本本地事务内完成「INSERT 账本 + 条件扣减库存」，供 settleAwardStock 使用。
     */
    private boolean deductStock(Long strategyId, String awardId) {
        StrategyDetail req = new StrategyDetail();
        req.setStrategyId(strategyId);
        req.setAwardId(awardId);
        int count = strategyDetailDao.deductStock(req);
        return count == 1;
    }
}
