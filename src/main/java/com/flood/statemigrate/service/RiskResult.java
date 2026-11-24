package com.flood.statemigrate.service;

import com.flood.statemigrate.domain.RiskLevel;

import java.util.Map;

public record RiskResult(RiskLevel level, double score, Map<String, Object> detail) {
}
