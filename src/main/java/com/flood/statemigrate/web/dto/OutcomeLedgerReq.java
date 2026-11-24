package com.flood.statemigrate.web.dto;

import com.flood.statemigrate.domain.*;

import java.time.Instant;

/** 冻结结果账条目请求：冻结当时的规则版本、状态水印、输入可信度、风险输出与派单干预 */
public record OutcomeLedgerReq(
        int partitionNo,
        String roadId,
        String roadType,
        String basin,
        Instant windowStart,
        Instant windowEnd,
        Instant outputTime,
        String ruleVersion,
        String stateVersion,
        long watermarkOffset,
        double inputCredibility,
        RiskLevel riskLevel,
        double riskScore,
        boolean dispatchPresent,
        InterventionType interventionType,
        Instant interventionAt,
        String interventionRef,
        OutcomeStage stage,
        String exposureGroup,
        Instant ruleChangeBoundary,
        Instant observationWindowEnd
) {
}
