package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 检查点清单：每个分区的消费位点、窗口水印、状态快照、输出批次。
 * stateSnapshotJson 为旧网格窗口状态（或迁移后的三层状态）快照原文。
 */
@Entity
@Table(name = "checkpoint_manifest")
public class CheckpointManifest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    @Column(nullable = false)
    private int partitionNo;

    @Column(nullable = false)
    private long consumeOffset;

    @Column(nullable = false)
    private Instant windowWatermark;

    @Column(columnDefinition = "TEXT")
    private String stateSnapshotJson;

    @Column(length = 64)
    private String outputBatchId;

    @Column(nullable = false, length = 32)
    private String rulePackageVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StateSchema stateSchema;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CheckpointStatus status;

    /** 回退后旧规则快照置为只读 */
    @Column(nullable = false)
    private boolean readOnlySnapshot;

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
    public long getConsumeOffset() { return consumeOffset; }
    public void setConsumeOffset(long consumeOffset) { this.consumeOffset = consumeOffset; }
    public Instant getWindowWatermark() { return windowWatermark; }
    public void setWindowWatermark(Instant windowWatermark) { this.windowWatermark = windowWatermark; }
    public String getStateSnapshotJson() { return stateSnapshotJson; }
    public void setStateSnapshotJson(String stateSnapshotJson) { this.stateSnapshotJson = stateSnapshotJson; }
    public String getOutputBatchId() { return outputBatchId; }
    public void setOutputBatchId(String outputBatchId) { this.outputBatchId = outputBatchId; }
    public String getRulePackageVersion() { return rulePackageVersion; }
    public void setRulePackageVersion(String rulePackageVersion) { this.rulePackageVersion = rulePackageVersion; }
    public StateSchema getStateSchema() { return stateSchema; }
    public void setStateSchema(StateSchema stateSchema) { this.stateSchema = stateSchema; }
    public CheckpointStatus getStatus() { return status; }
    public void setStatus(CheckpointStatus status) { this.status = status; }
    public boolean isReadOnlySnapshot() { return readOnlySnapshot; }
    public void setReadOnlySnapshot(boolean readOnlySnapshot) { this.readOnlySnapshot = readOnlySnapshot; }
    public Instant getCreatedAt() { return createdAt; }
}
