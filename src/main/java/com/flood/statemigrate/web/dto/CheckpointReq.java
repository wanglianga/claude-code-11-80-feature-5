package com.flood.statemigrate.web.dto;

import com.flood.statemigrate.domain.CheckpointStatus;
import com.flood.statemigrate.domain.StateSchema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/** 检查点登记请求 */
public record CheckpointReq(
        @PositiveOrZero int partitionNo,
        @PositiveOrZero long consumeOffset,
        @NotNull Instant windowWatermark,
        String stateSnapshotJson,
        String outputBatchId,
        @NotNull String rulePackageVersion,
        @NotNull StateSchema stateSchema,
        @NotNull CheckpointStatus status
) {
}
