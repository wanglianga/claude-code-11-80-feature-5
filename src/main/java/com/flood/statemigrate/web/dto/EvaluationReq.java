package com.flood.statemigrate.web.dto;

import com.flood.statemigrate.domain.EvaluationRoute;

import java.time.Instant;

/**
 * 校准评估请求：
 * route 为空默认 STRICT_VERIFIED；asOf 为空用当前时刻（右删失按此判定）；
 * replayOfRunId 用于事后标签到达后对同一评估的右删失版本重评。
 */
public record EvaluationReq(
        EvaluationRoute route,
        Instant asOf,
        Long replayOfRunId
) {
}
