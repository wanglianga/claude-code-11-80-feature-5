package com.flood.statemigrate.service;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检查点清单与检查点比对：对照每个分区的消费位点、窗口水印、
 * 状态快照与输出批次，给出迁移就绪度分析。
 */
@Service
public class CheckpointService {

    private final CheckpointManifestRepo checkpointRepo;
    private final StreamEventRepo eventRepo;
    private final MigrationPlanRepo planRepo;
    private final DualTrackRunRepo runRepo;
    private final StreamService streamService;

    public CheckpointService(CheckpointManifestRepo checkpointRepo, StreamEventRepo eventRepo,
                             MigrationPlanRepo planRepo, DualTrackRunRepo runRepo, StreamService streamService) {
        this.checkpointRepo = checkpointRepo;
        this.eventRepo = eventRepo;
        this.planRepo = planRepo;
        this.runRepo = runRepo;
        this.streamService = streamService;
    }

    @Transactional
    public CheckpointManifest record(Long streamId, CheckpointManifest cp) {
        StreamDefinition stream = streamService.get(streamId);
        streamService.partition(stream, cp.getPartitionNo());
        cp.setStreamId(streamId);
        cp.setReadOnlySnapshot(false);
        return checkpointRepo.save(cp);
    }

    public List<CheckpointManifest> list(Long streamId) {
        return checkpointRepo.findByStreamIdOrderByPartitionNoAscCreatedAtDesc(streamId);
    }

    public CheckpointManifest latest(Long streamId, int partitionNo) {
        return checkpointRepo.findFirstByStreamIdAndPartitionNoOrderByConsumeOffsetDescIdDesc(streamId, partitionNo)
                .orElse(null);
    }

    /** 检查点比对：位点滞后、水印滞后、快照与迁移就绪度 */
    public Map<String, Object> compare(Long streamId) {
        StreamDefinition stream = streamService.get(streamId);
        List<Map<String, Object>> partitions = new ArrayList<>();
        Instant now = Instant.now();
        for (PartitionConfig pc : streamService.partitions(stream)) {
            Map<String, Object> row = new LinkedHashMap<>();
            CheckpointManifest cp = latest(streamId, pc.partitionNo());
            long maxOffset = eventRepo.maxOffset(streamId, pc.partitionNo()).orElse(0L);
            row.put("partitionNo", pc.partitionNo());
            row.put("roadId", pc.roadId());
            row.put("key", pc.key());
            row.put("latestEventOffset", maxOffset);
            if (cp != null) {
                row.put("consumeOffset", cp.getConsumeOffset());
                row.put("offsetLag", maxOffset - cp.getConsumeOffset());
                row.put("windowWatermark", cp.getWindowWatermark());
                row.put("watermarkLagSeconds", cp.getWindowWatermark() == null ? null
                        : Math.max(0, now.getEpochSecond() - cp.getWindowWatermark().getEpochSecond()));
                row.put("status", cp.getStatus());
                row.put("snapshotPresent", cp.getStateSnapshotJson() != null);
                row.put("stateSchema", cp.getStateSchema());
                row.put("rulePackageVersion", cp.getRulePackageVersion());
                row.put("outputBatchId", cp.getOutputBatchId());
                row.put("readOnlySnapshot", cp.isReadOnlySnapshot());
            } else {
                row.put("consumeOffset", null);
                row.put("offsetLag", maxOffset);
                row.put("status", "MISSING");
                row.put("snapshotPresent", false);
            }
            planRepo.findFirstByStreamIdAndPartitionNoOrderByIdDesc(streamId, pc.partitionNo())
                    .ifPresentOrElse(p -> {
                        row.put("planRoute", p.getRoute());
                        row.put("planStatus", p.getStatus());
                    }, () -> {
                        row.put("planRoute", null);
                        row.put("planStatus", null);
                    });
            runRepo.findFirstByStreamIdAndPartitionNoOrderByIdDesc(streamId, pc.partitionNo())
                    .ifPresentOrElse(r -> row.put("dualTrackStatus", r.getStatus()),
                            () -> row.put("dualTrackStatus", null));
            partitions.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("streamId", streamId);
        result.put("streamCode", stream.getStreamCode());
        result.put("streamStatus", stream.getStatus());
        result.put("currentRuleVersion", stream.getRulePackageVersion());
        result.put("targetRuleVersion", stream.getTargetRulePackageVersion());
        result.put("partitions", partitions);
        return result;
    }
}
