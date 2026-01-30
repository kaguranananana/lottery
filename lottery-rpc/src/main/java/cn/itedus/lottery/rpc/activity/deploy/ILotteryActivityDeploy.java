package cn.itedus.lottery.rpc.activity.deploy;

import cn.itedus.lottery.rpc.activity.deploy.req.ActivityPageReq;
import cn.itedus.lottery.rpc.activity.deploy.res.ActivityRes;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 * 活动展台
 *  * 1. 创建活动
 *  * 2. 更新活动
 *  * 3. 查询活动
 */
public interface ILotteryActivityDeploy {

    ActivityRes queryActivityListByPageForErp(ActivityPageReq req);

}
