package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 切换闸门：仅当关键分区满足证据与覆盖阈值时发布新输出。
 * switchOffsetJson 记录发布时各分区的切换水印（位点）。
 */
@Entity
@Table(name = "switch_gate")
public class SwitchGate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long streamId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GateStatus status;

    /** 覆盖率阈值：新规则就绪分区数 / 总分区数 */
    @Column(nullable = false)
    private double coverageThreshold;

    /** 发布时的切换时间水印 */
    private Instant switchWatermark;

    /** 各分区切换位点 {"0":150,"1":160} */
    @Column(columnDefinition = "TEXT")
    private String switchOffsetJson;

    /** 待验证分区（新规则升级风险但旧作业未完成检查点） */
    @Column(columnDefinition = "TEXT")
    private String pendingVerificationJson;

    /** 最近一次评估明细 */
    @Column(columnDefinition = "TEXT")
    private String lastEvaluationJson;

    private Instant releasedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }
    public GateStatus getStatus() { return status; }
    public void setStatus(GateStatus status) { this.status = status; }
    public double getCoverageThreshold() { return coverageThreshold; }
    public void setCoverageThreshold(double coverageThreshold) { this.coverageThreshold = coverageThreshold; }
    public Instant getSwitchWatermark() { return switchWatermark; }
    public void setSwitchWatermark(Instant switchWatermark) { this.switchWatermark = switchWatermark; }
    public String getSwitchOffsetJson() { return switchOffsetJson; }
    public void setSwitchOffsetJson(String switchOffsetJson) { this.switchOffsetJson = switchOffsetJson; }
    public String getPendingVerificationJson() { return pendingVerificationJson; }
    public void setPendingVerificationJson(String pendingVerificationJson) { this.pendingVerificationJson = pendingVerificationJson; }
    public String getLastEvaluationJson() { return lastEvaluationJson; }
    public void setLastEvaluationJson(String lastEvaluationJson) { this.lastEvaluationJson = lastEvaluationJson; }
    public Instant getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Instant releasedAt) { this.releasedAt = releasedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
