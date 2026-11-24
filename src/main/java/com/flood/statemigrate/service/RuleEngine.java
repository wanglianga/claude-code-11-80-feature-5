package com.flood.statemigrate.service;

import tools.jackson.databind.JsonNode;
import com.flood.statemigrate.domain.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 规则引擎：
 * 旧规则 FIXED_THRESHOLD —— 仅按近两小时降雨累计的固定阈值定级；
 * 新规则 MULTI_FACTOR —— 同时考虑上游水位趋势、泵站启停滞后与传感器可信度。
 */
@Component
public class RuleEngine {

    public RiskResult evaluateOld(JsonNode config, AggInput in) {
        double medium = config.path("medium").asDouble(20.0);
        double high = config.path("high").asDouble(50.0);
        double score = in.rain2h();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rule", "FIXED_THRESHOLD");
        detail.put("rain2h", in.rain2h());
        detail.put("mediumThreshold", medium);
        detail.put("highThreshold", high);
        return new RiskResult(level(score, medium, high), score, detail);
    }

    public RiskResult evaluateNew(JsonNode config, AggInput in) {
        double medium = config.path("medium").asDouble(25.0);
        double high = config.path("high").asDouble(55.0);
        double wRain = config.path("wRain").asDouble(0.6);
        double wTrend = config.path("wTrend").asDouble(1.0);
        double wLag = config.path("wLag").asDouble(0.15);
        double wDepth = config.path("wDepth").asDouble(0.2);
        double credFloor = config.path("credibilityFloor").asDouble(0.5);

        double raw = wRain * in.rain2h()
                + wTrend * in.trendCmPerHour()
                + wLag * in.pumpLagMin()
                + wDepth * in.reportDepthCm();
        // 可信度越低，风险估计越保守（向上修正）
        double clampedCred = Math.max(credFloor, Math.min(1.0, in.credibility()));
        double credFactor = 1.5 - 0.5 * clampedCred;
        double score = raw * credFactor;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rule", "MULTI_FACTOR");
        detail.put("rain2h", in.rain2h());
        detail.put("trendCmPerHour", in.trendCmPerHour());
        detail.put("pumpLagMin", in.pumpLagMin());
        detail.put("reportDepthCm", in.reportDepthCm());
        detail.put("credibility", in.credibility());
        detail.put("credFactor", credFactor);
        detail.put("rawScore", raw);
        detail.put("pumpDataPresent", in.pumpDataPresent());
        detail.put("trendDataPresent", in.trendDataPresent());
        return new RiskResult(level(score, medium, high), score, detail);
    }

    private RiskLevel level(double score, double medium, double high) {
        if (score >= high) return RiskLevel.HIGH;
        if (score >= medium) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }
}
