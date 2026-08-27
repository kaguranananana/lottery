package com.anontokyo.lottery.infrastructure.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.strategy.model.aggregates.StrategyRich;
import com.anontokyo.lottery.domain.strategy.model.vo.AwardBriefVO;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyAwardStockDeductResultVO;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyBriefVO;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyDetailBriefVO;
import com.anontokyo.lottery.domain.strategy.repository.IStrategyRepository;
import com.anontokyo.lottery.infrastructure.cache.CacheConfigProperties;
import com.anontokyo.lottery.infrastructure.cache.TwoLevelCacheService;
import com.anontokyo.lottery.infrastructure.dao.IAwardDao;
import com.anontokyo.lottery.infrastructure.dao.IStrategyDao;
import com.anontokyo.lottery.infrastructure.dao.IStrategyDetailDao;
import com.anontokyo.lottery.infrastructure.po.Award;
import com.anontokyo.lottery.infrastructure.po.Strategy;
import com.anontokyo.lottery.infrastructure.po.StrategyDetail;
import com.anontokyo.lottery.infrastructure.utils.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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
    private IAwardDao awardDao;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private TwoLevelCacheService twoLevelCacheService;

    @Resource
    private CacheConfigProperties cacheConfigProperties;


    @Override
    public StrategyRich queryStrategyRich(Long strategyId) {
        return twoLevelCacheService.getStrategyRichCache(
                Constants.RedisKey.KEY_LOTTERY_CACHE_STRATEGY_RICH(strategyId),
                StrategyRich.class,
                () -> queryStrategyRichFromDb(strategyId));
    }

    @Override
    public AwardBriefVO queryAwardInfo(String awardId) {
        return twoLevelCacheService.getAwardCache(
                Constants.RedisKey.KEY_LOTTERY_CACHE_AWARD(awardId),
                AwardBriefVO.class,
                () -> {
                    Award award = awardDao.queryAwardInfo(awardId);
                    return toAwardVO(award);
                });
    }

    @Override
    public boolean deductStock(Long strategyId, String awardId) {
        StrategyDetail req = new StrategyDetail();
        req.setStrategyId(strategyId);
        req.setAwardId(awardId);
        int count = strategyDetailDao.deductStock(req);
        return count == 1;
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

    @Override
    public void recoverStock(Long strategyId, String awardId) {
        StrategyDetail req = new StrategyDetail();
        req.setStrategyId(strategyId);
        req.setAwardId(awardId);
        int count = strategyDetailDao.recoverStock(req);
        if (count == 0) {
            logger.warn("recover strategy detail stock failed, strategyId:{} awardId:{}", strategyId, awardId);
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

    private StrategyRich queryStrategyRichFromDb(Long strategyId) {
        Strategy strategy = strategyDao.queryStrategy(strategyId);
        List<StrategyDetail> strategyDetails = strategyDetailDao.queryStrategyDetailList(strategyId);

        StrategyBriefVO strategyVO = toStrategyVO(strategy);
        List<StrategyDetailBriefVO> detailVOList = toStrategyDetailVOList(strategyDetails);

        return new StrategyRich(strategyId, strategyVO, detailVOList);
    }
}
