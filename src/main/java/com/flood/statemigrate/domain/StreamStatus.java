package com.flood.statemigrate.domain;

public enum StreamStatus {
    ACTIVE,       // 旧规则正常服务
    MIGRATING,    // 迁移计划已生成/执行中
    DUAL_TRACK,   // 双轨校验中
    SWITCHED,     // 闸门已发布新输出
    ROLLED_BACK   // 已回退到旧规则只读快照
}
