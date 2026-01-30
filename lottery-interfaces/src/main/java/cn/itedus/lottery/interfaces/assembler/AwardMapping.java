package cn.itedus.lottery.interfaces.assembler;

import cn.itedus.lottery.domain.strategy.model.vo.DrawAwardInfo;
import cn.itedus.lottery.rpc.activity.booth.dto.AwardDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * @description: 奖品对象转换器
 * @author：anontokyo
 * @date: 2026/1/25
 * 
 *        将领域层 DrawAwardInfo 转换为接口层 AwardDTO
 * 
 *        配置说明：
 *        - componentModel = "spring": 生成的实现类会添加 @Component 注解，可被 Spring 注入
 *        - unmappedTargetPolicy = IGNORE: 未映射的目标字段会被忽略（如 userId, activityId
 *        需要手动设置）
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AwardMapping extends IMapping<DrawAwardInfo, AwardDTO> {

    /**
     * DrawAwardInfo → AwardDTO
     * 
     * 字段映射：
     * - awardId, awardType, awardName, awardContent, strategyMode, grantType,
     * grantDate 自动映射（同名）
     * - userId, activityId 忽略（在门面层手动设置）
     */
    @Override
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "activityId", ignore = true)
    AwardDTO sourceToTarget(DrawAwardInfo source);

    /**
     * AwardDTO → DrawAwardInfo（反向转换）
     */
    @Override
    DrawAwardInfo targetToSource(AwardDTO target);

}
