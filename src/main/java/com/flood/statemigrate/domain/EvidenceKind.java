package com.flood.statemigrate.domain;

/**
 * 证据账输出类别：
 * OLD_OUTPUT    旧规则已发布输出
 * NEW_CANDIDATE 新规则候选输出（双轨/回放产生，未发布）
 * NEW_OUTPUT    闸门发布后生效的新输出
 * RETRACTED     已撤回范围（回退或回放越过切换水印后作废）
 */
public enum EvidenceKind {
    OLD_OUTPUT,
    NEW_CANDIDATE,
    NEW_OUTPUT,
    RETRACTED
}
