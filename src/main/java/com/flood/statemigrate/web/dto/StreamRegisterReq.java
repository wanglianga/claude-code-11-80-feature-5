package com.flood.statemigrate.web.dto;

import com.flood.statemigrate.domain.StateSchema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 流定义登记请求 */
public record StreamRegisterReq(
        @NotBlank String streamCode,
        @NotBlank String name,
        @NotBlank String inputTopic,
        @NotBlank String eventTimeField,
        @Positive double waterLevelBaseline,
        @NotNull StateSchema stateSchema,
        @NotBlank String rulePackageVersion,
        String targetRulePackageVersion,
        @Positive int totalPartitions,
        @Positive int eventRetentionHours,
        @NotBlank String partitionConfigJson
) {
}
