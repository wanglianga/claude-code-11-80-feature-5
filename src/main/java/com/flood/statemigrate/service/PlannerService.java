package com.flood.statemigrate.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 迁移计划器：依据状态可逆性、事件保留范围与汛情时效，为各分区选择
 * ADAPT（经证明的状态适配）/ REPLAY（限定时间窗回放）/ MANUAL（人工裁定），
 * 也支持混合路线。适配器执行时若发现旧窗口累计量无法满足泵站启停守恒，
 * 自动转为限定时间窗回放；缺失启停区间的快照一律拒绝，绝不零值填充。
 */
@Service
public class PlannerService {

    private static final int WINDOW_HOURS = 2;

    private final MigrationPlanRepo planRepo;
    private final CheckpointManifestRepo checkpointRepo;
    private final StreamEventRepo eventRepo;
    private final EvidenceEntryRepo evidenceRepo;
    private final SwitchGateRepo gateRepo;
    private final StreamService streamService;
    private final ProverService proverService;
    private final AggregationService aggregationService;
    private final RuleEngine ruleEngine;
    private final RulePackageService rulePackageService;
    private final ObjectMapper om;

    public PlannerService(MigrationPlanRepo planRepo, CheckpointManifestRepo checkpointRepo,
                          StreamEventRepo eventRepo, EvidenceEntryRepo evidenceRepo, SwitchGateRepo gateRepo,
                          StreamService streamService, ProverService proverService,
                          AggregationService aggregationService, RuleEngine ruleEngine,
                          RulePackageService rulePackageService, ObjectMapper om) {
        this.planRepo = planRepo;
        this.checkpointRepo = checkpointRepo;
        this.eventRepo = eventRepo;
        this.evidenceRepo = evidenceRepo;
        this.gateRepo = gateRepo;
        this.streamService = streamService;
        this.proverService = proverService;
        this.aggregationService = aggregationService;
        this.ruleEngine = ruleEngine;
        this.rulePackageService = rulePackageService;
        this.om = om;
    }

    /** 为全部分区生成迁移计划 */
    @Transactional
    public List<MigrationPlan> generatePlans(Long streamId) {
        StreamDefinition stream = streamService.get(streamId);
        StateProof proof = proverService.currentValidProof(stream);
        if (proof == null) {
            throw ApiException.conflict("无绑定目标规则包的有效状态证明，请先运行状态语义证明器");
        }
        List<MigrationPlan> plans = new ArrayList<>();
        for (PartitionConfig pc : streamService.partitions(stream)) {
            plans.add(planForPartition(stream, pc, proof));
        }
        stream.setStatus(StreamStatus.MIGRATING);
        streamService.save(stream);
        return plans;
    }

