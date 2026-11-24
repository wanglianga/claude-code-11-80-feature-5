package com.flood.statemigrate.domain;

/** 标签到达渠道 */
public enum LabelSource {
    FIELD_VERIFICATION, // 现场独立核验（水深）
    VIDEO,              // 视频识别
    MANUAL_REPORT       // 人工上报
}
