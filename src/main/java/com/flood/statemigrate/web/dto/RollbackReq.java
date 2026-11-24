package com.flood.statemigrate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/** 回退请求：撤回 [fromOffset, toOffset] 范围内的新输出 */
public record RollbackReq(
        @PositiveOrZero long fromOffset,
        @PositiveOrZero long toOffset,
        List<Integer> partitions,
        @NotBlank String reason
) {
}
