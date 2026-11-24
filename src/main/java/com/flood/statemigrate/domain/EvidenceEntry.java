package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 事件证据账：每次风险结论使用的输入、规则与状态版本。
 * inputRefsJson: {"eventIds":[1,2],"fromOffset":100,"toOffset":150}
 */
@Entity
@Table(name = "evidence_entry")
public class EvidenceEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    @Column(nullable = false)
    private int partitionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EvidenceKind kind;

    @Column(length = 64)
    private String roadId;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private RiskLevel riskLevel;

    private Double riskScore;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String inputRefsJson;

    @Column(nullable = false, length = 32)
    private String ruleVersion;

    /** 状态版本：如 snap:chk-12 / adapt:plan-3 / replay:plan-5 / dual:run-7 */
    @Column(nullable = false, length = 64)
    private String stateVersion;

    private Long watermarkOffset;

    @Column(length = 256)
    private String note;

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
    public EvidenceKind getKind() { return kind; }
    public void setKind(EvidenceKind kind) { this.kind = kind; }
    public String getRoadId() { return roadId; }
    public void setRoadId(String roadId) { this.roadId = roadId; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }
    public Double getRiskScore() { return riskScore; }
    public void setRiskScore(Double riskScore) { this.riskScore = riskScore; }
    public String getInputRefsJson() { return inputRefsJson; }
    public void setInputRefsJson(String inputRefsJson) { this.inputRefsJson = inputRefsJson; }
    public String getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(String ruleVersion) { this.ruleVersion = ruleVersion; }
    public String getStateVersion() { return stateVersion; }
    public void setStateVersion(String stateVersion) { this.stateVersion = stateVersion; }
    public Long getWatermarkOffset() { return watermarkOffset; }
    public void setWatermarkOffset(Long watermarkOffset) { this.watermarkOffset = watermarkOffset; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getCreatedAt() { return createdAt; }
}
