package com.flood.statemigrate.domain;

/**
 * 评估路线：
 * STRICT_VERIFIED          仅用独立核验水深构建严格评估集（偏差小、解释清晰，但样本少、覆盖慢）
 * CENSORED_INTERVENTION    纳入受处置影响或延迟到达的标签，带删失与干预标记（覆盖更广，
 *                          但必须报告不可识别部分与敏感性范围）
 */
public enum EvaluationRoute {
    STRICT_VERIFIED,
    CENSORED_INTERVENTION
}
