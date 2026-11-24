package com.flood.statemigrate.web.dto;

import com.flood.statemigrate.domain.RuleKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 规则包登记/二次修订请求 */
public record RulePackageReq(
        @NotBlank String version,
        @NotNull RuleKind kind,
        @NotBlank String configJson
) {
}
