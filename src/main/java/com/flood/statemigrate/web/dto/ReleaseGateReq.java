package com.flood.statemigrate.web.dto;

import jakarta.validation.constraints.PositiveOrZero;

/** 上线门禁请求；为空时使用默认策略（最低覆盖 0.5、最小严格样本 1、漏报率上限 0.2） */
public record ReleaseGateReq(
        @PositiveOrZero Double minCoverage,
        @PositiveOrZero Integer minStrictSamples,
        @PositiveOrZero Double safetyFloor
) {
}
