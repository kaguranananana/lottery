package com.anontokyo.lottery.infrastructure.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.strategy.model.aggregates.StrategyRich;
import com.anontokyo.lottery.domain.strategy.model.vo.AwardBriefVO;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyBriefVO;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyDetailBriefVO;
import com.anontokyo.lottery.domain.strategy.repository.IStrategyRepository;
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
    private IAwardDao awardDao;

    @Resource
    private RedisUtil redisUtil;


    @Override
    public StrategyRich queryStrategyRich(Long strategyId) {
        Strategy strategy = strategyDao.queryStrategy(strategyId);
        List<StrategyDetail> strategyDetails = strategyDetailDao.queryStrategyDetailList(strategyId);

        StrategyBriefVO strategyVO = toStrategyVO(strategy);
        List<StrategyDetailBriefVO> detailVOList = toStrategyDetailVOList(strategyDetails);

        return new StrategyRich(strategyId, strategyVO, detailVOList);
    }

    @Override
    public AwardBriefVO queryAwardInfo(String awardId) {
        Award award = awardDao.queryAwardInfo(awardId);
        return toAwardVO(award);
    }

    @Override
    public List<String> queryNoStockStrategyAwardList(Long strategyId) {
        return strategyDetailDao.queryNoStockStrategyAwardList(strategyId);
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
    public boolean deductStockByRedis(Long strategyId, String awardId, Integer awardCount) {
        // 获取策略奖品库存key
        String stockKey = Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_COUNT(strategyId, awardId);

        // 扣减库存, 使用Redis incr 原子操作
        Integer stockUsedCount = (int) redisUtil.incr(stockKey, 1);

        // 超出库存判断, 进行恢复
        if (stockUsedCount > awardCount) {
            redisUtil.decr(stockKey, 1);
            return false;
        }

        // 使用Redis.setNx 加上一个分布式锁, 保证同一个库存位不被重复消费
        String stockTokenKey = Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_COUNT_TOKEN(strategyId, awardId, stockUsedCount);
        boolean lockToken = redisUtil.setNx(stockTokenKey, 350L);

        if (!lockToken) {
            logger.info("策略奖品库存扣减, 分布式锁失败: strategyId:{} awardId:{} stockTokenKey:{}", strategyId, awardId, stockTokenKey);
            redisUtil.decr(stockKey, 1);
            return false;
        }

        return true;
    }

    @Override
    public void recoverStrategyAwardStockByRedis(String stockTokenKey) {
        redisUtil.del(stockTokenKey);
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
}
