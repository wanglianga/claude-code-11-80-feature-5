package com.flood.statemigrate;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.repo.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 测试场景构建器：流、规则包、事件、检查点。
 * 事件覆盖 now-170m 起，保证回放窗口（近 2 小时）总在事件保留范围内。
 */
class ScenarioBuilder {

    private final StreamDefinitionRepo streamRepo;
    private final RulePackageRepo rulePackageRepo;
    private final StreamEventRepo eventRepo;
    private final CheckpointManifestRepo checkpointRepo;

    ScenarioBuilder(StreamDefinitionRepo streamRepo, RulePackageRepo rulePackageRepo,
                    StreamEventRepo eventRepo, CheckpointManifestRepo checkpointRepo) {
        this.streamRepo = streamRepo;
        this.rulePackageRepo = rulePackageRepo;
        this.eventRepo = eventRepo;
        this.checkpointRepo = checkpointRepo;
    }

    StreamDefinition stream(String code, int partitions, int retentionHours) {
        StreamDefinition s = new StreamDefinition();
        s.setStreamCode(code);
        s.setName(code + " 测试流");
        s.setInputTopic("flood.test.raw");
        s.setEventTimeField("eventTime");
        s.setWaterLevelBaseline(2.5);
        s.setStateSchema(StateSchema.GRID_WINDOW_V1);
        s.setRulePackageVersion("v1.0.0");
        s.setTargetRulePackageVersion("v2.0.0");
        s.setTotalPartitions(partitions);
        s.setEventRetentionHours(retentionHours);
        StringBuilder cfg = new StringBuilder("[");
        for (int i = 0; i < partitions; i++) {
            if (i > 0) cfg.append(',');
            cfg.append("{\"partitionNo\":").append(i)
                    .append(",\"roadId\":\"R-").append(i).append("\",\"pumpId\":\"P-").append(i)
                    .append("\",\"reachId\":\"RCH-").append(i).append("\",\"key\":true}");
        }
        cfg.append("]");
        s.setPartitionConfigJson(cfg.toString());
        s.setStatus(StreamStatus.ACTIVE);
        streamRepo.save(s);

        RulePackage oldPkg = new RulePackage();
        oldPkg.setStreamId(s.getId());
        oldPkg.setVersion("v1.0.0");
        oldPkg.setKind(RuleKind.FIXED_THRESHOLD);
        oldPkg.setConfigJson("{\"medium\":20,\"high\":50}");
        oldPkg.setStatus("ACTIVE");
        rulePackageRepo.save(oldPkg);

        RulePackage newPkg = new RulePackage();
        newPkg.setStreamId(s.getId());
        newPkg.setVersion("v2.0.0");
        newPkg.setKind(RuleKind.MULTI_FACTOR);
        newPkg.setConfigJson("{\"medium\":25,\"high\":55,\"wRain\":0.6,\"wTrend\":1.0,"
                + "\"wLag\":0.15,\"wDepth\":0.2,\"credibilityFloor\":0.5}");
        newPkg.setStatus("ACTIVE");
        rulePackageRepo.save(newPkg);
        return s;
    }

    /** 常规事件：rainMmPerEvent x3（近 1 小时内），水位 levelStart→levelEnd，可选泵站启停与积水上报。
     * 按时间顺序写入，保证位点随事件时间递增。 */
    void events(Long streamId, int p, double rainMmPerEvent, double levelStart, double levelEnd,
                boolean pumpOnOff, Double depthCm) {
        Instant now = Instant.now();
        addEvent(streamId, p, now.minus(170, ChronoUnit.MINUTES), "RAIN", "{\"mm\":0.5}");
        addEvent(streamId, p, now.minus(50, ChronoUnit.MINUTES), "RAIN", "{\"mm\":" + rainMmPerEvent + "}");
        addEvent(streamId, p, now.minus(50, ChronoUnit.MINUTES), "WATER_LEVEL", "{\"levelM\":" + levelStart + "}");
        if (pumpOnOff) {
            addEvent(streamId, p, now.minus(45, ChronoUnit.MINUTES), "PUMP", "{\"on\":true}");
        }
        addEvent(streamId, p, now.minus(40, ChronoUnit.MINUTES), "DEVICE_HEALTH", "{\"score\":0.9}");
        addEvent(streamId, p, now.minus(30, ChronoUnit.MINUTES), "RAIN", "{\"mm\":" + rainMmPerEvent + "}");
        if (depthCm != null) {
            addEvent(streamId, p, now.minus(20, ChronoUnit.MINUTES), "ROAD_REPORT",
                    "{\"depthCm\":" + depthCm + "}");
        }
        if (pumpOnOff) {
            addEvent(streamId, p, now.minus(15, ChronoUnit.MINUTES), "PUMP", "{\"on\":false}");
        }
        addEvent(streamId, p, now.minus(10, ChronoUnit.MINUTES), "RAIN", "{\"mm\":" + rainMmPerEvent + "}");
        addEvent(streamId, p, now.minus(10, ChronoUnit.MINUTES), "WATER_LEVEL", "{\"levelM\":" + levelEnd + "}");
    }

