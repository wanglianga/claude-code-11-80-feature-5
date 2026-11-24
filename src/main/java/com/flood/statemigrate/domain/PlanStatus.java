package com.flood.statemigrate.domain;

public enum PlanStatus {
    PLANNED,
    RUNNING,
    DONE,
    FAILED,
    STALE   // 绑定的证明/规则包已过期，需重新编排
}
