package com.flood.statemigrate.domain;

/** 处置动作类型：用于时间对齐与受处置影响判定 */
public enum InterventionType {
    PUMP_ACTION,  // 泵站抽排
    ROAD_CLOSURE  // 道路封闭（会使后续视频标签缺失）
}
