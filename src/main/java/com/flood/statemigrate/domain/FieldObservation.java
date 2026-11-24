package com.flood.statemigrate.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 现场观测（标签原始记录）：独立核验水深、视频识别、人工上报。
 * eventTime 为事件时间（积水发生/测量/峰值），observedAt 为到达评估系统的时间；
 * 两者之差用于判定延迟标签与右删失（迟到数日的标签在到达前属不可识别）。
 */
@Entity
@Table(name = "field_observation")
public class FieldObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    @Column(nullable = false)
    private int partitionNo;

    @Column(nullable = false, length = 64)
    private String roadId;

    @Column(nullable = false, length = 64)
    private String basin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private LabelSource source;

    @Column(nullable = false)
    private double depthCm;

    /** 事件时间：积水实际发生/测量时刻（视频峰值补传时保留峰值时刻） */
    @Column(nullable = false)
    private Instant eventTime;

    /** 到达评估系统的时间 */
    @Column(nullable = false)
    private Instant observedAt;

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
    public String getRoadId() { return roadId; }
    public void setRoadId(String roadId) { this.roadId = roadId; }
    public String getBasin() { return basin; }
    public void setBasin(String basin) { this.basin = basin; }
    public LabelSource getSource() { return source; }
    public void setSource(LabelSource source) { this.source = source; }
    public double getDepthCm() { return depthCm; }
    public void setDepthCm(double depthCm) { this.depthCm = depthCm; }
    public Instant getEventTime() { return eventTime; }
    public void setEventTime(Instant eventTime) { this.eventTime = eventTime; }
    public Instant getObservedAt() { return observedAt; }
    public void setObservedAt(Instant observedAt) { this.observedAt = observedAt; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getCreatedAt() { return createdAt; }
}
