package com.flood.statemigrate.domain;

/**
 * 预警结果账条目的输出阶段：
 * OLD_PUBLISHED     旧规则已发布输出
 * NEW_PUBLISHED     新规则已发布输出（闸门发布后）
 * SHADOW_SCREENING  影子运行候选输出——只能用于筛查，不能声称已带来真实减灾收益
 */
public enum OutcomeStage {
    OLD_PUBLISHED,
    NEW_PUBLISHED,
    SHADOW_SCREENING;

    public boolean isPublished() {
        return this == OLD_PUBLISHED || this == NEW_PUBLISHED;
    }
}
