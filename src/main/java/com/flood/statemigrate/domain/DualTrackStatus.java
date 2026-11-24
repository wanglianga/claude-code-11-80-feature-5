package com.flood.statemigrate.domain;

public enum DualTrackStatus {
    RUNNING,
    DONE,
    EXPIRED   // 规则包二次修订后，该比较结果过期
}
