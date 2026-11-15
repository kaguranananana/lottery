package com.anontokyo.lottery.infrastructure.dao;

import com.anontokyo.lottery.infrastructure.po.AwardStockBucket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface IAwardStockBucketDao {
    int insertList(@Param("rows") List<AwardStockBucket> rows);
    int deductOne(@Param("activityId") Long activityId,
                  @Param("awardId") String awardId,
                  @Param("bucketId") int bucketId);
}
