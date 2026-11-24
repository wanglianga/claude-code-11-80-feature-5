package com.flood.statemigrate.domain;

public enum CheckpointStatus {
    COMPLETE,   // 旧作业已完成该分区检查点
    PENDING     // 旧作业尚未完成该分区检查点
}
