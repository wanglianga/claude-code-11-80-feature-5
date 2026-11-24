package com.flood.statemigrate.domain;

public enum ProofStatus {
    VALID,    // 绑定的规则包版本仍然有效
    EXPIRED   // 规则包二次修订后过期，需重新绑定
}
