package com.flood.statemigrate.web.dto;

import java.time.Instant;

/** 计划执行请求；可覆盖回放窗口（用于越过切换水印的复核回放） */
public record PlanExecuteReq(Instant replayWindowStart, Instant replayWindowEnd) {
}
