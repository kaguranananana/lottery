package cn.itedus.lottery.domain.strategy.service.draw;

import cn.itedus.lottery.domain.strategy.model.req.DrawReq;
import cn.itedus.lottery.domain.strategy.model.res.DrawResult;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */

public interface IDrawExec {

    DrawResult doDrawExec(DrawReq req);
}
