package cn.itedus.lottery.infrastructure.dao;

import cn.itedus.lottery.infrastructure.po.Activity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */
@Mapper
public interface IActivityDao {

   void insert(Activity req);

   Activity queryActivityById(Long activityId);

   /**
    * 活动状态变更（乐观更新：必须匹配变更前状态）。
    */
   int alterState(@Param("activityId") Long activityId,
                  @Param("beforeState") Integer beforeState,
                  @Param("afterState") Integer afterState);

}
