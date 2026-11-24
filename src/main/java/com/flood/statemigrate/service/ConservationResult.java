package com.flood.statemigrate.service;

/** 守恒条件核验结果 */
public record ConservationResult(String name, boolean satisfied, String detail) {
}
