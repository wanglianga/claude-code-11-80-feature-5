package com.flood.statemigrate.domain;

/** 证明结论：模式级是否可适配 */
public enum ProofVerdict {
    CONDITIONALLY_ADAPTABLE, // 字段可逆且守恒条件可在分区级逐一核验
    REPLAY_REQUIRED,         // 模式级缺失关键中间量，必须回放
    MANUAL_REQUIRED          // 依赖关系无法自动判定，需人工裁定
}
