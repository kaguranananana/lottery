package com.anontokyo.lottery.infrastructure.dao;

import com.anontokyo.lottery.infrastructure.po.DrawConfigPrize;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */

@Mapper
public interface IDrawConfigPrizeDao {

    List<DrawConfigPrize> queryDrawConfigPrizeList(Long drawConfigId);

    DrawConfigPrize queryDrawConfigPrize(DrawConfigPrize req);

    List<String> querySoldOutPrizeIds(Long drawConfigId);

    /**
     * 扣减库存
     *
     * @param req
     * @return
     */
    int deductStock(DrawConfigPrize req);


    void insertList(List<DrawConfigPrize> drawConfigPrizeList);

}