    private MigrationPlan planForPartition(StreamDefinition stream, PartitionConfig pc, StateProof proof) {
        MigrationPlan plan = new MigrationPlan();
        plan.setStreamId(stream.getId());
        plan.setPartitionNo(pc.partitionNo());
        plan.setProofId(proof.getId());
        plan.setStatus(PlanStatus.PLANNED);

        CheckpointManifest cp = checkpointRepo.findFirstByStreamIdAndPartitionNoOrderByConsumeOffsetDescIdDesc(stream.getId(), pc.partitionNo()).orElse(null);
        Instant now = Instant.now();

        if (cp == null || cp.getStateSnapshotJson() == null || cp.getStatus() != CheckpointStatus.COMPLETE) {
            // 无可用旧快照（如旧作业尚未完成检查点）→ 限定窗口回放
            plan.setRoute(PlanRoute.REPLAY);
            plan.setReason("无可用旧状态快照（检查点缺失或未完成），采用限定时间窗回放");
            boundReplayWindow(stream, pc, plan, now.minus(Duration.ofHours(WINDOW_HOURS)), now);
            return planRepo.save(plan);
        }

        JsonNode snapshot = aggregationService.readTree(cp.getStateSnapshotJson());
        List<ConservationResult> results = new ArrayList<>();
        results.addAll(aggregationService.checkPumpConservation(snapshot));
        results.add(aggregationService.checkWindowGranularity(snapshot));
        List<String> failed = results.stream().filter(r -> !r.satisfied()).map(ConservationResult::detail).toList();

        if (failed.isEmpty()) {
            plan.setRoute(PlanRoute.ADAPT);
            plan.setReason("守恒条件全部满足（" + results.size() + "项），状态字段可逆，采用经证明的状态适配器");
            return planRepo.save(plan);
        }

        // 守恒不满足 → 限定时间窗回放；事件保留范围不足 → 人工裁定
        Instant neededStart = snapshot.path("windowStart").isTextual()
                ? Instant.parse(snapshot.path("windowStart").asText())
                : now.minus(Duration.ofHours(WINDOW_HOURS));
        plan.setRoute(PlanRoute.REPLAY);
        plan.setReason("旧窗口累计量不满足守恒条件（" + String.join("；", failed) + "），转为限定时间窗回放");
        boundReplayWindow(stream, pc, plan, neededStart, now);
        if (plan.getRoute() == PlanRoute.MANUAL) {
            plan.setReason("守恒不满足且事件保留范围不足以回放所需窗口，转人工裁定: " + String.join("；", failed));
        }
        return planRepo.save(plan);
    }

    /** 依据事件保留范围限定回放窗口；保留不足时降级为人工裁定 */
    private void boundReplayWindow(StreamDefinition stream, PartitionConfig pc, MigrationPlan plan,
                                   Instant neededStart, Instant windowEnd) {
        Instant retentionStart = Instant.now().minus(Duration.ofHours(stream.getEventRetentionHours()));
        Instant earliest = eventRepo.earliestEventTime(stream.getId(), pc.partitionNo()).orElse(null);
        Instant availableStart = earliest != null && earliest.isAfter(retentionStart) ? earliest : retentionStart;
        if (earliest == null || neededStart.isBefore(availableStart)) {
            plan.setRoute(PlanRoute.MANUAL);
            return;
        }
        plan.setReplayWindowStart(neededStart);
        plan.setReplayWindowEnd(windowEnd);
    }

    /** 执行迁移计划（ADAPT 失败自动转 REPLAY） */
    @Transactional
    public MigrationPlan execute(Long planId, Instant overrideStart, Instant overrideEnd) {
        MigrationPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> ApiException.notFound("迁移计划不存在: " + planId));
        if (plan.getStatus() == PlanStatus.STALE) {
            throw ApiException.conflict("计划绑定的证明已过期（规则包二次修订），请重新编排后再执行");
        }
        if (plan.getRoute() == PlanRoute.MANUAL) {
            throw ApiException.conflict("该分区需人工裁定路线后方可执行");
        }
        StreamDefinition stream = streamService.get(plan.getStreamId());
        PartitionConfig pc = streamService.partition(stream, plan.getPartitionNo());
        plan.setStatus(PlanStatus.RUNNING);
        planRepo.save(plan);

