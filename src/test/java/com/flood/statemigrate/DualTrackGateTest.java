package com.flood.statemigrate;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.service.DualTrackService;
import com.flood.statemigrate.service.GateService;
import com.flood.statemigrate.service.PlannerService;
import com.flood.statemigrate.service.ProverService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** 双轨校验与切换闸门（待验证标记、证据与覆盖阈值） */
class DualTrackGateTest extends BaseIntegrationTest {

    @Autowired
    private ProverService proverService;
    @Autowired
    private PlannerService plannerService;
    @Autowired
    private DualTrackService dualTrackService;
    @Autowired
    private GateService gateService;

    @Test
    void dualTrackQuantifiesRiskDiff() {
        StreamDefinition stream = scenario.stream("T-DUAL", 1, 48);
        // 雨量小（旧规则 LOW）但趋势/积水上报高（新规则 HIGH）
        scenario.events(stream.getId(), 0, 2.0, 2.6, 3.4, false, 45.0);

        List<DualTrackRun> runs = dualTrackService.start(stream.getId(), null, null);

        assertThat(runs).hasSize(1);
        DualTrackRun run = runs.get(0);
        assertThat(run.getStatus()).isEqualTo(DualTrackStatus.DONE);
        assertThat(run.getOldRuleVersion()).isEqualTo("v1.0.0");
        assertThat(run.getNewRuleVersion()).isEqualTo("v2.0.0");
        List<DualTrackDiff> diffs = dualTrackService.diffs(run.getId());
        assertThat(diffs).hasSize(1);
        DualTrackDiff diff = diffs.get(0);
        assertThat(diff.getOldRisk()).isEqualTo(RiskLevel.LOW);
        assertThat(diff.getNewRisk()).isEqualTo(RiskLevel.HIGH);
        assertThat(diff.isEscalation()).isTrue();
        assertThat(diff.getDelta()).isGreaterThan(0);
        // 新规则候选输出已入证据账
        assertThat(evidenceRepo.findByStreamIdAndPartitionNoAndKind(
                stream.getId(), 0, EvidenceKind.NEW_CANDIDATE)).isNotEmpty();
    }

    @Test
    void gateHoldsAndMarksPendingWhenEscalationButOldCheckpointIncomplete() {
        StreamDefinition stream = scenario.stream("T-HOLD", 1, 48);
        scenario.events(stream.getId(), 0, 2.0, 2.6, 3.4, false, 45.0);
        // 旧作业尚未完成该分区检查点
        scenario.checkpoint(stream.getId(), 0, 3, CheckpointStatus.PENDING, null);
        proverService.prove(stream.getId(), null);
        for (MigrationPlan plan : plannerService.generatePlans(stream.getId())) {
            plannerService.execute(plan.getId(), null, null);
        }
        dualTrackService.start(stream.getId(), null, null);

        Map<String, Object> eval = gateService.evaluate(stream.getId());

        assertThat(eval.get("canRelease")).isEqualTo(false);
        assertThat((List<Integer>) eval.get("pendingVerification")).containsExactly(0);
        assertThat(eval.get("holdReasons").toString()).contains("维持旧输出");
        assertThat(gateService.get(stream.getId()).getStatus()).isEqualTo(GateStatus.HOLD);
        // 闸门未发布：证据账中无 NEW_OUTPUT
        assertThat(evidenceRepo.findByStreamIdAndKind(stream.getId(), EvidenceKind.NEW_OUTPUT)).isEmpty();
        // 直接发布也应被拒绝
        assertThatThrownBy(() -> gateService.release(stream.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("维持旧输出");
    }

    @Test
    @SuppressWarnings("unchecked")
    void gateReleasesWhenEvidenceAndCoverageMet() {
        StreamDefinition stream = scenario.stream("T-RELEASE", 2, 48);
        for (int p = 0; p < 2; p++) {
            scenario.events(stream.getId(), p, 10, 2.6, 2.7, true, null);
            scenario.checkpoint(stream.getId(), p, 5, CheckpointStatus.COMPLETE,
                    scenario.consistentSnapshot());
        }
        proverService.prove(stream.getId(), null);
        for (MigrationPlan plan : plannerService.generatePlans(stream.getId())) {
            plannerService.execute(plan.getId(), null, null);
        }
        dualTrackService.start(stream.getId(), null, null);

        Map<String, Object> eval = gateService.evaluate(stream.getId());
        assertThat(eval.get("canRelease")).isEqualTo(true);
        assertThat(eval.get("coverage")).isEqualTo(1.0);

        Map<String, Object> result = gateService.release(stream.getId());

        assertThat(result.get("released")).isEqualTo(true);
        assertThat((int) result.get("publishedOutputs")).isEqualTo(2);
        assertThat(gateService.get(stream.getId()).getStatus()).isEqualTo(GateStatus.RELEASED);
        assertThat(evidenceRepo.findByStreamIdAndKind(stream.getId(), EvidenceKind.NEW_OUTPUT)).hasSize(2);
        StreamDefinition switched = streamRepo.findById(stream.getId()).orElseThrow();
        assertThat(switched.getStatus()).isEqualTo(StreamStatus.SWITCHED);
        assertThat(switched.getStateSchema()).isEqualTo(StateSchema.PUMP_REACH_ROAD_V2);
        assertThat(switched.getRulePackageVersion()).isEqualTo("v2.0.0");
    }
}
