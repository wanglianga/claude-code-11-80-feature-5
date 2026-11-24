package com.flood.statemigrate.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 评估运行：记录一次校准评估所采用的路线、时间基准、结果与不可识别/敏感性范围。
 * asOf 为本次评估的「当时」时刻——右删失按 asOf 判定，事后补传的标签
 * 会在下一次评估中以事件时间纳入，同时保留当时 asOf 下的右删失版本（replayOfRunId）。
 */
@Entity
@Table(name = "evaluation_run")
public class EvaluationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private EvaluationRoute route;

    /** 评估「当时」时刻（右删失按此判定） */
    @Column(nullable = false)
    private Instant asOf;

    /** 同一条评估的重跑溯源：本次为对 replayOfRunId 在更新 asOf 下的重评 */
    private Long replayOfRunId;

    @Column(nullable = false)
    private int ledgerSize;

    @Column(nullable = false)
    private int strictSampleSize;

    @Column(nullable = false)
    private int censoredSampleSize;

    /** 不可识别（UNOBSERVED + 右删失未到达）占比 */
    @Column(nullable = false)
    private double unidentifiableShare;

    /** 完整评估结果（分层命中/提前量/校准区间/右删失范围/敏感性范围） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String resultJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }
    public EvaluationRoute getRoute() { return route; }
    public void setRoute(EvaluationRoute route) { this.route = route; }
    public Instant getAsOf() { return asOf; }
    public void setAsOf(Instant asOf) { this.asOf = asOf; }
    public Long getReplayOfRunId() { return replayOfRunId; }
    public void setReplayOfRunId(Long replayOfRunId) { this.replayOfRunId = replayOfRunId; }
    public int getLedgerSize() { return ledgerSize; }
    public void setLedgerSize(int ledgerSize) { this.ledgerSize = ledgerSize; }
    public int getStrictSampleSize() { return strictSampleSize; }
    public void setStrictSampleSize(int strictSampleSize) { this.strictSampleSize = strictSampleSize; }
    public int getCensoredSampleSize() { return censoredSampleSize; }
    public void setCensoredSampleSize(int censoredSampleSize) { this.censoredSampleSize = censoredSampleSize; }
    public double getUnidentifiableShare() { return unidentifiableShare; }
    public void setUnidentifiableShare(double unidentifiableShare) { this.unidentifiableShare = unidentifiableShare; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public Instant getCreatedAt() { return createdAt; }
}
