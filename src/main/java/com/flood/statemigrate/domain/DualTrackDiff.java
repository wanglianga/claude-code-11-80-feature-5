package com.flood.statemigrate.domain;

import jakarta.persistence.*;

/**
 * 双轨差异：同一输入截点下，某道路旧/新规则风险结论的量化差异。
 */
@Entity
@Table(name = "dual_track_diff")
public class DualTrackDiff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long runId;

    @Column(nullable = false)
    private Long streamId;

    @Column(nullable = false)
    private int partitionNo;

    @Column(nullable = false, length = 64)
    private String roadId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RiskLevel oldRisk;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RiskLevel newRisk;

    @Column(nullable = false)
    private double oldScore;

    @Column(nullable = false)
    private double newScore;

    /** newScore - oldScore，量化风险差异 */
    @Column(nullable = false)
    private double delta;

    /** 旧→新是否升级（如 LOW→HIGH） */
    @Column(nullable = false)
    private boolean escalation;

    @Column(columnDefinition = "TEXT")
    private String detailJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }
    public int getPartitionNo() { return partitionNo; }
    public void setPartitionNo(int partitionNo) { this.partitionNo = partitionNo; }
    public String getRoadId() { return roadId; }
    public void setRoadId(String roadId) { this.roadId = roadId; }
    public RiskLevel getOldRisk() { return oldRisk; }
    public void setOldRisk(RiskLevel oldRisk) { this.oldRisk = oldRisk; }
    public RiskLevel getNewRisk() { return newRisk; }
    public void setNewRisk(RiskLevel newRisk) { this.newRisk = newRisk; }
    public double getOldScore() { return oldScore; }
    public void setOldScore(double oldScore) { this.oldScore = oldScore; }
    public double getNewScore() { return newScore; }
    public void setNewScore(double newScore) { this.newScore = newScore; }
    public double getDelta() { return delta; }
    public void setDelta(double delta) { this.delta = delta; }
    public boolean isEscalation() { return escalation; }
    public void setEscalation(boolean escalation) { this.escalation = escalation; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
}
