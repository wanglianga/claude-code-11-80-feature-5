package com.flood.statemigrate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/** 事件写入请求（位点由服务端按分区分配） */
public record EventReq(
        @PositiveOrZero int partitionNo,
        @NotNull Instant eventTime,
        @NotBlank String type,
        String roadId,
        String pumpId,
        String reachId,
        @NotBlank String payloadJson
) {
}
