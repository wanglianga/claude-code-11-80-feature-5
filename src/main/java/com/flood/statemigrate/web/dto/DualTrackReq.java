package com.flood.statemigrate.web.dto;

import jakarta.validation.constraints.PositiveOrZero;

/** 双轨运行请求；partitionNo 为空时全部分区并行 */
public record DualTrackReq(
        @PositiveOrZero Integer partitionNo,
        @PositiveOrZero Long inputCutOffset
) {
}
