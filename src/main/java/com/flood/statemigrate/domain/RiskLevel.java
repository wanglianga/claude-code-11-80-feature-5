package com.flood.statemigrate.domain;

public enum RiskLevel {
    LOW, MEDIUM, HIGH;

    /** 旧→新是否为升级（如 LOW→HIGH） */
    public boolean escalatesTo(RiskLevel other) {
        return other.ordinal() > this.ordinal();
    }
}
