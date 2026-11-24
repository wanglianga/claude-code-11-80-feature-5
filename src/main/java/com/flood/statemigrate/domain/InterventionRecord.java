package com.flood.statemigrate.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 处置动作记录：泵站抽排 / 道路封闭。
 * 抽排会改变后续水位，封路会使视频标签缺失；时间对齐器据此判断
 * 「处置之后的水位变化不得反证原先风险」。
 */
@Entity
@Table(name = "intervention_record")
public class InterventionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    @Column(nullable = false)
    private int partitionNo;

    @Column(nullable = false, length = 64)
    private String roadId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private InterventionType type;

    @Column(nullable = false)
    private Instant at;

    @Column(length = 64)
    private String ref;

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
    public InterventionType getType() { return type; }
    public void setType(InterventionType type) { this.type = type; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
    public String getRef() { return ref; }
    public void setRef(String ref) { this.ref = ref; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getCreatedAt() { return createdAt; }
}
