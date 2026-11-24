package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.service.EvaluationService;
import com.flood.statemigrate.service.ObservationService;
import com.flood.statemigrate.service.OutcomeLedgerService;
import com.flood.statemigrate.service.ReleaseGateService;
import com.flood.statemigrate.web.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Tag(name = "预警结果与校准评估")
@RestController
@RequestMapping("/api/streams/{streamId}")
public class OutcomeController {

    private final OutcomeLedgerService ledgerService;
    private final ObservationService observationService;
    private final EvaluationService evaluationService;
    private final ReleaseGateService releaseGateService;

    public OutcomeController(OutcomeLedgerService ledgerService, ObservationService observationService,
                             EvaluationService evaluationService, ReleaseGateService releaseGateService) {
        this.ledgerService = ledgerService;
        this.observationService = observationService;
        this.evaluationService = evaluationService;
        this.releaseGateService = releaseGateService;
    }

    // ---------- 结果账 ----------

    @Operation(summary = "冻结结果账条目（规则版本/状态水印/输入可信度/风险输出/派单干预）")
    @PostMapping("/outcome-ledger")
    @ResponseStatus(HttpStatus.CREATED)
    public OutcomeLedgerEntry freeze(@PathVariable Long streamId, @Valid @RequestBody OutcomeLedgerReq req) {
        OutcomeLedgerEntry e = new OutcomeLedgerEntry();
        e.setPartitionNo(req.partitionNo());
        e.setRoadId(req.roadId());
        e.setRoadType(req.roadType());
        e.setBasin(req.basin());
        e.setWindowStart(req.windowStart());
        e.setWindowEnd(req.windowEnd());
        e.setOutputTime(req.outputTime());
        e.setRuleVersion(req.ruleVersion());
        e.setStateVersion(req.stateVersion());
        e.setWatermarkOffset(req.watermarkOffset());
        e.setInputCredibility(req.inputCredibility());
        e.setRiskLevel(req.riskLevel());
        e.setRiskScore(req.riskScore());
        e.setDispatchPresent(req.dispatchPresent());
        e.setInterventionType(req.interventionType());
        e.setInterventionAt(req.interventionAt());
        e.setInterventionRef(req.interventionRef());
        e.setStage(req.stage());
        e.setExposureGroup(req.exposureGroup());
        e.setRuleChangeBoundary(req.ruleChangeBoundary());
        e.setObservationWindowEnd(req.observationWindowEnd());
        return ledgerService.freeze(streamId, e);
    }

    @Operation(summary = "结果账列表（可按分区过滤）")
    @GetMapping("/outcome-ledger")
    public List<OutcomeLedgerEntry> list(@PathVariable Long streamId,
                                         @RequestParam(required = false) Integer partitionNo) {
        return ledgerService.list(streamId, partitionNo);
    }

    @Operation(summary = "结果账详情")
    @GetMapping("/outcome-ledger/{entryId}")
    public OutcomeLedgerEntry getEntry(@PathVariable Long streamId, @PathVariable Long entryId) {
        return ledgerService.get(entryId);
    }

    @Operation(summary = "暴露拆分汇总（规则升级跨越同一积水事件时按输出时刻拆分）")
    @GetMapping("/outcome-ledger/exposure")
    public Map<String, Object> exposure(@PathVariable Long streamId) {
        return ledgerService.exposureSummary(streamId);
    }

    // ---------- 观测与干预 ----------

    @Operation(summary = "写入现场观测（核验水深/视频/人工；eventTime 事件时间、observedAt 到达时间）")
    @PostMapping("/observations")
    @ResponseStatus(HttpStatus.CREATED)
    public FieldObservation addObservation(@PathVariable Long streamId, @Valid @RequestBody ObservationReq req) {
        FieldObservation o = new FieldObservation();
        o.setPartitionNo(req.partitionNo());
        o.setRoadId(req.roadId());
        o.setBasin(req.basin());
        o.setSource(req.source());
        o.setDepthCm(req.depthCm());
        o.setEventTime(req.eventTime());
        o.setObservedAt(req.observedAt());
        o.setNote(req.note());
        return observationService.recordObservation(streamId, o);
    }

