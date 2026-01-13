package cn.itedus.lottery.rpc;

import cn.itedus.lottery.rpc.req.ActivityReq;
import cn.itedus.lottery.rpc.res.ActivityRes;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 * 活动展台
 *  * 1. 创建活动
 *  * 2. 更新活动
 *  * 3. 查询活动
 */
public interface IActivityBooth {

    ActivityRes queryActivityById(ActivityReq req);

}
