package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 回退记录：保留旧规则只读快照引用与可撤回输出范围。
 */
@Entity
@Table(name = "rollback_record")
public class RollbackRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    /** 撤回范围（按分区位点区间） */
    @Column(nullable = false)
    private long retractFromOffset;

    @Column(nullable = false)
    private long retractToOffset;

    /** 涉及分区 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String partitionsJson;

    /** 旧规则只读快照（检查点 id 列表） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String readOnlySnapshotIdsJson;

    @Column(nullable = false, length = 256)
    private String reason;

    /** 被撤回的新输出条数 */
    @Column(nullable = false)
    private int retractedCount;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }
    public long getRetractFromOffset() { return retractFromOffset; }
    public void setRetractFromOffset(long retractFromOffset) { this.retractFromOffset = retractFromOffset; }
    public long getRetractToOffset() { return retractToOffset; }
    public void setRetractToOffset(long retractToOffset) { this.retractToOffset = retractToOffset; }
    public String getPartitionsJson() { return partitionsJson; }
    public void setPartitionsJson(String partitionsJson) { this.partitionsJson = partitionsJson; }
    public String getReadOnlySnapshotIdsJson() { return readOnlySnapshotIdsJson; }
    public void setReadOnlySnapshotIdsJson(String readOnlySnapshotIdsJson) { this.readOnlySnapshotIdsJson = readOnlySnapshotIdsJson; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public int getRetractedCount() { return retractedCount; }
    public void setRetractedCount(int retractedCount) { this.retractedCount = retractedCount; }
    public Instant getCreatedAt() { return createdAt; }
}
