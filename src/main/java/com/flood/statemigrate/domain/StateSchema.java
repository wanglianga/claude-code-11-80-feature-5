package com.flood.statemigrate.domain;

/** 状态模式：旧=网格窗口单层状态；新=泵站—河段—道路三层中间状态 */
public enum StateSchema {
    GRID_WINDOW_V1,
    PUMP_REACH_ROAD_V2
}
