package com.flood.statemigrate.web.dto;

import com.flood.statemigrate.domain.LabelSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * 现场观测写入：eventTime 为事件时间（视频峰值补传时填峰值时刻），
 * observedAt 为到达评估系统的时间；二者之差用于延迟与右删失判定。
 */
public record ObservationReq(
        @PositiveOrZero int partitionNo,
        @NotBlank String roadId,
        String basin,
        @NotNull LabelSource source,
        @PositiveOrZero double depthCm,
        @NotNull Instant eventTime,
        @NotNull Instant observedAt,
        String note
) {
}
