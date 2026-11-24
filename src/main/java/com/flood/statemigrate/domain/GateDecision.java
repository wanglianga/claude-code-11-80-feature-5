package com.flood.statemigrate.domain;

/** 上线门禁结论 */
public enum GateDecision {
    ALLOW,  // 覆盖与安全下限满足，可按允许范围扩大规则
    BLOCK   // 维持现状，禁止扩大
}
