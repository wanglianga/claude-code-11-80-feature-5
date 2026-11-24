package com.flood.statemigrate.domain;

/**
 * 标签可观察性分类：决定一条结果能否作为规则评估的证据，以及能否计为误报。
 * VERIFIED_DEPTH          已核验水深（独立核验，可进入严格评估集）
 * INTERVENTION_AFFECTED   受处置影响的观测（预警后抽排/封路导致水位未再上升，不得计作假阳性）
 * DELAYED_VIDEO           视频识别标签（常在积水峰值后补传，按事件时间纳入并保留右删失版本）
 * DELAYED_MANUAL          人工上报标签（通常迟到数日）
 * UNOBSERVED              完全未观察结果（道路封闭等原因导致无标签）
 */
public enum ObservabilityClass {
    VERIFIED_DEPTH,
    INTERVENTION_AFFECTED,
    DELAYED_VIDEO,
    DELAYED_MANUAL,
    UNOBSERVED;

    /** 是否属于可进入评估的观测（严格集只接受已核验水深） */
    public boolean isObservation() {
        return this == VERIFIED_DEPTH || this == INTERVENTION_AFFECTED
                || this == DELAYED_VIDEO || this == DELAYED_MANUAL;
    }
}
