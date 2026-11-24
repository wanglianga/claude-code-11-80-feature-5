package com.flood.statemigrate.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 上线门禁：只有通过最低覆盖与安全下限的规则才可扩大到更多道路。
 * 门禁绑定最近一次评估运行，记录覆盖、选择偏差、最小样本、安全下限判定，
 * 以及本次允许扩大到的道路范围（allowedRoadIds）。
 */
@Entity
@Table(name = "release_gate")
public class ReleaseGate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GateDecision decision;

    @Column(nullable = false)
    private Long evaluationRunId;

    @Column(nullable = false)
    private double minCoverage;

    @Column(nullable = false)
    private int minStrictSamples;

    @Column(nullable = false)
    private double safetyFloor;

    @Column(nullable = false)
    private double measuredCoverage;

    @Column(nullable = false)
    private int strictSamples;

    @Column(nullable = false)
    private double measuredSafety;

    /** 覆盖选择偏差是否存在（如仅低风险道路得到人工核验） */
    @Column(nullable = false)
    private boolean coverageBias;

    @Column(columnDefinition = "TEXT")
    private String allowedRoadIdsJson;

    @Column(columnDefinition = "TEXT")
    private String blockedRoadIdsJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reasonsJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }
    public GateDecision getDecision() { return decision; }
    public void setDecision(GateDecision decision) { this.decision = decision; }
    public Long getEvaluationRunId() { return evaluationRunId; }
    public void setEvaluationRunId(Long evaluationRunId) { this.evaluationRunId = evaluationRunId; }
    public double getMinCoverage() { return minCoverage; }
    public void setMinCoverage(double minCoverage) { this.minCoverage = minCoverage; }
    public int getMinStrictSamples() { return minStrictSamples; }
    public void setMinStrictSamples(int minStrictSamples) { this.minStrictSamples = minStrictSamples; }
    public double getSafetyFloor() { return safetyFloor; }
    public void setSafetyFloor(double safetyFloor) { this.safetyFloor = safetyFloor; }
    public double getMeasuredCoverage() { return measuredCoverage; }
    public void setMeasuredCoverage(double measuredCoverage) { this.measuredCoverage = measuredCoverage; }
    public int getStrictSamples() { return strictSamples; }
    public void setStrictSamples(int strictSamples) { this.strictSamples = strictSamples; }
    public double getMeasuredSafety() { return measuredSafety; }
    public void setMeasuredSafety(double measuredSafety) { this.measuredSafety = measuredSafety; }
    public boolean isCoverageBias() { return coverageBias; }
    public void setCoverageBias(boolean coverageBias) { this.coverageBias = coverageBias; }
    public String getAllowedRoadIdsJson() { return allowedRoadIdsJson; }
    public void setAllowedRoadIdsJson(String allowedRoadIdsJson) { this.allowedRoadIdsJson = allowedRoadIdsJson; }
    public String getBlockedRoadIdsJson() { return blockedRoadIdsJson; }
    public void setBlockedRoadIdsJson(String blockedRoadIdsJson) { this.blockedRoadIdsJson = blockedRoadIdsJson; }
    public String getReasonsJson() { return reasonsJson; }
    public void setReasonsJson(String reasonsJson) { this.reasonsJson = reasonsJson; }
    public Instant getCreatedAt() { return createdAt; }
}
