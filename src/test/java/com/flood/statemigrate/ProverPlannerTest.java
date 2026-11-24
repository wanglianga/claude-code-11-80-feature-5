package com.flood.statemigrate;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.service.PlannerService;
import com.flood.statemigrate.service.ProverService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** 状态语义证明器与迁移计划器（适配/回放/人工、禁止零值填充） */
class ProverPlannerTest extends BaseIntegrationTest {

    @Autowired
    private ProverService proverService;
    @Autowired
    private PlannerService plannerService;

    @Test
    void proverGivesConservationConditionsAndConvertibleFields() {
        StreamDefinition stream = scenario.stream("T-PROVE", 1, 48);

        StateProof proof = proverService.prove(stream.getId(), null);

        assertThat(proof.getVerdict()).isEqualTo(ProofVerdict.CONDITIONALLY_ADAPTABLE);
        assertThat(proof.getStatus()).isEqualTo(ProofStatus.VALID);
        assertThat(proof.getBoundRulePackageVersion()).isEqualTo("v2.0.0");
        assertThat(proof.getConservationJson()).contains("PUMP_ON_OFF_CONSERVATION")
                .contains("WINDOW_GRANULARITY_MATCH");
        assertThat(proof.getConvertibleFieldsJson()).contains("rain2hAcc", "deviceHealth");
        assertThat(proof.getMissingIntermediatesJson()).contains("零值填充");
        assertThat(proof.getFieldMappingsJson()).contains("road.rain2hAcc", "reach.inflowAcc");
    }