    long maxOffset(Long streamId, int p) {
        return eventRepo.maxOffset(streamId, p).orElse(0L);
    }

    /** 守恒满足的旧快照（启停 30min == pumpRuntimeMin 30，窗口 120min） */
    String consistentSnapshot() {
        Instant now = Instant.now();
        return "{\"gridId\":\"G\",\"windowStart\":\"" + now.minus(125, ChronoUnit.MINUTES) + "\","
                + "\"windowEnd\":\"" + now.minus(5, ChronoUnit.MINUTES) + "\","
                + "\"rain2hAcc\":30.0,\"upstreamInflowAcc\":800.0,\"deviceHealth\":0.9,"
                + "\"pumpRuntimeMin\":30,"
                + "\"pumpOnOffEvents\":[{\"t\":\"" + now.minus(45, ChronoUnit.MINUTES) + "\",\"on\":true},"
                + "{\"t\":\"" + now.minus(15, ChronoUnit.MINUTES) + "\",\"on\":false}],"
                + "\"riskLevel\":\"MEDIUM\"}";
    }

    /** 缺失启停事件的旧快照（适配器必须拒绝，禁止零值填充） */
    String missingPumpEventsSnapshot() {
        Instant now = Instant.now();
        return "{\"gridId\":\"G\",\"windowStart\":\"" + now.minus(125, ChronoUnit.MINUTES) + "\","
                + "\"windowEnd\":\"" + now.minus(5, ChronoUnit.MINUTES) + "\","
                + "\"rain2hAcc\":30.0,\"upstreamInflowAcc\":800.0,\"deviceHealth\":0.9,"
                + "\"pumpRuntimeMin\":40,\"riskLevel\":\"MEDIUM\"}";
    }

    /** 窗口起点远超事件保留范围的旧快照 */
    String ancientWindowSnapshot() {
        Instant now = Instant.now();
        return "{\"gridId\":\"G\",\"windowStart\":\"" + now.minus(72, ChronoUnit.HOURS) + "\","
                + "\"windowEnd\":\"" + now.minus(70, ChronoUnit.HOURS) + "\","
                + "\"rain2hAcc\":30.0,\"upstreamInflowAcc\":800.0,\"deviceHealth\":0.9,"
                + "\"pumpRuntimeMin\":30,"
                + "\"pumpOnOffEvents\":[{\"t\":\"" + now.minus(715, ChronoUnit.HOURS).plus(45, ChronoUnit.MINUTES)
                + "\",\"on\":true}],\"riskLevel\":\"MEDIUM\"}";
    }

    CheckpointManifest checkpoint(Long streamId, int p, long offset, CheckpointStatus status, String snapshot) {
        CheckpointManifest cp = new CheckpointManifest();
        cp.setStreamId(streamId);
        cp.setPartitionNo(p);
        cp.setConsumeOffset(offset);
        cp.setWindowWatermark(Instant.now().minus(5, ChronoUnit.MINUTES));
        cp.setStateSnapshotJson(snapshot);
        cp.setOutputBatchId("old-batch-" + p + "-" + offset);
        cp.setRulePackageVersion("v1.0.0");
        cp.setStateSchema(StateSchema.GRID_WINDOW_V1);
        cp.setStatus(status);
        return checkpointRepo.save(cp);
    }

    private void addEvent(Long streamId, int p, Instant time, String type, String payload) {
        StreamEvent e = new StreamEvent();
        e.setStreamId(streamId);
        e.setPartitionNo(p);
        e.setEventOffset(maxOffset(streamId, p) + 1);
        e.setEventTime(time);
        e.setType(type);
        e.setRoadId("R-" + p);
        e.setPumpId("P-" + p);
        e.setReachId("RCH-" + p);
        e.setPayloadJson(payload);
        eventRepo.save(e);
    }
}
