package com.anontokyo.lottery.infrastructure.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.infrastructure.dao.IUserStrategyExportDao;
import com.anontokyo.lottery.infrastructure.po.UserStrategyExport;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * @description: 奖品表仓储服务
 * @author：anontokyo
 * @date: 2026/1/10
 */

@Component
public class OrderRepository implements IOrderRepository {

    @Resource
    private IUserStrategyExportDao userStrategyExportDao;

    @Override
    public void updateUserAwardState(String uId, Long orderId, String awardId, Integer grantState) {
        UserStrategyExport userStrategyExport = new UserStrategyExport();
        userStrategyExport.setuId(uId);
        userStrategyExport.setOrderId(orderId);
        userStrategyExport.setAwardId(awardId);
        userStrategyExport.setGrantState(grantState);
        userStrategyExportDao.updateUserAwardState(userStrategyExport);
    }

    @Override
    public boolean isAwardCompleted(String uId, Long orderId) {
        UserStrategyExport req = new UserStrategyExport();
        req.setuId(uId);
        req.setUuid(String.valueOf(orderId));
        UserStrategyExport userStrategyExport = userStrategyExportDao.queryUserStrategyExportByUuid(req);
        return userStrategyExport != null
                && Constants.GrantState.COMPLETE.getCode().equals(userStrategyExport.getGrantState());
    }
}
