package com.flood.statemigrate.web.dto;

/** 证明器运行请求；targetRulePackageVersion 为空时使用流的目标版本 */
public record ProofReq(String targetRulePackageVersion) {
}
