package com.flood.statemigrate.service;

import tools.jackson.databind.ObjectMapper;
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
 * 切换闸门：仅当关键分区满足证据要求且覆盖率达标时发布新输出。
 * 若新规则把某道路升级（如低→高）而旧作业尚未完成该分区检查点，
 * 闸门维持旧输出并把该分区标记为待验证。
 */
@Service
public class GateService {

    private final SwitchGateRepo gateRepo;
    private final CheckpointManifestRepo checkpointRepo;
    private final MigrationPlanRepo planRepo;
    private final DualTrackRunRepo runRepo;
    private final DualTrackDiffRepo diffRepo;
    private final EvidenceEntryRepo evidenceRepo;
    private final StreamService streamService;
    private final ProverService proverService;
    private final ObjectMapper om;

    public GateService(SwitchGateRepo gateRepo, CheckpointManifestRepo checkpointRepo,
                       MigrationPlanRepo planRepo, DualTrackRunRepo runRepo, DualTrackDiffRepo diffRepo,
                       EvidenceEntryRepo evidenceRepo, StreamService streamService,
                       ProverService proverService, ObjectMapper om) {
        this.gateRepo = gateRepo;
        this.checkpointRepo = checkpointRepo;
        this.planRepo = planRepo;
        this.runRepo = runRepo;
        this.diffRepo = diffRepo;
        this.evidenceRepo = evidenceRepo;
        this.streamService = streamService;
        this.proverService = proverService;
        this.om = om;
    }

    public SwitchGate getOrCreate(StreamDefinition stream) {
        return gateRepo.findByStreamId(stream.getId()).orElseGet(() -> {
            SwitchGate gate = new SwitchGate();
            gate.setStreamId(stream.getId());
            gate.setStatus(GateStatus.HOLD);
            gate.setCoverageThreshold(0.75);
            return gateRepo.save(gate);
        });
    }

    /** 评估闸门：证据、覆盖率、待验证升级 */
    @Transactional
    public Map<String, Object> evaluate(Long streamId) {
        StreamDefinition stream = streamService.get(streamId);
        SwitchGate gate = getOrCreate(stream);
        String target = stream.getTargetRulePackageVersion();

        List<Map<String, Object>> partitionRows = new ArrayList<>();
        List<Integer> pendingVerification = new ArrayList<>();
        List<String> holdReasons = new ArrayList<>();
        int readyCount = 0;
        int keyReadyCount = 0;
        int keyTotal = 0;

        for (PartitionConfig pc : streamService.partitions(stream)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("partitionNo", pc.partitionNo());
            row.put("roadId", pc.roadId());
            row.put("key", pc.key());

            CheckpointManifest oldCp = latestOldSchemaCheckpoint(stream.getId(), pc.partitionNo());
            boolean oldCheckpointComplete = oldCp != null && oldCp.getStatus() == CheckpointStatus.COMPLETE;

            MigrationPlan plan = planRepo.findFirstByStreamIdAndPartitionNoOrderByIdDesc(stream.getId(), pc.partitionNo())
                    .orElse(null);
            boolean planDone = plan != null && plan.getStatus() == PlanStatus.DONE;

            DualTrackRun run = runRepo.findFirstByStreamIdAndPartitionNoOrderByIdDesc(stream.getId(), pc.partitionNo())
                    .orElse(null);
            boolean runValid = run != null && run.getStatus() == DualTrackStatus.DONE
                    && target != null && target.equals(run.getNewRuleVersion());

            boolean ready = planDone && runValid;
            if (ready) readyCount++;
            if (pc.key()) {
                keyTotal++;
                if (ready) keyReadyCount++;
            }

            // 升级风险但旧作业未完成检查点 → 待验证，维持旧输出
            boolean escalationPending = false;
            if (run != null && run.getStatus() == DualTrackStatus.DONE) {
                for (DualTrackDiff diff : diffRepo.findByRunId(run.getId())) {
                    if (diff.isEscalation() && !oldCheckpointComplete) {
                        escalationPending = true;
                    }
                }
            }
            if (escalationPending) {
                pendingVerification.add(pc.partitionNo());
                holdReasons.add("分区 " + pc.partitionNo() + " 道路 " + pc.roadId()
                        + " 新规则升级风险（旧作业检查点未完成），维持旧输出并标记待验证");
            }

            row.put("oldCheckpointStatus", oldCp == null ? "MISSING" : oldCp.getStatus().name());
            row.put("planStatus", plan == null ? null : plan.getStatus().name());
            row.put("dualTrackStatus", run == null ? null : run.getStatus().name());
            row.put("dualTrackBoundVersion", run == null ? null : run.getNewRuleVersion());
            row.put("ready", ready);
            row.put("escalationPendingVerification", escalationPending);
            partitionRows.add(row);
        }

        double coverage = stream.getTotalPartitions() == 0 ? 0
                : (double) readyCount / stream.getTotalPartitions();
        boolean proofValid = proverService.currentValidProof(stream) != null;

        if (target == null) holdReasons.add("未设置升级目标规则包版本");
        if (!proofValid) holdReasons.add("无绑定目标规则包的有效状态证明（可能因规则二次修订过期）");
        if (keyReadyCount < keyTotal) holdReasons.add("关键分区未全部就绪: " + keyReadyCount + "/" + keyTotal);
        if (coverage < gate.getCoverageThreshold())
            holdReasons.add("覆盖率不足: " + readyCount + "/" + stream.getTotalPartitions()
                    + " < 阈值 " + gate.getCoverageThreshold());

        boolean canRelease = holdReasons.isEmpty();

        Map<String, Object> evaluation = new LinkedHashMap<>();
        evaluation.put("streamId", streamId);
        evaluation.put("gateStatus", gate.getStatus());
        evaluation.put("targetRuleVersion", target);
        evaluation.put("readyPartitions", readyCount);
        evaluation.put("totalPartitions", stream.getTotalPartitions());
        evaluation.put("coverage", AggregationService.round2(coverage));
        evaluation.put("coverageThreshold", gate.getCoverageThreshold());
        evaluation.put("keyPartitionsReady", keyReadyCount + "/" + keyTotal);
        evaluation.put("proofValid", proofValid);
        evaluation.put("pendingVerification", pendingVerification);
        evaluation.put("canRelease", canRelease);
        evaluation.put("holdReasons", holdReasons);
        evaluation.put("partitions", partitionRows);

        gate.setPendingVerificationJson(writeJson(pendingVerification));
        gate.setLastEvaluationJson(writeJson(evaluation));
        gateRepo.save(gate);
        return evaluation;
    }

