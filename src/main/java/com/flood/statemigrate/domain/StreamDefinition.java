package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 流定义登记表：输入主题、事件时间字段、水位基准、状态模式、当前规则包版本。
 * partitionConfigJson 保存分区→道路/泵站/河段映射与关键分区标记，例如：
 * [{"partitionNo":0,"roadId":"R-001","pumpId":"P-0","reachId":"RCH-0","key":true}]
 */
@Entity
@Table(name = "stream_definition")
public class StreamDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String streamCode;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 128)
    private String inputTopic;

    @Column(nullable = false, length = 64)
    private String eventTimeField;

    /** 水位基准（米），高水位判定 = 基准 + 0.5m */
    @Column(nullable = false)
    private double waterLevelBaseline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StateSchema stateSchema;

    @Column(nullable = false, length = 32)
    private String rulePackageVersion;

    /** 升级目标规则包版本（迁移期间使用） */
    @Column(length = 32)
    private String targetRulePackageVersion;

    @Column(nullable = false)
    private int totalPartitions;

    @Column(nullable = false)
    private int eventRetentionHours;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String partitionConfigJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StreamStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStreamCode() { return streamCode; }
    public void setStreamCode(String streamCode) { this.streamCode = streamCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getInputTopic() { return inputTopic; }
    public void setInputTopic(String inputTopic) { this.inputTopic = inputTopic; }
    public String getEventTimeField() { return eventTimeField; }
    public void setEventTimeField(String eventTimeField) { this.eventTimeField = eventTimeField; }
    public double getWaterLevelBaseline() { return waterLevelBaseline; }
    public void setWaterLevelBaseline(double waterLevelBaseline) { this.waterLevelBaseline = waterLevelBaseline; }
    public StateSchema getStateSchema() { return stateSchema; }
    public void setStateSchema(StateSchema stateSchema) { this.stateSchema = stateSchema; }
    public String getRulePackageVersion() { return rulePackageVersion; }
    public void setRulePackageVersion(String rulePackageVersion) { this.rulePackageVersion = rulePackageVersion; }
    public String getTargetRulePackageVersion() { return targetRulePackageVersion; }
    public void setTargetRulePackageVersion(String v) { this.targetRulePackageVersion = v; }
    public int getTotalPartitions() { return totalPartitions; }
    public void setTotalPartitions(int totalPartitions) { this.totalPartitions = totalPartitions; }
    public int getEventRetentionHours() { return eventRetentionHours; }
    public void setEventRetentionHours(int eventRetentionHours) { this.eventRetentionHours = eventRetentionHours; }
    public String getPartitionConfigJson() { return partitionConfigJson; }
    public void setPartitionConfigJson(String partitionConfigJson) { this.partitionConfigJson = partitionConfigJson; }
    public StreamStatus getStatus() { return status; }
    public void setStatus(StreamStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
