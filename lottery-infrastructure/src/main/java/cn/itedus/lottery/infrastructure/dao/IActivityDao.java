package cn.itedus.lottery.infrastructure.dao;

import cn.itedus.lottery.infrastructure.po.Activity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 博客：https://bugstack.cn - 沉淀、分享、成长，让自己和他人都能有所收获！
 * 公众号：bugstack虫洞栈
 * Create by 小傅哥(fustack)
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
