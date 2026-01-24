package cn.itedus.lottery.interfaces.assembler;

import org.mapstruct.InheritConfiguration;
import org.mapstruct.InheritInverseConfiguration;
import org.mapstruct.MapperConfig;

import java.util.List;
import java.util.stream.Stream;

/**
 * @description: 通用对象转换接口（泛型）
 * @author：anontokyo
 * @date: 2026/1/25
 * 
 *        定义对象转换的通用方法，可被具体转换器继承
 * 
 * @param <SOURCE> 源对象类型
 * @param <TARGET> 目标对象类型
 */
@MapperConfig
public interface IMapping<SOURCE, TARGET> {

    /**
     * 源对象转目标对象
     * 
     * @param source 源对象
     * @return 目标对象
     */
    TARGET sourceToTarget(SOURCE source);

    /**
     * 目标对象转源对象（反向转换）
     * 
     * @param target 目标对象
     * @return 源对象
     */
    @InheritInverseConfiguration(name = "sourceToTarget")
    SOURCE targetToSource(TARGET target);

    /**
     * 批量转换：源对象列表转目标对象列表
     * 
     * @param sources 源对象列表
     * @return 目标对象列表
     */
    @InheritConfiguration(name = "sourceToTarget")
    List<TARGET> sourceToTarget(List<SOURCE> sources);

    /**
     * 批量转换：目标对象列表转源对象列表
     * 
     * @param targets 目标对象列表
     * @return 源对象列表
     */
    @InheritConfiguration(name = "targetToSource")
    List<SOURCE> targetToSource(List<TARGET> targets);

    /**
     * 流式转换：源对象流转目标对象流
     * 
     * @param sources 源对象流
     * @return 目标对象流
     */
    default Stream<TARGET> sourceToTarget(Stream<SOURCE> sources) {
        return sources.map(this::sourceToTarget);
    }

    /**
     * 流式转换：目标对象流转源对象流
     * 
     * @param targets 目标对象流
     * @return 源对象流
     */
    default Stream<SOURCE> targetToSource(Stream<TARGET> targets) {
        return targets.map(this::targetToSource);
    }

}