    @Test
    void plannerChoosesAdaptWhenConservationHolds() {
        StreamDefinition stream = scenario.stream("T-ADAPT", 1, 48);
        scenario.events(stream.getId(), 0, 10, 2.6, 2.7, true, null);
        scenario.checkpoint(stream.getId(), 0, 5, CheckpointStatus.COMPLETE, scenario.consistentSnapshot());
        proverService.prove(stream.getId(), null);

        List<MigrationPlan> plans = plannerService.generatePlans(stream.getId());

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).getRoute()).isEqualTo(PlanRoute.ADAPT);
    }

    @Test
    void plannerChoosesReplayWhenPumpEventsMissing() {
        StreamDefinition stream = scenario.stream("T-REPLAY", 1, 48);
        scenario.events(stream.getId(), 0, 10, 2.6, 2.7, true, null);
        scenario.checkpoint(stream.getId(), 0, 5, CheckpointStatus.COMPLETE,
                scenario.missingPumpEventsSnapshot());
        proverService.prove(stream.getId(), null);

        List<MigrationPlan> plans = plannerService.generatePlans(stream.getId());

        assertThat(plans.get(0).getRoute()).isEqualTo(PlanRoute.REPLAY);
        assertThat(plans.get(0).getReason()).contains("守恒");
        assertThat(plans.get(0).getReplayWindowStart()).isNotNull();
        assertThat(plans.get(0).getReplayWindowEnd()).isNotNull();
    }

    @Test
    void plannerChoosesReplayWhenCheckpointIncomplete() {
        StreamDefinition stream = scenario.stream("T-PENDING", 1, 48);
        scenario.events(stream.getId(), 0, 10, 2.6, 2.7, true, null);
        scenario.checkpoint(stream.getId(), 0, 3, CheckpointStatus.PENDING, null);
        proverService.prove(stream.getId(), null);

        List<MigrationPlan> plans = plannerService.generatePlans(stream.getId());

        assertThat(plans.get(0).getRoute()).isEqualTo(PlanRoute.REPLAY);
    }

    @Test
    void plannerChoosesManualWhenRetentionInsufficient() {
        StreamDefinition stream = scenario.stream("T-MANUAL", 1, 48);
        scenario.events(stream.getId(), 0, 10, 2.6, 2.7, true, null);
        scenario.checkpoint(stream.getId(), 0, 5, CheckpointStatus.COMPLETE,
                scenario.ancientWindowSnapshot());
        proverService.prove(stream.getId(), null);

        List<MigrationPlan> plans = plannerService.generatePlans(stream.getId());

        assertThat(plans.get(0).getRoute()).isEqualTo(PlanRoute.MANUAL);
        assertThat(plans.get(0).getReason()).contains("人工裁定");
    }

    @Test
    void adaptProducesLayeredPumpReachRoadState() {
        StreamDefinition stream = scenario.stream("T-ADAPT-OK", 1, 48);
        scenario.events(stream.getId(), 0, 10, 2.6, 2.7, true, null);
        scenario.checkpoint(stream.getId(), 0, 5, CheckpointStatus.COMPLETE, scenario.consistentSnapshot());
        proverService.prove(stream.getId(), null);
        MigrationPlan plan = plannerService.generatePlans(stream.getId()).get(0);

        MigrationPlan done = plannerService.execute(plan.getId(), null, null);

        assertThat(done.getStatus()).isEqualTo(PlanStatus.DONE);
        assertThat(done.getRoute()).isEqualTo(PlanRoute.ADAPT);
        CheckpointManifest migrated = checkpointRepo
                .findFirstByStreamIdAndPartitionNoOrderByConsumeOffsetDescIdDesc(stream.getId(), 0).orElseThrow();
        assertThat(migrated.getStateSchema()).isEqualTo(StateSchema.PUMP_REACH_ROAD_V2);
        assertThat(migrated.getStateSnapshotJson())
                .contains("\"pump\"", "\"reach\"", "\"road\"", "P-0", "RCH-0", "R-0");
        assertThat(migrated.getStateSnapshotJson()).contains("\"runtimeMin\":30.0");
    }

    @Test
    void adapterRejectsZeroFillAndFallsBackToReplay() {
        StreamDefinition stream = scenario.stream("T-FALLBACK", 1, 48);
        scenario.events(stream.getId(), 0, 10, 2.6, 2.7, true, null);
        // 计划时快照守恒满足 → ADAPT
        scenario.checkpoint(stream.getId(), 0, 5, CheckpointStatus.COMPLETE, scenario.consistentSnapshot());
        proverService.prove(stream.getId(), null);
        MigrationPlan plan = plannerService.generatePlans(stream.getId()).get(0);
        assertThat(plan.getRoute()).isEqualTo(PlanRoute.ADAPT);
        // 执行前旧作业写入新检查点：缺失启停事件（适配器不得零值填充）
        scenario.checkpoint(stream.getId(), 0, 8, CheckpointStatus.COMPLETE,
                scenario.missingPumpEventsSnapshot());

        MigrationPlan executed = plannerService.execute(plan.getId(), null, null);

        assertThat(executed.getRoute()).isEqualTo(PlanRoute.REPLAY);
        assertThat(executed.getStatus()).isEqualTo(PlanStatus.DONE);
        assertThat(executed.getReason()).contains("转为限定时间窗回放");
        // 回放生成了三层状态与新规则候选输出
        CheckpointManifest migrated = checkpointRepo
                .findFirstByStreamIdAndPartitionNoOrderByConsumeOffsetDescIdDesc(stream.getId(), 0).orElseThrow();
        assertThat(migrated.getStateSchema()).isEqualTo(StateSchema.PUMP_REACH_ROAD_V2);
        assertThat(migrated.getStateSnapshotJson()).contains("\"dataPresent\":true");
        assertThat(evidenceRepo.findByStreamIdAndPartitionNoAndKind(
                stream.getId(), 0, EvidenceKind.NEW_CANDIDATE)).isNotEmpty();
    }

    @Test
    void executeStalePlanIsRejected() {
        StreamDefinition stream = scenario.stream("T-STALE", 1, 48);
        scenario.events(stream.getId(), 0, 10, 2.6, 2.7, true, null);
        scenario.checkpoint(stream.getId(), 0, 5, CheckpointStatus.COMPLETE, scenario.consistentSnapshot());
        proverService.prove(stream.getId(), null);
        MigrationPlan plan = plannerService.generatePlans(stream.getId()).get(0);
        plan.setStatus(PlanStatus.STALE);
        planRepo.save(plan);

        assertThatThrownBy(() -> plannerService.execute(plan.getId(), null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("过期");
    }
}
