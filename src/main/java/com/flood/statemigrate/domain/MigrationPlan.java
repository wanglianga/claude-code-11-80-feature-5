package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 迁移计划：为单个分区选择 ADAPT / REPLAY / MANUAL 路线。
 * REPLAY 路线记录限定的回放时间窗与位点区间。
 */
@Entity
@Table(name = "migration_plan")
public class MigrationPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    @Column(nullable = false)
    private int partitionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PlanRoute route;

    @Column(nullable = false, length = 512)
    private String reason;

    @Column
    private Long proofId;

    /** 回放窗口（限定时间窗）与位点区间 */
    private Instant replayWindowStart;
    private Instant replayWindowEnd;
    private Long replayFromOffset;
    private Long replayToOffset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PlanStatus status;

    @Column(columnDefinition = "TEXT")
    private String resultJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }
    public int getPartitionNo() { return partitionNo; }
    public void setPartitionNo(int partitionNo) { this.partitionNo = partitionNo; }
    public PlanRoute getRoute() { return route; }
    public void setRoute(PlanRoute route) { this.route = route; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getProofId() { return proofId; }
    public void setProofId(Long proofId) { this.proofId = proofId; }
    public Instant getReplayWindowStart() { return replayWindowStart; }
    public void setReplayWindowStart(Instant t) { this.replayWindowStart = t; }
    public Instant getReplayWindowEnd() { return replayWindowEnd; }
    public void setReplayWindowEnd(Instant t) { this.replayWindowEnd = t; }
    public Long getReplayFromOffset() { return replayFromOffset; }
    public void setReplayFromOffset(Long v) { this.replayFromOffset = v; }
    public Long getReplayToOffset() { return replayToOffset; }
    public void setReplayToOffset(Long v) { this.replayToOffset = v; }
    public PlanStatus getStatus() { return status; }
    public void setStatus(PlanStatus status) { this.status = status; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
