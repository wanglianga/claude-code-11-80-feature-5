package com.flood.statemigrate;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * 结果账 / 标签可观察性分类器 / 时间对齐器 / 校准评估器 / 上线门禁。
 * 四个关键业务场景：
 *  1) 红色预警后抽排且水位未再上升 → 受干预观测，不计假阳性；
 *  2) 视频在峰值后补传 → 按事件时间纳入并保留当时右删失版本；
 *  3) 某流域仅低风险道路核验 → 门禁标记覆盖选择偏差并阻止全流域结论；
 *  4) 规则升级跨越同一积水事件 → 结果账按输出时刻拆分暴露。
 */
class OutcomeEvaluationTest extends BaseIntegrationTest {

    @Autowired private OutcomeLedgerService ledgerService;
    @Autowired private ObservationService observationService;
    @Autowired private EvaluationService evaluationService;
    @Autowired private ReleaseGateService releaseGateService;
    @Autowired private StreamService streamService;

    /** 建一个带流域/道路类型的流 */
    private StreamDefinition streamWithBasins(String code, String[][] parts) {
        StreamDefinition s = new StreamDefinition();
        s.setStreamCode(code);
        s.setName(code);
        s.setInputTopic("flood.test.raw");
        s.setEventTimeField("eventTime");
        s.setWaterLevelBaseline(2.5);
        s.setStateSchema(StateSchema.GRID_WINDOW_V1);
        s.setRulePackageVersion("v1.0.0");
        s.setTargetRulePackageVersion("v2.0.0");
        s.setTotalPartitions(parts.length);
        s.setEventRetentionHours(48);
        StringBuilder cfg = new StringBuilder("[");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) cfg.append(',');
            cfg.append("{\"partitionNo\":").append(i)
                    .append(",\"roadId\":\"").append(parts[i][0]).append("\"")
                    .append(",\"pumpId\":\"P-").append(i).append("\"")
                    .append(",\"reachId\":\"RCH-").append(i).append("\"")
                    .append(",\"key\":true")
                    .append(",\"roadType\":\"").append(parts[i][1]).append("\"")
                    .append(",\"basin\":\"").append(parts[i][2]).append("\"}");
        }
        cfg.append("]");
        s.setPartitionConfigJson(cfg.toString());
        s.setStatus(StreamStatus.ACTIVE);
        return streamRepo.save(s);
    }

    private OutcomeLedgerEntry entry(int p, String road, String stage, RiskLevel level, Instant outputTime) {
        OutcomeLedgerEntry e = new OutcomeLedgerEntry();
        e.setPartitionNo(p);
        e.setRoadId(road);
        e.setOutputTime(outputTime);
        e.setWindowStart(outputTime.minus(2, ChronoUnit.HOURS));
        e.setWindowEnd(outputTime);
        e.setRuleVersion("v2.0.0");
        e.setStateVersion("dual:run-1");
        e.setWatermarkOffset(10);
        e.setInputCredibility(0.9);
        e.setRiskLevel(level);
        e.setRiskScore(level == RiskLevel.HIGH ? 70 : level == RiskLevel.MEDIUM ? 40 : 10);
        e.setStage(OutcomeStage.valueOf(stage));
        e.setDispatchPresent(false);
        e.setObservationWindowEnd(outputTime.plus(6, ChronoUnit.HOURS));
        return e;
    }

    private FieldObservation obs(int p, String road, LabelSource source, double depth,
                                 Instant eventTime, Instant observedAt) {
        FieldObservation o = new FieldObservation();
        o.setPartitionNo(p);
        o.setRoadId(road);
        o.setSource(source);
        o.setDepthCm(depth);
        o.setEventTime(eventTime);
        o.setObservedAt(observedAt);
        return o;
    }

    private InterventionRecord pump(int p, String road, Instant at) {
        InterventionRecord r = new InterventionRecord();
        r.setPartitionNo(p);
        r.setRoadId(road);
        r.setType(InterventionType.PUMP_ACTION);
        r.setAt(at);
        r.setRef("P-pump");
        return r;
    }

    @Test
    void interventionAfterRedWarningIsNotCountedAsFalsePositive() {
        StreamDefinition s = streamWithBasins("T-INTERV", new String[][]{{"R-0", "ARTERIAL", "B-1"}});
        Instant t0 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        // 红色预警（HIGH），之后抽排
        OutcomeLedgerEntry e = ledgerService.freeze(s.getId(), entry(0, "R-0", "NEW_PUBLISHED", RiskLevel.HIGH, t0));
        Instant pumpAt = t0.plus(30, ChronoUnit.MINUTES);
        observationService.recordIntervention(s.getId(), pump(0, "R-0", pumpAt));
        // 处置之后的水位观测：未再上升（8cm）
        observationService.recordObservation(s.getId(), obs(0, "R-0", LabelSource.VIDEO, 8,
                t0.plus(60, ChronoUnit.MINUTES), t0.plus(60, ChronoUnit.MINUTES)));

        Map<String, Object> result = evaluationService.evaluate(s.getId(), EvaluationRoute.STRICT_VERIFIED, null, null);
        OutcomeLedgerEntry reloaded = ledgerService.get(e.getId());

        assertThat(reloaded.getObservability()).isEqualTo(ObservabilityClass.INTERVENTION_AFFECTED);
        assertThat(reloaded.isPostIntervention()).isTrue();
        assertThat(result.get("interventionAffectedCount")).isEqualTo(1);
        // 受干预观测不计入假阳性（严格集排除，overall 假阳性为 0）
        @SuppressWarnings("unchecked")
        Map<String, Object> overall = (Map<String, Object>) result.get("overall");
        assertThat((int) overall.get("falsePositives")).isZero();
        assertThat(reloaded.getClassificationReason()).contains("不得计作假阳性");
    }

    @Test
    void lateArrivingVideoIsIncludedByEventTimeButRightCensoredAtTheTime() {
        StreamDefinition s = streamWithBasins("T-CENSOR", new String[][]{{"R-0", "COLLECTOR", "B-1"}});
        // 输出在 4 天前；视频在事件后 3 天补传（相对现在 1 天前已到达）
        Instant t0 = Instant.now().minus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        OutcomeLedgerEntry e = ledgerService.freeze(s.getId(), entry(0, "R-0", "NEW_PUBLISHED", RiskLevel.MEDIUM, t0));
        // 峰值在 t0+40m 发生，但 asOf=t0+50m 时尚未到达；实际 t0+3d 才补传
        Instant peak = t0.plus(40, ChronoUnit.MINUTES);
        Instant arrived = t0.plus(3, ChronoUnit.DAYS);
        observationService.recordObservation(s.getId(), obs(0, "R-0", LabelSource.VIDEO, 35, peak, arrived));

        // 当时（asOf = t0+50m）评估：标签尚未到达 → 右删失版本
        Instant asOfThen = t0.plus(50, ChronoUnit.MINUTES);
        Map<String, Object> then = evaluationService.evaluate(
                s.getId(), EvaluationRoute.CENSORED_INTERVENTION, asOfThen, null);
        OutcomeLedgerEntry afterThen = ledgerService.get(e.getId());
        assertThat(afterThen.isCensored()).isTrue();
        assertThat((int) then.get("rightCensoredCount")).isEqualTo(1);
        Long runThenId = (Long) then.get("evaluationRunId");

        // 事后（asOf=now）重评：标签按事件时间纳入并命中（实际 HIGH）
        Map<String, Object> now = evaluationService.evaluate(
                s.getId(), EvaluationRoute.CENSORED_INTERVENTION, Instant.now(), runThenId);
        OutcomeLedgerEntry afterNow = ledgerService.get(e.getId());
        assertThat(afterNow.isCensored()).isFalse();
        assertThat(afterNow.getObservability()).isEqualTo(ObservabilityClass.DELAYED_VIDEO);
        assertThat(afterNow.getLabelEventTime()).isEqualTo(peak);
        assertThat((int) now.get("rightCensoredCount")).isZero();

        // 当时右删失版本的评估运行被保留，可追溯到本次重评
        EvaluationRun thenRun = evaluationService.getRun(runThenId);
        assertThat(thenRun.getResultJson()).contains("右删失");
        assertThat(now.get("replayOfRunId")).isEqualTo(runThenId);
    }

    @Test
    void gateFlagsCoverageSelectionBiasWhenOnlyLowRiskRoadsVerified() {
        // B-1 流域两条道路：R-0 低风险、R-1 高风险
        StreamDefinition s = streamWithBasins("T-BIAS",
                new String[][]{{"R-0", "LOCAL", "B-1"}, {"R-1", "ARTERIAL", "B-1"}});
        Instant t0 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        ledgerService.freeze(s.getId(), entry(0, "R-0", "NEW_PUBLISHED", RiskLevel.LOW, t0));
        ledgerService.freeze(s.getId(), entry(1, "R-1", "NEW_PUBLISHED", RiskLevel.HIGH, t0));
        // 只有低风险 R-0 得到人工核验（无积水 3cm）
        observationService.recordObservation(s.getId(), obs(0, "R-0", LabelSource.FIELD_VERIFICATION, 3,
                t0.plus(30, ChronoUnit.MINUTES), t0.plus(30, ChronoUnit.MINUTES)));

        Map<String, Object> gate = releaseGateService.evaluate(s.getId(), null);

        assertThat(gate.get("coverageBias")).isEqualTo(true);
        assertThat(gate.get("decision")).isEqualTo(GateDecision.BLOCK);
        assertThat(gate.get("reasons").toString()).contains("覆盖选择偏差");
        // 该流域道路被阻止，不得据此扩大
        assertThat((List<String>) gate.get("blockedRoadIds")).contains("R-0", "R-1");
        assertThat((List<String>) gate.get("allowedRoadIds")).isEmpty();
    }

    @Test
    void ruleUpgradeAcrossSameEventSplitsExposureByOutputTime() {
        StreamDefinition s = streamWithBasins("T-SPLIT", new String[][]{{"R-0", "ARTERIAL", "B-1"}});
        Instant event = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Instant boundary = event.plus(20, ChronoUnit.MINUTES); // 规则在事件中途升级
        String group = "R-0@EVENT-1";

        OutcomeLedgerEntry before = entry(0, "R-0", "OLD_PUBLISHED", RiskLevel.LOW, event.plus(5, ChronoUnit.MINUTES));
        before.setRuleVersion("v1.0.0");
        before.setExposureGroup(group);
        before.setRuleChangeBoundary(boundary);
        ledgerService.freeze(s.getId(), before);

        OutcomeLedgerEntry after = entry(0, "R-0", "NEW_PUBLISHED", RiskLevel.HIGH, event.plus(40, ChronoUnit.MINUTES));
        after.setExposureGroup(group);
        after.setRuleChangeBoundary(boundary);
        ledgerService.freeze(s.getId(), after);

        Map<String, Object> exposure = ledgerService.exposureSummary(s.getId());

        assertThat(before.getExposureSplit()).isEqualTo("BEFORE_RULE_CHANGE");
        assertThat(after.getExposureSplit()).isEqualTo("AFTER_RULE_CHANGE");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) exposure.get("groups");
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).get("crossEventSplit")).isEqualTo(true);
        assertThat(groups.get(0).get("ruleVersions").toString()).contains("v1.0.0", "v2.0.0");
    }

    @Test
    void shadowScreeningIsExcludedFromBenefitsAndGate() {
        StreamDefinition s = streamWithBasins("T-SHADOW", new String[][]{{"R-0", "ARTERIAL", "B-1"}});
        Instant t0 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        OutcomeLedgerEntry e = ledgerService.freeze(s.getId(),
                entry(0, "R-0", "SHADOW_SCREENING", RiskLevel.HIGH, t0));
        observationService.recordObservation(s.getId(), obs(0, "R-0", LabelSource.FIELD_VERIFICATION, 40,
                t0.plus(30, ChronoUnit.MINUTES), t0.plus(30, ChronoUnit.MINUTES)));

        Map<String, Object> result = evaluationService.evaluate(
                s.getId(), EvaluationRoute.CENSORED_INTERVENTION, null, null);
        assertThat((int) result.get("shadowScreeningCount")).isEqualTo(1);
        assertThat(result.get("notes").toString()).contains("仅作筛查");

        Map<String, Object> gate = releaseGateService.evaluate(s.getId(), null);
        // 影子运行不参与放行：无严格核验覆盖 → BLOCK
        assertThat(gate.get("decision")).isEqualTo(GateDecision.BLOCK);
        assertThat(gate.get("shadowScreeningExcluded")).isEqualTo(1L);
    }

    @Test
    void gateAllowsWhenCoverageAndSafetyMet() {
        StreamDefinition s = streamWithBasins("T-ALLOW",
                new String[][]{{"R-0", "ARTERIAL", "B-1"}, {"R-1", "COLLECTOR", "B-1"}});
        Instant t0 = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        // 两条道路均 HIGH 预警且均核验为真实积水 → 覆盖 1.0，无漏报
        for (int p = 0; p < 2; p++) {
            String road = "R-" + p;
            ledgerService.freeze(s.getId(), entry(p, road, "NEW_PUBLISHED", RiskLevel.HIGH, t0));
            observationService.recordObservation(s.getId(), obs(p, road, LabelSource.FIELD_VERIFICATION, 35,
                    t0.plus(30, ChronoUnit.MINUTES), t0.plus(30, ChronoUnit.MINUTES)));
        }

        Map<String, Object> gate = releaseGateService.evaluate(s.getId(),
                new ReleaseGateService.GatePolicy(0.5, 2, 0.2));

        assertThat(gate.get("decision")).isEqualTo(GateDecision.ALLOW);
        assertThat((List<String>) gate.get("allowedRoadIds")).containsExactly("R-0", "R-1");
        assertThat((double) gate.get("measuredCoverage")).isEqualTo(1.0);
        assertThat((double) gate.get("measuredSafety")).isEqualTo(1.0);
    }

    @Test
    void evaluationProducesStratifiedCalibrationAndLeadTime() {
        StreamDefinition s = streamWithBasins("T-STRATA",
                new String[][]{{"R-0", "ARTERIAL", "B-1"}, {"R-1", "LOCAL", "B-2"}});
        Instant t0 = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        // R-0: HIGH 预警，35cm 核验积水（命中 HIGH）
        ledgerService.freeze(s.getId(), entry(0, "R-0", "NEW_PUBLISHED", RiskLevel.HIGH, t0));
        observationService.recordObservation(s.getId(), obs(0, "R-0", LabelSource.FIELD_VERIFICATION, 35,
                t0.plus(45, ChronoUnit.MINUTES), t0.plus(45, ChronoUnit.MINUTES)));
        // R-1: MEDIUM 预警，18cm 核验（命中 MEDIUM），流域不同
        ledgerService.freeze(s.getId(), entry(1, "R-1", "NEW_PUBLISHED", RiskLevel.MEDIUM, t0));
        observationService.recordObservation(s.getId(), obs(1, "R-1", LabelSource.FIELD_VERIFICATION, 18,
                t0.plus(25, ChronoUnit.MINUTES), t0.plus(25, ChronoUnit.MINUTES)));

        Map<String, Object> result = evaluationService.evaluate(
                s.getId(), EvaluationRoute.STRICT_VERIFIED, null, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> overall = (Map<String, Object>) result.get("overall");
        assertThat((int) overall.get("scored")).isEqualTo(2);
        assertThat((int) overall.get("levelHits")).isEqualTo(2);
        assertThat((double) overall.get("levelHitRate")).isEqualTo(1.0);
        // 提前量：输出到积水发生为正
        assertThat((double) overall.get("leadTimeAvgMinutes")).isEqualTo(35.0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> strata = (List<Map<String, Object>>) result.get("strata");
        // 应按流域/道路类型分层：B-1|ARTERIAL 与 B-2|LOCAL 各自一条（+观测状态维度）
        assertThat(strata.stream().map(x -> x.get("basin")).toList()).contains("B-1", "B-2");
        // 校准区间存在且合法
        @SuppressWarnings("unchecked")
        Map<String, Object> firstCal = (Map<String, Object>) strata.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cal = (List<Map<String, Object>>) firstCal.get("calibration");
        assertThat(cal).isNotEmpty();
        assertThat((double) cal.get(0).get("ciLow")).isBetween(0.0, 1.0);
        assertThat((double) cal.get(0).get("ciHigh")).isBetween(0.0, 1.0);
    }

    @Test
    void coverageEndpointReportsLabelStatesAndUnidentifiable() {
        StreamDefinition s = streamWithBasins("T-COV",
                new String[][]{{"R-0", "ARTERIAL", "B-1"}, {"R-1", "LOCAL", "B-1"}});
        Instant t0 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        ledgerService.freeze(s.getId(), entry(0, "R-0", "NEW_PUBLISHED", RiskLevel.HIGH, t0));
        ledgerService.freeze(s.getId(), entry(1, "R-1", "NEW_PUBLISHED", RiskLevel.HIGH, t0));
        observationService.recordObservation(s.getId(), obs(0, "R-0", LabelSource.FIELD_VERIFICATION, 35,
                t0.plus(20, ChronoUnit.MINUTES), t0.plus(20, ChronoUnit.MINUTES)));

        Map<String, Object> cov = evaluationService.coverageSummary(s.getId());

        assertThat(cov.get("entries")).isEqualTo(2);
        assertThat((long) cov.get("unidentified")).isEqualTo(1L);
        assertThat(cov.get("byObservabilityClass").toString()).contains("VERIFIED_DEPTH", "UNOBSERVED");
    }
}
