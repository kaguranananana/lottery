package cn.itedus.lottery.infrastructure.dao;


import cn.itedus.lottery.infrastructure.po.Strategy;
import cn.itedus.lottery.infrastructure.po.StrategyDetail;
import org.apache.ibatis.annotations.Mapper;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */

@Mapper
public interface IStrategyDao {


    Strategy queryStrategy(Long strategyId);

    void insert(Strategy strategy);
}
