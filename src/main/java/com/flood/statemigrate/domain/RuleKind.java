package com.flood.statemigrate.domain;

/** 规则包类型：旧=固定阈值；新=多因子（上游水位趋势/泵站启停滞后/传感器可信度） */
public enum RuleKind {
    FIXED_THRESHOLD,
    MULTI_FACTOR
}
