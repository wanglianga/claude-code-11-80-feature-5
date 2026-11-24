package com.flood.statemigrate.web.dto;

import com.flood.statemigrate.domain.InterventionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/** 处置动作写入：泵站抽排 / 道路封闭 */
public record InterventionReq(
        @PositiveOrZero int partitionNo,
        String roadId,
        @NotNull InterventionType type,
        @NotNull Instant at,
        String ref,
        String note
) {
}
