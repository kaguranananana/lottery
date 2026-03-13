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
    public List<String> queryNoStockStrategyAwardList(Long strategyId) {
        String cacheKey = Constants.RedisKey.KEY_LOTTERY_CACHE_STRATEGY_NO_STOCK_AWARD(strategyId);
        try {
            Set<String> awardSet = redisUtil.sMembers(cacheKey);
            if (awardSet == null || awardSet.isEmpty()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(awardSet);
        } catch (Exception e) {
            logger.warn("query no-stock award list from redis failed, strategyId:{}", strategyId, e);
            return Collections.emptyList();
        }
    }

    @Override
    public boolean deductStock(Long strategyId, String awardId) {
        StrategyDetail req = new StrategyDetail();
        req.setStrategyId(strategyId);
        req.setAwardId(awardId);
        int count = strategyDetailDao.deductStock(req);
        return count == 1;
    }

    @Override
    public StrategyAwardStockDeductResultVO deductStockByRedis(Long strategyId, String awardId) {
        String stockKey = Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_COUNT(strategyId, awardId);
        long currentStockSurplusCount = initStrategyAwardStockIfAbsent(strategyId, awardId, stockKey);
        if (currentStockSurplusCount <= 0) {
            markNoStockAward(strategyId, awardId);
            return StrategyAwardStockDeductResultVO.soldOut();
        }

        long stockSurplusCount = redisUtil.decr(stockKey, 1);
        if (stockSurplusCount < 0) {
            redisUtil.incr(stockKey, 1);
            markNoStockAward(strategyId, awardId);
            return StrategyAwardStockDeductResultVO.soldOut();
        }
        if (stockSurplusCount == 0) {
            markNoStockAward(strategyId, awardId);
        }
        return StrategyAwardStockDeductResultVO.success();
    }

    @Override
    public void recoverStrategyAwardStockByRedis(Long strategyId, String awardId) {
        String stockKey = Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_COUNT(strategyId, awardId);
        try {
            long stockSurplusCount = redisUtil.incr(stockKey, 1);
            if (stockSurplusCount > 0) {
                clearNoStockAward(strategyId, awardId);
            }
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

    private void markNoStockAward(Long strategyId, String awardId) {
        String cacheKey = Constants.RedisKey.KEY_LOTTERY_CACHE_STRATEGY_NO_STOCK_AWARD(strategyId);
        try {
            redisUtil.sAdd(cacheKey, awardId);
            long ttlSeconds = cacheConfigProperties.getNoStockAwardListRedisTtlSeconds();
            if (ttlSeconds > 0) {
                redisUtil.expire(cacheKey, ttlSeconds);
            }
        } catch (Exception e) {
            logger.warn("mark no-stock award in redis failed, strategyId:{} awardId:{}", strategyId, awardId, e);
        }
    }

    private void clearNoStockAward(Long strategyId, String awardId) {
        String cacheKey = Constants.RedisKey.KEY_LOTTERY_CACHE_STRATEGY_NO_STOCK_AWARD(strategyId);
        try {
            redisUtil.sRem(cacheKey, awardId);
        } catch (Exception e) {
            logger.warn("clear no-stock award in redis failed, strategyId:{} awardId:{}", strategyId, awardId, e);
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

    private long initStrategyAwardStockIfAbsent(Long strategyId, String awardId, String stockKey) {
        try {
            String cacheValue = redisUtil.get(stockKey);
            if (cacheValue != null && cacheValue.length() > 0) {
                return Long.parseLong(cacheValue);
            }
        } catch (Exception e) {
            logger.warn("read strategy award stock from redis failed, strategyId:{} awardId:{}", strategyId, awardId, e);
        }

        StrategyDetail req = new StrategyDetail();
        req.setStrategyId(strategyId);
        req.setAwardId(awardId);
        StrategyDetail strategyDetail = strategyDetailDao.queryStrategyDetail(req);
        long stockSurplusCount = strategyDetail == null || strategyDetail.getAwardSurplusCount() == null ? 0L
                : strategyDetail.getAwardSurplusCount();
        try {
            boolean initialized = redisUtil.setIfAbsent(stockKey, String.valueOf(stockSurplusCount));
            if (initialized) {
                return stockSurplusCount;
            }
            String latestValue = redisUtil.get(stockKey);
            if (latestValue != null && latestValue.length() > 0) {
                return Long.parseLong(latestValue);
            }
        } catch (Exception e) {
            logger.warn("init strategy award stock in redis failed, strategyId:{} awardId:{}", strategyId, awardId,
                    e);
        }
        return stockSurplusCount;
    }
}
