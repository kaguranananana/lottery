package com.anontokyo.lottery.infrastructure.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.draw.model.aggregates.DrawConfigAggregate;
import com.anontokyo.lottery.domain.draw.model.snapshot.DrawConfigSnapshot;
import com.anontokyo.lottery.domain.draw.model.vo.AwardBriefVO;
import com.anontokyo.lottery.domain.draw.model.vo.DrawConfigPrizeStockDeductResultVO;
import com.anontokyo.lottery.domain.draw.model.vo.DrawConfigBriefVO;
import com.anontokyo.lottery.domain.draw.model.vo.DrawConfigPrizeBriefVO;
import com.anontokyo.lottery.domain.draw.repository.IDrawConfigRepository;
import com.anontokyo.lottery.domain.draw.service.snapshot.DrawConfigSnapshotFactory;
import com.anontokyo.lottery.infrastructure.cache.TwoLevelCacheService;
import com.anontokyo.lottery.infrastructure.dao.IAwardStockSettlementDao;
import com.anontokyo.lottery.infrastructure.dao.IAwardDao;
import com.anontokyo.lottery.infrastructure.dao.IDrawConfigDao;
import com.anontokyo.lottery.infrastructure.dao.IDrawConfigPrizeDao;
import com.anontokyo.lottery.infrastructure.po.AwardStockSettlement;
import com.anontokyo.lottery.infrastructure.po.Award;
import com.anontokyo.lottery.infrastructure.po.DrawConfig;
import com.anontokyo.lottery.infrastructure.po.DrawConfigPrize;
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
public class DrawConfigRepository implements IDrawConfigRepository {

    private Logger logger = LoggerFactory.getLogger(DrawConfigRepository.class);

    @Resource
    private IDrawConfigDao drawConfigDao;

    @Resource
    private IDrawConfigPrizeDao drawConfigPrizeDao;

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

    private final DrawConfigSnapshotFactory snapshotFactory = new DrawConfigSnapshotFactory();

    @Override
    public DrawConfigSnapshot queryDrawConfigSnapshot(Long drawConfigId) {
        return twoLevelCacheService.getDrawConfigSnapshotCache(
                Constants.RedisKey.KEY_LOTTERY_CACHE_DRAW_CONFIG_SNAPSHOT(drawConfigId),
                DrawConfigSnapshot.class,
                () -> buildSnapshotFromDb(drawConfigId));
    }

    private DrawConfigSnapshot buildSnapshotFromDb(Long drawConfigId) {
        DrawConfig drawConfig = drawConfigDao.queryDrawConfig(drawConfigId);
        List<DrawConfigPrize> details = drawConfigPrizeDao.queryDrawConfigPrizeList(drawConfigId);

        return snapshotFactory.build(new DrawConfigAggregate(drawConfigId, toDrawConfigVO(drawConfig), toDrawConfigPrizeVOList(details)),
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
            ledgerRow.setDrawConfigId(settlement.getDrawConfigId());
            ledgerRow.setAwardId(settlement.getAwardId());
            try {
                awardStockSettlementDao.insert(ledgerRow);
            } catch (DuplicateKeyException e) {
                logger.info("award stock settlement already done for orderId:{}, skip deduction",
                        settlement.getOrderId());
                return true;
            }

            boolean deducted = deductStock(settlement.getDrawConfigId(), settlement.getAwardId());
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
     * 抛出状态异常并保留原参与，由人工对账恢复后使用原请求继续。
     */
    @Override
    public DrawConfigPrizeStockDeductResultVO deductStockByRedis(Long drawConfigId, String awardId) {
        String stockKey = Constants.RedisKey.KEY_LOTTERY_DRAW_CONFIG_PRIZE_STOCK_COUNT(drawConfigId, awardId);
        try {
            // Lua 将存在性检查与条件扣减合并；缺失 Key 抛出，只有真实售罄才返回 soldOut。
            long remaining = redisUtil.deductStock(stockKey);
            return remaining < 0 ? DrawConfigPrizeStockDeductResultVO.soldOut()
                    : DrawConfigPrizeStockDeductResultVO.success();
        } catch (Exception e) {
            logger.error("deduct drawConfig award stock failed, drawConfigId:{} awardId:{} key:{}",
                    drawConfigId, awardId, stockKey, e);
            throw new IllegalStateException("drawConfig award stock state unavailable", e);
        }
    }

    @Override
    public void recoverDrawConfigPrizeStockByRedis(Long drawConfigId, String awardId) {
        String stockKey = Constants.RedisKey.KEY_LOTTERY_DRAW_CONFIG_PRIZE_STOCK_COUNT(drawConfigId, awardId);
        try {
            redisUtil.recoverStock(stockKey);
        } catch (Exception e) {
            logger.error("recover drawConfig award stock by redis failed, drawConfigId:{} awardId:{}", drawConfigId,
                    awardId, e);
            // 回补失败必须暴露，不能只写日志就让调用方认为补偿已完成。
            throw e;
        }
    }

    private DrawConfigBriefVO toDrawConfigVO(DrawConfig drawConfig) {
        if (drawConfig == null) {
            return null;
        }
        DrawConfigBriefVO vo = new DrawConfigBriefVO();
        vo.setId(drawConfig.getId());
        vo.setDrawConfigId(drawConfig.getDrawConfigId());
        vo.setDrawConfigDesc(drawConfig.getDrawConfigDesc());
        vo.setGrantType(drawConfig.getGrantType());
        vo.setScheduledAt(drawConfig.getScheduledAt());
        return vo;
    }

    private List<DrawConfigPrizeBriefVO> toDrawConfigPrizeVOList(List<DrawConfigPrize> list) {
        if (list == null || list.isEmpty()) {
            return new ArrayList<>();
        }
        List<DrawConfigPrizeBriefVO> voList = new ArrayList<>(list.size());
        for (DrawConfigPrize detail : list) {
            DrawConfigPrizeBriefVO vo = new DrawConfigPrizeBriefVO();
            vo.setId(detail.getId());
            vo.setDrawConfigId(detail.getDrawConfigId());
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
    private boolean deductStock(Long drawConfigId, String awardId) {
        DrawConfigPrize req = new DrawConfigPrize();
        req.setDrawConfigId(drawConfigId);
        req.setAwardId(awardId);
        int count = drawConfigPrizeDao.deductStock(req);
        return count == 1;
    }
}
