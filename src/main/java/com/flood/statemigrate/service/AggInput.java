package com.flood.statemigrate.service;

import java.util.List;

/**
 * 窗口聚合输入：近两小时降雨累计、上游水位趋势、泵站启停滞后、传感器可信度、上报积水深度。
 * pumpDataPresent=false 时 pumpLagMin 不允许由缺失启停区间零值填充得到（仅在无高水位时合法为 0）。
 */
public record AggInput(
        double rain2h,
        double trendCmPerHour,
        double pumpLagMin,
        double credibility,
        double reportDepthCm,
        long fromOffset,
        long toOffset,
        List<Long> eventIds,
        boolean pumpDataPresent,
        boolean trendDataPresent
) {
}