        if (plan.getRoute() == PlanRoute.ADAPT) {
            boolean adapted = tryAdapt(stream, pc, plan);
            if (!adapted) {
                // 适配器发现守恒不满足 → 转为限定时间窗回放
                plan.setRoute(PlanRoute.REPLAY);
                plan.setReason(plan.getReason() + "；适配器执行时发现旧窗口累计量无法满足泵站启停守恒，转为限定时间窗回放");
                Instant start = plan.getReplayWindowStart() != null ? plan.getReplayWindowStart()
                        : Instant.now().minus(Duration.ofHours(WINDOW_HOURS));
                boundReplayWindow(stream, pc, plan, start, Instant.now());
                if (plan.getRoute() == PlanRoute.MANUAL) {
                    plan.setStatus(PlanStatus.PLANNED);
                    return planRepo.save(plan);
                }
            } else {
                plan.setStatus(PlanStatus.DONE);
                return planRepo.save(plan);
            }
        }
        return doReplay(stream, pc, plan, overrideStart, overrideEnd);
    }

    /** 状态适配器：守恒核验通过后把旧聚合提升为三层状态；核验失败返回 false（拒绝零值填充） */
    private boolean tryAdapt(StreamDefinition stream, PartitionConfig pc, MigrationPlan plan) {
        CheckpointManifest cp = checkpointRepo.findFirstByStreamIdAndPartitionNoOrderByConsumeOffsetDescIdDesc(stream.getId(), pc.partitionNo()).orElse(null);
        if (cp == null || cp.getStateSnapshotJson() == null) {
            plan.setReason(plan.getReason() + "；适配时无快照可用");
            return false;
        }
        JsonNode snapshot = aggregationService.readTree(cp.getStateSnapshotJson());
        List<ConservationResult> results = new ArrayList<>();
        results.addAll(aggregationService.checkPumpConservation(snapshot));
        results.add(aggregationService.checkWindowGranularity(snapshot));
        boolean allOk = results.stream().allMatch(ConservationResult::satisfied);
        if (!allOk) {
            return false; // 拒绝适配，调用方转回放；绝不用零值补齐缺失启停区间
        }

        Map<String, Object> layered = new LinkedHashMap<>();
        Map<String, Object> pump = new LinkedHashMap<>();
        pump.put("pumpId", pc.pumpId());
        pump.put("runtimeMin", snapshot.path("pumpRuntimeMin").asDouble());
        pump.put("onOffEvents", om.convertValue(snapshot.path("pumpOnOffEvents"), List.class));
        pump.put("lagMin", snapshot.path("pumpLagMin").isNumber() ? snapshot.path("pumpLagMin").asDouble() : null);
        Map<String, Object> reach = new LinkedHashMap<>();
        reach.put("reachId", pc.reachId());
        reach.put("inflowAcc", snapshot.path("upstreamInflowAcc").asDouble());
        Map<String, Object> road = new LinkedHashMap<>();
        road.put("roadId", pc.roadId());
        road.put("rain2hAcc", snapshot.path("rain2hAcc").asDouble());
        road.put("credibility", snapshot.path("deviceHealth").asDouble());
        layered.put("pump", pump);
        layered.put("reach", reach);
        layered.put("road", road);
        layered.put("windowStart", snapshot.path("windowStart").asText());
        layered.put("windowEnd", snapshot.path("windowEnd").asText());
        layered.put("migratedFrom", "snap:chk-" + cp.getId());

        CheckpointManifest migrated = new CheckpointManifest();
        migrated.setStreamId(stream.getId());
        migrated.setPartitionNo(pc.partitionNo());
        migrated.setConsumeOffset(cp.getConsumeOffset());
        migrated.setWindowWatermark(cp.getWindowWatermark());
        migrated.setStateSnapshotJson(writeJson(layered));
        migrated.setOutputBatchId("adapt-plan-" + plan.getId());
        migrated.setRulePackageVersion(stream.getTargetRulePackageVersion());
        migrated.setStateSchema(StateSchema.PUMP_REACH_ROAD_V2);
        migrated.setStatus(CheckpointStatus.COMPLETE);
        checkpointRepo.save(migrated);

        plan.setResultJson(writeJson(Map.of(
                "adapted", true,
                "checkpointId", migrated.getId(),
                "conservationChecks", results.stream().map(r -> Map.of(
                        "name", r.name(), "satisfied", r.satisfied(), "detail", r.detail())).toList())));
        return true;
    }

    /** 限定时间窗回放：从冻结位点重算三层状态并产出新规则候选输出 */
    private MigrationPlan doReplay(StreamDefinition stream, PartitionConfig pc, MigrationPlan plan,
                                   Instant overrideStart, Instant overrideEnd) {
        Instant windowStart = overrideStart != null ? overrideStart : plan.getReplayWindowStart();
        Instant windowEnd = overrideEnd != null ? overrideEnd : plan.getReplayWindowEnd();
        if (windowStart == null || windowEnd == null) {
            windowEnd = Instant.now();
            windowStart = windowEnd.minus(Duration.ofHours(WINDOW_HOURS));
        }
        // 事件保留范围核验
        Instant earliest = eventRepo.earliestEventTime(stream.getId(), pc.partitionNo()).orElse(null);
        if (earliest == null) {
            plan.setStatus(PlanStatus.FAILED);
            plan.setReason(plan.getReason() + "；分区内无事件可回放");
            return planRepo.save(plan);
        }
        if (windowStart.isBefore(earliest)) {
            plan.setRoute(PlanRoute.MANUAL);
            plan.setStatus(PlanStatus.PLANNED);
            plan.setReason(plan.getReason() + "；事件保留范围不足（最早事件 " + earliest + "），转人工裁定");
            return planRepo.save(plan);
        }

        List<StreamEvent> events = eventRepo
                .findByStreamIdAndPartitionNoAndEventTimeBetweenOrderByEventTimeAsc(
                        stream.getId(), pc.partitionNo(), windowStart, windowEnd);
        if (events.isEmpty()) {
            plan.setStatus(PlanStatus.FAILED);
            plan.setReason(plan.getReason() + "；回放窗口内无事件");
            return planRepo.save(plan);
        }

        AggregationService.LayeredState state = aggregationService.buildLayeredState(
                pc, events, stream.getWaterLevelBaseline(), windowStart, windowEnd);
        AggInput agg = state.agg();

        Map<String, Object> layered = new LinkedHashMap<>();
        layered.put("pump", Map.of(
                "pumpId", pc.pumpId(),
                "runtimeMin", state.pumpRuntimeMin(),
                "onOffEvents", state.pumpTransitions().stream()
                        .map(t -> Map.of("t", t.t().toString(), "on", t.on())).toList(),
                "lagMin", agg.pumpLagMin(),
                "dataPresent", agg.pumpDataPresent()));
        layered.put("reach", Map.of(
                "reachId", pc.reachId(),
                "levelTrendCmPerHour", agg.trendCmPerHour(),
                "lastLevelM", state.lastLevelM() == null ? "n/a" : state.lastLevelM(),
                "baselineDeviationM", state.lastLevelM() == null ? "n/a"
                        : AggregationService.round2(state.lastLevelM() - stream.getWaterLevelBaseline())));
        layered.put("road", Map.of(
                "roadId", pc.roadId(),
                "rain2hAcc", agg.rain2h(),
                "credibility", agg.credibility(),
                "reportDepthCm", agg.reportDepthCm()));
        layered.put("windowStart", windowStart.toString());
        layered.put("windowEnd", windowEnd.toString());
        layered.put("migratedFrom", "replay:plan-" + plan.getId());

        CheckpointManifest migrated = new CheckpointManifest();
        migrated.setStreamId(stream.getId());
        migrated.setPartitionNo(pc.partitionNo());
        migrated.setConsumeOffset(agg.toOffset());
        migrated.setWindowWatermark(windowEnd);
        migrated.setStateSnapshotJson(writeJson(layered));
        migrated.setOutputBatchId("replay-plan-" + plan.getId());
        migrated.setRulePackageVersion(stream.getTargetRulePackageVersion());
        migrated.setStateSchema(StateSchema.PUMP_REACH_ROAD_V2);
        migrated.setStatus(CheckpointStatus.COMPLETE);
        checkpointRepo.save(migrated);

        // 新规则候选输出（证据账）
        RulePackage targetPkg = rulePackageService.get(stream.getId(), stream.getTargetRulePackageVersion());
        RiskResult risk = ruleEngine.evaluateNew(
                aggregationService.readTree(targetPkg.getConfigJson()), agg);

        // 回放越过切换水印：已发布的新输出作废为已撤回，本次结果仅作候选
        SwitchGate gate = gateRepo.findByStreamId(stream.getId()).orElse(null);
        boolean crossesSwitchWatermark = false;
        int retracted = 0;
        if (gate != null && gate.getStatus() == GateStatus.RELEASED && gate.getSwitchOffsetJson() != null) {
            JsonNode offsets = aggregationService.readTree(gate.getSwitchOffsetJson());
            JsonNode switchOffset = offsets.get(String.valueOf(pc.partitionNo()));
            if (switchOffset != null && agg.toOffset() > switchOffset.asLong()) {
                crossesSwitchWatermark = true;
                for (EvidenceEntry e : evidenceRepo.findByStreamIdAndPartitionNoAndKind(
                        stream.getId(), pc.partitionNo(), EvidenceKind.NEW_OUTPUT)) {
                    if (e.getWatermarkOffset() != null && e.getWatermarkOffset() >= agg.fromOffset()) {
                        e.setKind(EvidenceKind.RETRACTED);
                        e.setNote((e.getNote() == null ? "" : e.getNote() + "；")
                                + "回放越过切换水印，该输出范围撤回（plan-" + plan.getId() + "）");
                        evidenceRepo.save(e);
                        retracted++;
                    }
                }
            }
        }

        EvidenceEntry evidence = new EvidenceEntry();
        evidence.setStreamId(stream.getId());
        evidence.setPartitionNo(pc.partitionNo());
        evidence.setKind(EvidenceKind.NEW_CANDIDATE);
        evidence.setRoadId(pc.roadId());
        evidence.setRiskLevel(risk.level());
        evidence.setRiskScore(AggregationService.round2(risk.score()));
        evidence.setInputRefsJson(writeJson(Map.of(
                "eventIds", agg.eventIds(),
                "fromOffset", agg.fromOffset(),
                "toOffset", agg.toOffset())));
        evidence.setRuleVersion(stream.getTargetRulePackageVersion());
        evidence.setStateVersion("replay:plan-" + plan.getId());
        evidence.setWatermarkOffset(agg.toOffset());
        evidence.setNote(crossesSwitchWatermark
                ? "回放越过切换水印，作为新候选输出；已撤回范围内旧新输出 " + retracted + " 条"
                : "限定时间窗回放产生的新规则候选输出");
        evidenceRepo.save(evidence);

        plan.setReplayWindowStart(windowStart);
        plan.setReplayWindowEnd(windowEnd);
        plan.setReplayFromOffset(agg.fromOffset());
        plan.setReplayToOffset(agg.toOffset());
        plan.setStatus(PlanStatus.DONE);
        plan.setResultJson(writeJson(Map.of(
                "replayed", true,
                "events", events.size(),
                "checkpointId", migrated.getId(),
                "evidenceId", evidence.getId(),
                "crossesSwitchWatermark", crossesSwitchWatermark,
                "retractedOutputs", retracted)));
        return planRepo.save(plan);
    }

    /** 人工裁定：为 MANUAL 计划指定路线 */
    @Transactional
    public MigrationPlan adjudicate(Long planId, PlanRoute route, String reason) {
        MigrationPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> ApiException.notFound("迁移计划不存在: " + planId));
        if (plan.getRoute() != PlanRoute.MANUAL) {
            throw ApiException.conflict("仅 MANUAL 路线的计划需要人工裁定");
        }
        if (route == PlanRoute.MANUAL) {
            throw ApiException.badRequest("裁定路线须为 ADAPT 或 REPLAY");
        }
        plan.setRoute(route);
        plan.setStatus(PlanStatus.PLANNED);
        plan.setReason(plan.getReason() + "；人工裁定采用 " + route + ": " + reason);
        return planRepo.save(plan);
    }

    public List<MigrationPlan> list(Long streamId) {
        return planRepo.findByStreamIdOrderByPartitionNoAsc(streamId);
    }

    public MigrationPlan get(Long planId) {
        return planRepo.findById(planId).orElseThrow(() -> ApiException.notFound("迁移计划不存在: " + planId));
    }

    private String writeJson(Object value) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
