package cn.itedus.lottery.infrastructure.dao;


import cn.itedus.lottery.infrastructure.po.Award;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface IAwardDao {
    /**
     * 查询奖品信息
     * @param awardId
     * @return
     */
    Award queryAwardInfo(String awardId);

    void insertList(List<Award> list);
}