    /** 发布新输出：证据与覆盖阈值全部满足才放行 */
    @Transactional
    public Map<String, Object> release(Long streamId) {
        Map<String, Object> evaluation = evaluate(streamId);
        if (!Boolean.TRUE.equals(evaluation.get("canRelease"))) {
            throw ApiException.conflict("闸门维持旧输出，不可发布: " + evaluation.get("holdReasons"));
        }
        StreamDefinition stream = streamService.get(streamId);
        SwitchGate gate = getOrCreate(stream);
        String target = stream.getTargetRulePackageVersion();

        Map<String, Long> switchOffsets = new LinkedHashMap<>();
        int published = 0;
        for (PartitionConfig pc : streamService.partitions(stream)) {
            long maxOffset = 0;
            for (EvidenceEntry e : evidenceRepo.findByStreamIdAndPartitionNoAndKind(
                    streamId, pc.partitionNo(), EvidenceKind.NEW_CANDIDATE)) {
                if (target.equals(e.getRuleVersion())) {
                    e.setKind(EvidenceKind.NEW_OUTPUT);
                    e.setNote((e.getNote() == null ? "" : e.getNote() + "；") + "闸门发布，新输出生效");
                    evidenceRepo.save(e);
                    published++;
                    if (e.getWatermarkOffset() != null) {
                        maxOffset = Math.max(maxOffset, e.getWatermarkOffset());
                    }
                }
            }
            switchOffsets.put(String.valueOf(pc.partitionNo()), maxOffset);
        }

        gate.setStatus(GateStatus.RELEASED);
        gate.setSwitchWatermark(Instant.now());
        gate.setSwitchOffsetJson(writeJson(switchOffsets));
        gate.setReleasedAt(Instant.now());
        gateRepo.save(gate);

        stream.setStateSchema(StateSchema.PUMP_REACH_ROAD_V2);
        stream.setRulePackageVersion(target);
        stream.setStatus(StreamStatus.SWITCHED);
        streamService.save(stream);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("released", true);
        result.put("publishedOutputs", published);
        result.put("switchWatermark", gate.getSwitchWatermark());
        result.put("switchOffsets", switchOffsets);
        result.put("streamStatus", stream.getStatus());
        return result;
    }

    public SwitchGate get(Long streamId) {
        StreamDefinition stream = streamService.get(streamId);
        return getOrCreate(stream);
    }

    private CheckpointManifest latestOldSchemaCheckpoint(Long streamId, int partitionNo) {
        return checkpointRepo.findByStreamIdAndPartitionNoOrderByConsumeOffsetDesc(streamId, partitionNo)
                .stream()
                .filter(c -> c.getStateSchema() == StateSchema.GRID_WINDOW_V1)
                .findFirst().orElse(null);
    }

    private String writeJson(Object value) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
