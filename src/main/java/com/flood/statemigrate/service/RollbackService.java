package com.flood.statemigrate.service;

import tools.jackson.databind.ObjectMapper;
import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 回退控制器：保留旧规则的只读快照，撤回指定范围内的新输出，
 * 闸门回到 HOLD，流恢复旧规则服务。
 */
@Service
public class RollbackService {

    private final RollbackRecordRepo rollbackRepo;
    private final EvidenceEntryRepo evidenceRepo;
    private final CheckpointManifestRepo checkpointRepo;
    private final SwitchGateRepo gateRepo;
    private final StreamService streamService;
    private final RulePackageRepo rulePackageRepo;
    private final ObjectMapper om;

    public RollbackService(RollbackRecordRepo rollbackRepo, EvidenceEntryRepo evidenceRepo,
                           CheckpointManifestRepo checkpointRepo, SwitchGateRepo gateRepo,
                           StreamService streamService, RulePackageRepo rulePackageRepo, ObjectMapper om) {
        this.rollbackRepo = rollbackRepo;
        this.evidenceRepo = evidenceRepo;
        this.checkpointRepo = checkpointRepo;
        this.gateRepo = gateRepo;
        this.streamService = streamService;
        this.rulePackageRepo = rulePackageRepo;
        this.om = om;
    }

    @Transactional
    public RollbackRecord rollback(Long streamId, long fromOffset, long toOffset,
                                   List<Integer> partitions, String reason) {
        StreamDefinition stream = streamService.get(streamId);
        if (fromOffset > toOffset) {
            throw ApiException.badRequest("撤回范围非法: fromOffset > toOffset");
        }
        List<Integer> targetPartitions = partitions != null && !partitions.isEmpty()
                ? partitions
                : streamService.partitions(stream).stream().map(PartitionConfig::partitionNo).toList();

        // 1) 撤回范围内的新输出 → RETRACTED
        int retracted = 0;
        for (int p : targetPartitions) {
            for (EvidenceEntry e : evidenceRepo.findByStreamIdAndPartitionNoAndKind(
                    streamId, p, EvidenceKind.NEW_OUTPUT)) {
                if (e.getWatermarkOffset() != null
                        && e.getWatermarkOffset() >= fromOffset && e.getWatermarkOffset() <= toOffset) {
                    e.setKind(EvidenceKind.RETRACTED);
                    e.setNote((e.getNote() == null ? "" : e.getNote() + "；") + "回退撤回: " + reason);
                    evidenceRepo.save(e);
                    retracted++;
                }
            }
        }

        // 2) 旧规则快照置为只读
        List<Long> readOnlyIds = new ArrayList<>();
        for (int p : targetPartitions) {
            checkpointRepo.findByStreamIdAndPartitionNoOrderByConsumeOffsetDesc(streamId, p).stream()
                    .filter(c -> c.getStateSchema() == StateSchema.GRID_WINDOW_V1)
                    .findFirst()
                    .ifPresent(cp -> {
                        cp.setReadOnlySnapshot(true);
                        checkpointRepo.save(cp);
                        readOnlyIds.add(cp.getId());
                    });
        }

        // 3) 闸门回到 HOLD
        gateRepo.findByStreamId(streamId).ifPresent(g -> {
            g.setStatus(GateStatus.HOLD);
            gateRepo.save(g);
        });

        // 4) 流恢复旧规则服务
        rulePackageRepo.findByStreamIdOrderByCreatedAtAsc(streamId).stream()
                .filter(rp -> rp.getKind() == RuleKind.FIXED_THRESHOLD && "ACTIVE".equals(rp.getStatus()))
                .reduce((first, second) -> second)
                .ifPresent(old -> stream.setRulePackageVersion(old.getVersion()));
        stream.setStateSchema(StateSchema.GRID_WINDOW_V1);
        stream.setStatus(StreamStatus.ROLLED_BACK);
        streamService.save(stream);

        RollbackRecord record = new RollbackRecord();
        record.setStreamId(streamId);
        record.setRetractFromOffset(fromOffset);
        record.setRetractToOffset(toOffset);
        record.setPartitionsJson(writeJson(targetPartitions));
        record.setReadOnlySnapshotIdsJson(writeJson(readOnlyIds));
        record.setReason(reason);
        record.setRetractedCount(retracted);
        return rollbackRepo.save(record);
    }

    public List<RollbackRecord> list(Long streamId) {
        return rollbackRepo.findByStreamIdOrderByIdDesc(streamId);
    }

    private String writeJson(Object value) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
