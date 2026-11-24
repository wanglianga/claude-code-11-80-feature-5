package com.flood.statemigrate.domain;

/** 迁移路线 */
public enum PlanRoute {
    ADAPT,   // 经证明的状态适配器：旧聚合拆解/提升为新模式
    REPLAY,  // 限定时间窗回放原始事件重算
    MANUAL   // 人工裁定
}
