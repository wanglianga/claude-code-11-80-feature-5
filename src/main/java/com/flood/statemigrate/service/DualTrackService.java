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
 * 双轨校验器：旧/新规则在同一输入截点并行计算，量化风险差异。
 * 新规则结论作为 NEW_CANDIDATE 写入证据账（未发布）。
 */
@Service
public class DualTrackService {

    private static final int WINDOW_HOURS = 2;

    private final DualTrackRunRepo runRepo;
    private final DualTrackDiffRepo diffRepo;
    private final StreamEventRepo eventRepo;
    private final EvidenceEntryRepo evidenceRepo;
    private final StreamService streamService;
    private final RulePackageService rulePackageService;
    private final AggregationService aggregationService;
    private final RuleEngine ruleEngine;
    private final ObjectMapper om;

    public DualTrackService(DualTrackRunRepo runRepo, DualTrackDiffRepo diffRepo, StreamEventRepo eventRepo,
                            EvidenceEntryRepo evidenceRepo, StreamService streamService,
                            RulePackageService rulePackageService, AggregationService aggregationService,
                            RuleEngine ruleEngine, ObjectMapper om) {
        this.runRepo = runRepo;
        this.diffRepo = diffRepo;
        this.eventRepo = eventRepo;
        this.evidenceRepo = evidenceRepo;
        this.streamService = streamService;
        this.rulePackageService = rulePackageService;
        this.aggregationService = aggregationService;
        this.ruleEngine = ruleEngine;
        this.om = om;
    }

    /** 对指定分区（或全部分区）启动双轨运行 */
    @Transactional
    public List<DualTrackRun> start(Long streamId, Integer partitionNo, Long inputCutOffset) {
        StreamDefinition stream = streamService.get(streamId);
        if (stream.getTargetRulePackageVersion() == null) {
            throw ApiException.conflict("流未设置升级目标规则包版本，无法双轨校验");
        }
        List<PartitionConfig> pcs = partitionNo != null
                ? List.of(streamService.partition(stream, partitionNo))
                : streamService.partitions(stream);
        List<DualTrackRun> runs = new ArrayList<>();
        for (PartitionConfig pc : pcs) {
            runs.add(runForPartition(stream, pc, inputCutOffset));
        }
        if (stream.getStatus() == StreamStatus.MIGRATING || stream.getStatus() == StreamStatus.ACTIVE) {
            stream.setStatus(StreamStatus.DUAL_TRACK);
            streamService.save(stream);
        }
        return runs;
    }

    private DualTrackRun runForPartition(StreamDefinition stream, PartitionConfig pc, Long cutOverride) {
        long cut = cutOverride != null ? cutOverride
                : eventRepo.maxOffset(stream.getId(), pc.partitionNo()).orElse(0L);
        List<StreamEvent> events = eventRepo
                .findByStreamIdAndPartitionNoAndEventOffsetLessThanEqualOrderByEventOffsetAsc(
                        stream.getId(), pc.partitionNo(), cut);
        if (events.isEmpty()) {
            throw ApiException.unprocessable("分区 " + pc.partitionNo() + " 在截点 " + cut + " 前无事件");
        }
        Instant windowEnd = events.get(events.size() - 1).getEventTime();
        AggInput agg = aggregationService.aggregate(events, stream.getWaterLevelBaseline(), windowEnd, WINDOW_HOURS);

        RulePackage oldPkg = rulePackageService.get(stream.getId(), stream.getRulePackageVersion());
        RulePackage newPkg = rulePackageService.get(stream.getId(), stream.getTargetRulePackageVersion());
        RiskResult oldResult = ruleEngine.evaluateOld(aggregationService.readTree(oldPkg.getConfigJson()), agg);
        RiskResult newResult = ruleEngine.evaluateNew(aggregationService.readTree(newPkg.getConfigJson()), agg);

        DualTrackRun run = new DualTrackRun();
        run.setStreamId(stream.getId());
        run.setPartitionNo(pc.partitionNo());
        run.setInputCutOffset(cut);
        run.setOldRuleVersion(oldPkg.getVersion());
        run.setNewRuleVersion(newPkg.getVersion());
        run.setStatus(DualTrackStatus.DONE);
        runRepo.save(run);

        DualTrackDiff diff = new DualTrackDiff();
        diff.setRunId(run.getId());
        diff.setStreamId(stream.getId());
        diff.setPartitionNo(pc.partitionNo());
        diff.setRoadId(pc.roadId());
        diff.setOldRisk(oldResult.level());
        diff.setNewRisk(newResult.level());
        diff.setOldScore(AggregationService.round2(oldResult.score()));
        diff.setNewScore(AggregationService.round2(newResult.score()));
        diff.setDelta(AggregationService.round2(newResult.score() - oldResult.score()));
        diff.setEscalation(oldResult.level().escalatesTo(newResult.level()));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("oldDetail", oldResult.detail());
        detail.put("newDetail", newResult.detail());
        diff.setDetailJson(writeJson(detail));
        diffRepo.save(diff);

        // 新规则候选输出写入证据账
        EvidenceEntry evidence = new EvidenceEntry();
        evidence.setStreamId(stream.getId());
        evidence.setPartitionNo(pc.partitionNo());
        evidence.setKind(EvidenceKind.NEW_CANDIDATE);
        evidence.setRoadId(pc.roadId());
        evidence.setRiskLevel(newResult.level());
        evidence.setRiskScore(AggregationService.round2(newResult.score()));
        evidence.setInputRefsJson(writeJson(Map.of(
                "eventIds", agg.eventIds(),
                "fromOffset", agg.fromOffset(),
                "toOffset", agg.toOffset())));
        evidence.setRuleVersion(newPkg.getVersion());
        evidence.setStateVersion("dual:run-" + run.getId());
        evidence.setWatermarkOffset(cut);
        evidence.setNote("双轨并行计算的新规则候选输出（输入截点 " + cut + "）");
        evidenceRepo.save(evidence);

        run.setSummaryJson(writeJson(Map.of(
                "roads", 1,
                "escalations", diff.isEscalation() ? 1 : 0,
                "delta", diff.getDelta(),
                "oldRisk", oldResult.level().name(),
                "newRisk", newResult.level().name(),
                "evidenceId", evidence.getId())));
        return runRepo.save(run);
    }

    public List<DualTrackRun> list(Long streamId) {
        return runRepo.findByStreamIdOrderByIdAsc(streamId);
    }

    public DualTrackRun get(Long runId) {
        return runRepo.findById(runId).orElseThrow(() -> ApiException.notFound("双轨运行不存在: " + runId));
    }

    public List<DualTrackDiff> diffs(Long runId) {
        return diffRepo.findByRunId(runId);
    }

    private String writeJson(Object value) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
