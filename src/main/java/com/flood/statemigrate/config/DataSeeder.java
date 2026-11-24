package com.flood.statemigrate.config;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.repo.*;
import com.flood.statemigrate.service.OutcomeLedgerService;import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 演示数据：流 FY-MAIN 四个分区。
 *  p0 检查点完成且启停守恒 → 适配；p1 快照缺失启停事件 → 限定窗口回放；
 *  p2 旧作业检查点未完成且新规则低→高 → 闸门待验证；p3 守恒满足（空启停）→ 适配。
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final StreamDefinitionRepo streamRepo;
    private final RulePackageRepo rulePackageRepo;
    private final CheckpointManifestRepo checkpointRepo;
    private final StreamEventRepo eventRepo;
    private final EvidenceEntryRepo evidenceRepo;
    private final OutcomeLedgerRepo outcomeLedgerRepo;
    private final FieldObservationRepo observationRepo;
    private final InterventionRecordRepo interventionRepo;
    private final boolean seedEnabled;

    public DataSeeder(StreamDefinitionRepo streamRepo, RulePackageRepo rulePackageRepo,
                      CheckpointManifestRepo checkpointRepo, StreamEventRepo eventRepo,
                      EvidenceEntryRepo evidenceRepo, OutcomeLedgerRepo outcomeLedgerRepo,
                      FieldObservationRepo observationRepo, InterventionRecordRepo interventionRepo,
                      @Value("${app.seed-demo-data:true}") boolean seedEnabled) {
        this.streamRepo = streamRepo;
        this.rulePackageRepo = rulePackageRepo;
        this.checkpointRepo = checkpointRepo;
        this.eventRepo = eventRepo;
        this.evidenceRepo = evidenceRepo;
        this.outcomeLedgerRepo = outcomeLedgerRepo;
        this.observationRepo = observationRepo;
        this.interventionRepo = interventionRepo;
        this.seedEnabled = seedEnabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seedEnabled || streamRepo.count() > 0) {
            return;
        }
        log.info("写入演示数据: FY-MAIN");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        StreamDefinition stream = new StreamDefinition();
        stream.setStreamCode("FY-MAIN");
        stream.setName("城区防汛主干实时计算链路");
        stream.setInputTopic("flood.telemetry.raw");
        stream.setEventTimeField("eventTime");
        stream.setWaterLevelBaseline(2.5);
        stream.setStateSchema(StateSchema.GRID_WINDOW_V1);
        stream.setRulePackageVersion("v1.0.0");
        stream.setTargetRulePackageVersion("v2.0.0");
        stream.setTotalPartitions(4);
        stream.setEventRetentionHours(48);
        stream.setPartitionConfigJson("["
                + "{\"partitionNo\":0,\"roadId\":\"R-001\",\"pumpId\":\"P-0\",\"reachId\":\"RCH-0\",\"key\":true,\"roadType\":\"ARTERIAL\",\"basin\":\"B-NORTH\"},"
                + "{\"partitionNo\":1,\"roadId\":\"R-002\",\"pumpId\":\"P-1\",\"reachId\":\"RCH-1\",\"key\":true,\"roadType\":\"COLLECTOR\",\"basin\":\"B-NORTH\"},"
                + "{\"partitionNo\":2,\"roadId\":\"R-003\",\"pumpId\":\"P-2\",\"reachId\":\"RCH-2\",\"key\":true,\"roadType\":\"ARTERIAL\",\"basin\":\"B-SOUTH\"},"
                + "{\"partitionNo\":3,\"roadId\":\"R-004\",\"pumpId\":\"P-3\",\"reachId\":\"RCH-3\",\"key\":false,\"roadType\":\"LOCAL\",\"basin\":\"B-SOUTH\"}]");
        stream.setStatus(StreamStatus.ACTIVE);
        streamRepo.save(stream);
        Long sid = stream.getId();

        RulePackage oldPkg = new RulePackage();
        oldPkg.setStreamId(sid);
        oldPkg.setVersion("v1.0.0");
        oldPkg.setKind(RuleKind.FIXED_THRESHOLD);
        oldPkg.setConfigJson("{\"medium\":20,\"high\":50}");
        oldPkg.setStatus("ACTIVE");
        rulePackageRepo.save(oldPkg);

        RulePackage newPkg = new RulePackage();
        newPkg.setStreamId(sid);
        newPkg.setVersion("v2.0.0");
        newPkg.setKind(RuleKind.MULTI_FACTOR);
        newPkg.setConfigJson("{\"medium\":25,\"high\":55,\"wRain\":0.6,\"wTrend\":1.0,"
                + "\"wLag\":0.15,\"wDepth\":0.2,\"credibilityFloor\":0.5}");
        newPkg.setStatus("ACTIVE");
        rulePackageRepo.save(newPkg);

        // 每分区事件参数：{分区, 道路, 泵站, 河段, 每10分钟雨量mm, 水位起点, 水位终点, 健康度}
        double[][] params = {
                {0, 3.5, 2.60, 2.75, 0.92},
                {1, 2.9, 2.62, 2.80, 0.88},
                {2, 1.15, 2.70, 3.50, 0.90},
                {3, 0.92, 2.55, 2.60, 0.95}};
        String[] roads = {"R-001", "R-002", "R-003", "R-004"};
        String[] pumps = {"P-0", "P-1", "P-2", "P-3"};
        String[] reaches = {"RCH-0", "RCH-1", "RCH-2", "RCH-3"};

        for (double[] pa : params) {
            int p = (int) pa[0];
            double rainPer10Min = pa[1];
            double levelStart = pa[2];
            double levelEnd = pa[3];
            double health = pa[4];
            List<StreamEvent> events = new ArrayList<>();

            for (int i = 0; i <= 18; i++) { // 每10分钟雨量，覆盖近3小时
                events.add(event(sid, p, now.minus(180 - 10L * i, ChronoUnit.MINUTES),
                        "RAIN", roads[p], null, null, "{\"mm\":" + rainPer10Min + "}"));
            }
            for (int i = 0; i <= 12; i++) { // 每15分钟水位
                double level = levelStart + (levelEnd - levelStart) * i / 12.0;
                events.add(event(sid, p, now.minus(180 - 15L * i, ChronoUnit.MINUTES),
                        "WATER_LEVEL", null, null, reaches[p],
                        "{\"levelM\":" + Math.round(level * 1000.0) / 1000.0 + "}"));
            }
            for (int i = 0; i <= 6; i++) { // 每30分钟设备健康度
                events.add(event(sid, p, now.minus(180 - 30L * i, ChronoUnit.MINUTES),
                        "DEVICE_HEALTH", null, pumps[p], null, "{\"score\":" + health + "}"));
            }
            if (p == 0) { // 泵站启停：与 p0 快照守恒（30 分钟）
                events.add(event(sid, p, now.minus(90, ChronoUnit.MINUTES), "PUMP", null, pumps[p], null,
                        "{\"on\":true}"));
                events.add(event(sid, p, now.minus(60, ChronoUnit.MINUTES), "PUMP", null, pumps[p], null,
                        "{\"on\":false}"));
            }
            if (p == 1) { // p1 实际有启停（供回放重算），但旧快照缺失该区间
                events.add(event(sid, p, now.minus(80, ChronoUnit.MINUTES), "PUMP", null, pumps[p], null,
                        "{\"on\":true}"));
                events.add(event(sid, p, now.minus(50, ChronoUnit.MINUTES), "PUMP", null, pumps[p], null,
                        "{\"on\":false}"));
            }
            if (p == 2) { // p2 人工上报与视频识别积水
                events.add(event(sid, p, now.minus(40, ChronoUnit.MINUTES), "ROAD_REPORT", roads[p], null, null,
                        "{\"depthCm\":45}"));
                events.add(event(sid, p, now.minus(30, ChronoUnit.MINUTES), "VIDEO", roads[p], null, null,
                        "{\"depthCm\":50}"));
            }
            events.sort((a, b) -> a.getEventTime().compareTo(b.getEventTime()));
            long offset = 0;
            for (StreamEvent e : events) {
                e.setEventOffset(++offset);
                eventRepo.save(e);
            }
        }

        // 检查点：p0 守恒满足；p1 缺失启停事件；p2 旧作业未完成；p3 空启停守恒满足
        CheckpointManifest cp0 = checkpoint(sid, 0, 30, now,
                "{\"gridId\":\"R-001\",\"windowStart\":\"" + now.minus(130, ChronoUnit.MINUTES) + "\","
                        + "\"windowEnd\":\"" + now.minus(10, ChronoUnit.MINUTES) + "\","
                        + "\"rain2hAcc\":45.5,\"upstreamInflowAcc\":1300.0,\"deviceHealth\":0.92,"
                        + "\"pumpRuntimeMin\":30,"
                        + "\"pumpOnOffEvents\":[{\"t\":\"" + now.minus(90, ChronoUnit.MINUTES) + "\",\"on\":true},"
                        + "{\"t\":\"" + now.minus(60, ChronoUnit.MINUTES) + "\",\"on\":false}],"
                        + "\"riskLevel\":\"MEDIUM\"}",
                CheckpointStatus.COMPLETE, "old-batch-p0-30");
        CheckpointManifest cp1 = checkpoint(sid, 1, 32, now,
                "{\"gridId\":\"R-002\",\"windowStart\":\"" + now.minus(130, ChronoUnit.MINUTES) + "\","
                        + "\"windowEnd\":\"" + now.minus(10, ChronoUnit.MINUTES) + "\","
                        + "\"rain2hAcc\":37.7,\"upstreamInflowAcc\":900.0,\"deviceHealth\":0.88,"
                        + "\"pumpRuntimeMin\":40,\"riskLevel\":\"MEDIUM\"}",
                CheckpointStatus.COMPLETE, "old-batch-p1-32");
        checkpoint(sid, 2, 25, now, null, CheckpointStatus.PENDING, "old-batch-p2-24");
        CheckpointManifest cp3 = checkpoint(sid, 3, 28, now,
                "{\"gridId\":\"R-004\",\"windowStart\":\"" + now.minus(130, ChronoUnit.MINUTES) + "\","
                        + "\"windowEnd\":\"" + now.minus(10, ChronoUnit.MINUTES) + "\","
                        + "\"rain2hAcc\":11.96,\"upstreamInflowAcc\":300.0,\"deviceHealth\":0.95,"
                        + "\"pumpRuntimeMin\":0,\"pumpOnOffEvents\":[],\"riskLevel\":\"LOW\"}",
                CheckpointStatus.COMPLETE, "old-batch-p3-28");

        // 旧作业已发布输出（证据账 OLD_OUTPUT）
        oldOutput(sid, 0, "R-001", RiskLevel.MEDIUM, 45.5, 30, "snap:chk-" + cp0.getId());
        oldOutput(sid, 1, "R-002", RiskLevel.MEDIUM, 37.7, 32, "snap:chk-" + cp1.getId());
        oldOutput(sid, 3, "R-004", RiskLevel.LOW, 11.96, 28, "snap:chk-" + cp3.getId());

        seedEvaluationDemo(sid, now);
        log.info("演示数据就绪: streamId={}", sid);
    }

    /**
     * 评估演示数据：结果账 + 观测 + 处置，覆盖四个关键场景：
     *  R-001 红色预警后抽排、水位未再上升 → 受干预观测（不计假阳性）；
     *  R-002 视频峰值后补传 → 延迟标签（评估时按当时右删失）；
     *  B-SOUTH 仅低风险 R-004 得到核验、高风险 R-003 未核验 → 覆盖选择偏差；
     *  R-003 同一积水事件跨越规则升级 → 暴露按输出时刻拆分。
     */
    private void seedEvaluationDemo(Long sid, Instant now) {
        // --- R-001(p0)：HIGH 新输出，30 分钟后抽排，抽排后视频仅 8cm ---
        Instant r1Out = now.minus(150, ChronoUnit.MINUTES);
        ledger(sid, 0, "R-001", "ARTERIAL", "B-NORTH", r1Out, "v2.0.0", "dual:run-demo",
                RiskLevel.HIGH, 72.0, 0.90, OutcomeStage.NEW_PUBLISHED, null,
                "R-001@p0-demo", now.plus(120, ChronoUnit.MINUTES));
        InterventionRecord pump = new InterventionRecord();
        pump.setStreamId(sid);
        pump.setPartitionNo(0);
        pump.setRoadId("R-001");
        pump.setType(InterventionType.PUMP_ACTION);
        pump.setAt(r1Out.plus(30, ChronoUnit.MINUTES));
        pump.setRef("P-0");
        pump.setNote("红色预警后紧急抽排");
        interventionRepo.save(pump);
        observation(sid, 0, "R-001", "B-NORTH", LabelSource.VIDEO, 8,
                r1Out.plus(45, ChronoUnit.MINUTES), r1Out.plus(45, ChronoUnit.MINUTES),
                "抽排后水位未再上升");

        // --- R-002(p1)：MEDIUM 新输出，视频峰值 35cm，3 天后才补传 ---
        Instant r2Out = now.minus(4, ChronoUnit.DAYS);
        ledger(sid, 1, "R-002", "COLLECTOR", "B-NORTH", r2Out, "v2.0.0", "dual:run-demo",
                RiskLevel.MEDIUM, 42.0, 0.88, OutcomeStage.NEW_PUBLISHED, null,
                "R-002@p1-demo", r2Out.plus(6, ChronoUnit.HOURS));
        observation(sid, 1, "R-002", "B-NORTH", LabelSource.VIDEO, 35,
                r2Out.plus(40, ChronoUnit.MINUTES), r2Out.plus(3, ChronoUnit.DAYS),
                "视频识别在积水峰值后补传");

        // --- B-SOUTH：仅低风险 R-004 核验（3cm），高风险 R-003 未核验 ---
        Instant r4Out = now.minus(120, ChronoUnit.MINUTES);
        ledger(sid, 3, "R-004", "LOCAL", "B-SOUTH", r4Out, "v2.0.0", "dual:run-demo",
                RiskLevel.LOW, 12.0, 0.95, OutcomeStage.NEW_PUBLISHED, null,
                "R-004@p3-demo", now.plus(240, ChronoUnit.MINUTES));
        observation(sid, 3, "R-004", "B-SOUTH", LabelSource.FIELD_VERIFICATION, 3,
                r4Out.plus(20, ChronoUnit.MINUTES), r4Out.plus(20, ChronoUnit.MINUTES),
                "人工核验：无积水");
        Instant r3Out = now.minus(120, ChronoUnit.MINUTES);
        ledger(sid, 2, "R-003", "ARTERIAL", "B-SOUTH", r3Out, "v2.0.0", "dual:run-demo",
                RiskLevel.HIGH, 68.0, 0.90, OutcomeStage.NEW_PUBLISHED, null,
                "R-003@p2-demo", now.plus(240, ChronoUnit.MINUTES));

        // --- R-003 同一积水事件跨越规则升级：旧输出（升级前）+ 新输出（升级后） ---
        Instant boundary = now.minus(100, ChronoUnit.MINUTES);
        Instant evtStart = now.minus(120, ChronoUnit.MINUTES);
        ledger(sid, 2, "R-003", "ARTERIAL", "B-SOUTH", evtStart.plus(10, ChronoUnit.MINUTES),
                "v1.0.0", "snap:chk-demo", RiskLevel.LOW, 18.0, 0.90, OutcomeStage.OLD_PUBLISHED,
                boundary, "R-003@EVENT-1", now.plus(240, ChronoUnit.MINUTES));
        ledger(sid, 2, "R-003", "ARTERIAL", "B-SOUTH", evtStart.plus(50, ChronoUnit.MINUTES),
                "v2.0.0", "dual:run-demo", RiskLevel.HIGH, 66.0, 0.90, OutcomeStage.NEW_PUBLISHED,
                boundary, "R-003@EVENT-1", now.plus(240, ChronoUnit.MINUTES));
    }

    private void ledger(Long sid, int p, String road, String roadType, String basin, Instant outputTime,
                        String ruleVersion, String stateVersion, RiskLevel level, double score, double cred,
                        OutcomeStage stage, Instant ruleChangeBoundary, String exposureGroup,
                        Instant observationWindowEnd) {
        OutcomeLedgerEntry e = new OutcomeLedgerEntry();
        e.setStreamId(sid);
        e.setPartitionNo(p);
        e.setRoadId(road);
        e.setRoadType(roadType);
        e.setBasin(basin);
        e.setWindowStart(outputTime.minus(120, ChronoUnit.MINUTES));
        e.setWindowEnd(outputTime);
        e.setOutputTime(outputTime);
        e.setRuleVersion(ruleVersion);
        e.setStateVersion(stateVersion);
        e.setWatermarkOffset(20);
        e.setInputCredibility(cred);
        e.setRiskLevel(level);
        e.setRiskScore(score);
        e.setDispatchPresent(false);
        e.setStage(stage);
        e.setExposureGroup(exposureGroup);
        e.setRuleChangeBoundary(ruleChangeBoundary);
        e.setObservationWindowEnd(observationWindowEnd);
        e.setExposureSplit(OutcomeLedgerService.exposureSplit(outputTime, ruleChangeBoundary));
        outcomeLedgerRepo.save(e);
    }

    private void observation(Long sid, int p, String road, String basin, LabelSource source, double depth,
                             Instant eventTime, Instant observedAt, String note) {
        FieldObservation o = new FieldObservation();
        o.setStreamId(sid);
        o.setPartitionNo(p);
        o.setRoadId(road);
        o.setBasin(basin);
        o.setSource(source);
        o.setDepthCm(depth);
        o.setEventTime(eventTime);
        o.setObservedAt(observedAt);
        o.setNote(note);
        observationRepo.save(o);
    }

    private StreamEvent event(Long sid, int p, Instant time, String type,
                              String roadId, String pumpId, String reachId, String payload) {
        StreamEvent e = new StreamEvent();
        e.setStreamId(sid);
        e.setPartitionNo(p);
        e.setEventTime(time);
        e.setType(type);
        e.setRoadId(roadId);
        e.setPumpId(pumpId);
        e.setReachId(reachId);
        e.setPayloadJson(payload);
        return e;
    }

    private CheckpointManifest checkpoint(Long sid, int p, long offset, Instant now, String snapshot,
                                          CheckpointStatus status, String batchId) {
        CheckpointManifest cp = new CheckpointManifest();
        cp.setStreamId(sid);
        cp.setPartitionNo(p);
        cp.setConsumeOffset(offset);
        cp.setWindowWatermark(now.minus(10, ChronoUnit.MINUTES));
        cp.setStateSnapshotJson(snapshot);
        cp.setOutputBatchId(batchId);
        cp.setRulePackageVersion("v1.0.0");
        cp.setStateSchema(StateSchema.GRID_WINDOW_V1);
        cp.setStatus(status);
        return checkpointRepo.save(cp);
    }

    private void oldOutput(Long sid, int p, String roadId, RiskLevel level, double score,
                           long offset, String stateVersion) {
        EvidenceEntry e = new EvidenceEntry();
        e.setStreamId(sid);
        e.setPartitionNo(p);
        e.setKind(EvidenceKind.OLD_OUTPUT);
        e.setRoadId(roadId);
        e.setRiskLevel(level);
        e.setRiskScore(score);
        e.setInputRefsJson("{\"fromOffset\":0,\"toOffset\":" + offset + ",\"eventIds\":[]}");
        e.setRuleVersion("v1.0.0");
        e.setStateVersion(stateVersion);
        e.setWatermarkOffset(offset);
        e.setNote("旧作业已发布输出");
        evidenceRepo.save(e);
    }
}
