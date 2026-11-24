package com.flood.statemigrate.web.dto;

import com.flood.statemigrate.domain.PlanRoute;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 人工裁定请求 */
public record AdjudicateReq(@NotNull PlanRoute route, @NotBlank String reason) {
}