    @Operation(summary = "观测列表")
    @GetMapping("/observations")
    public List<FieldObservation> observations(@PathVariable Long streamId,
                                               @RequestParam(required = false) Integer partitionNo) {
        return observationService.observations(streamId, partitionNo);
    }

    @Operation(summary = "写入处置动作（泵站抽排/道路封闭）")
    @PostMapping("/interventions")
    @ResponseStatus(HttpStatus.CREATED)
    public InterventionRecord addIntervention(@PathVariable Long streamId, @Valid @RequestBody InterventionReq req) {
        InterventionRecord r = new InterventionRecord();
        r.setPartitionNo(req.partitionNo());
        r.setRoadId(req.roadId());
        r.setType(req.type());
        r.setAt(req.at());
        r.setRef(req.ref());
        r.setNote(req.note());
        return observationService.recordIntervention(streamId, r);
    }

    @Operation(summary = "处置动作列表")
    @GetMapping("/interventions")
    public List<InterventionRecord> interventions(@PathVariable Long streamId,
                                                  @RequestParam(required = false) Integer partitionNo) {
        return observationService.interventions(streamId, partitionNo);
    }

    // ---------- 时间对齐 / 分类 ----------

    @Operation(summary = "结果覆盖与标签状态：对齐 + 分类统计（流域/道路类型/观测状态）")
    @GetMapping("/outcome-coverage")
    public Map<String, Object> coverage(@PathVariable Long streamId) {
        return evaluationService.coverageSummary(streamId);
    }

    // ---------- 校准评估 ----------

    @Operation(summary = "校准评估：等级命中/预警提前量/校准区间/右删失范围（严格或删失干预路线）")
    @PostMapping("/evaluations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> evaluate(@PathVariable Long streamId,
                                        @RequestBody(required = false) EvaluationReq req) {
        EvaluationRoute route = req == null || req.route() == null
                ? EvaluationRoute.STRICT_VERIFIED : req.route();
        return evaluationService.evaluate(streamId, route,
                req == null ? null : req.asOf(),
                req == null ? null : req.replayOfRunId());
    }

    @Operation(summary = "评估运行列表")
    @GetMapping("/evaluations")
    public List<EvaluationRun> runs(@PathVariable Long streamId) {
        return evaluationService.runs(streamId);
    }

    @Operation(summary = "评估运行详情")
    @GetMapping("/evaluations/{runId}")
    public EvaluationRun run(@PathVariable Long streamId, @PathVariable Long runId) {
        return evaluationService.getRun(runId);
    }

    // ---------- 上线门禁 ----------

    @Operation(summary = "上线门禁：仅把通过最低覆盖与安全下限的规则扩大到更多道路")
    @PostMapping("/release-gates")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> releaseGate(@PathVariable Long streamId,
                                           @RequestBody(required = false) ReleaseGateReq req) {
        ReleaseGateService.GatePolicy policy = req == null ? null
                : new ReleaseGateService.GatePolicy(
                        req.minCoverage() == null ? ReleaseGateService.GatePolicy.DEFAULT.minCoverage() : req.minCoverage(),
                        req.minStrictSamples() == null ? ReleaseGateService.GatePolicy.DEFAULT.minStrictSamples() : req.minStrictSamples(),
                        req.safetyFloor() == null ? ReleaseGateService.GatePolicy.DEFAULT.safetyFloor() : req.safetyFloor());
        return releaseGateService.evaluate(streamId, policy);
    }

    @Operation(summary = "门禁历史")
    @GetMapping("/release-gates")
    public List<ReleaseGate> gates(@PathVariable Long streamId) {
        return releaseGateService.history(streamId);
    }

    @Operation(summary = "最近一次门禁结论")
    @GetMapping("/release-gates/latest")
    public ReleaseGate latestGate(@PathVariable Long streamId) {
        return releaseGateService.latest(streamId);
    }
}
