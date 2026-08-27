package com.anontokyo.lottery.domain.support.ids;

/**
 * 全局唯一 ID 生成器。当前唯一实现为 Snowflake；保留接口是为了让依赖方
 * 不必知道具体算法（例如未来替换成号段模式时不改调用方代码）。
 */
public interface IdGenerator {

    long nextId();
}
