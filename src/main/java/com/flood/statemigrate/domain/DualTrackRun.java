package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 双轨校验运行：旧/新规则在同一输入截点（inputCutOffset）并行计算。
 */
@Entity
@Table(name = "dual_track_run")
public class DualTrackRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    @Column(nullable = false)
    private int partitionNo;

    /** 输入截点：旧新规则都只消费到该位点 */
    @Column(nullable = false)
    private long inputCutOffset;

    @Column(nullable = false, length = 32)
    private String oldRuleVersion;

    @Column(nullable = false, length = 32)
    private String newRuleVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DualTrackStatus status;

    /** 汇总：{"roads":1,"escalations":1,"maxDelta":48.5,"meanDelta":12.3} */
    @Column(columnDefinition = "TEXT")
    private String summaryJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }
    public int getPartitionNo() { return partitionNo; }
    public void setPartitionNo(int partitionNo) { this.partitionNo = partitionNo; }
    public long getInputCutOffset() { return inputCutOffset; }
    public void setInputCutOffset(long inputCutOffset) { this.inputCutOffset = inputCutOffset; }
    public String getOldRuleVersion() { return oldRuleVersion; }
    public void setOldRuleVersion(String oldRuleVersion) { this.oldRuleVersion = oldRuleVersion; }
    public String getNewRuleVersion() { return newRuleVersion; }
    public void setNewRuleVersion(String newRuleVersion) { this.newRuleVersion = newRuleVersion; }
    public DualTrackStatus getStatus() { return status; }
    public void setStatus(DualTrackStatus status) { this.status = status; }
    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }
    public Instant getCreatedAt() { return createdAt; }
}
