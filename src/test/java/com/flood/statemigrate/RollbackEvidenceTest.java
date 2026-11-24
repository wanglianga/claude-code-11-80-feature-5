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

/** 回退控制与证据账（撤回范围、只读快照、回放越过切换水印） */
class RollbackEvidenceTest extends BaseIntegrationTest {

    @Autowired
    private ProverService proverService;
    @Autowired
    private PlannerService plannerService;
    @Autowired
    private DualTrackService dualTrackService;
    @Autowired
    private GateService gateService;
    @Autowired
    private RollbackService rollbackService;

    private StreamDefinition releasedStream(String code) {
        StreamDefinition stream = scenario.stream(code, 1, 48);
        scenario.events(stream.getId(), 0, 10, 2.6, 2.7, true, null);
        scenario.checkpoint(stream.getId(), 0, 5, CheckpointStatus.COMPLETE, scenario.consistentSnapshot());
        proverService.prove(stream.getId(), null);
        for (MigrationPlan plan : plannerService.generatePlans(stream.getId())) {
            plannerService.execute(plan.getId(), null, null);
        }
        dualTrackService.start(stream.getId(), null, null);
        gateService.release(stream.getId());
        return streamRepo.findById(stream.getId()).orElseThrow();
    }

    @Test
    void rollbackRetractsOutputsAndKeepsReadOnlySnapshot() {
        StreamDefinition stream = releasedStream("T-ROLLBACK");
        assertThat(evidenceRepo.findByStreamIdAndKind(stream.getId(), EvidenceKind.NEW_OUTPUT)).hasSize(1);

        RollbackRecord record = rollbackService.rollback(stream.getId(), 0, 1000, null, "新规则误报回退");

        assertThat(record.getRetractedCount()).isEqualTo(1);
        // 新输出已撤回
        assertThat(evidenceRepo.findByStreamIdAndKind(stream.getId(), EvidenceKind.NEW_OUTPUT)).isEmpty();
        assertThat(evidenceRepo.findByStreamIdAndKind(stream.getId(), EvidenceKind.RETRACTED)).hasSize(1);
        // 旧规则快照只读
        CheckpointManifest oldCp = checkpointRepo
                .findByStreamIdAndPartitionNoOrderByConsumeOffsetDesc(stream.getId(), 0).stream()
                .filter(c -> c.getStateSchema() == StateSchema.GRID_WINDOW_V1)
                .findFirst().orElseThrow();
        assertThat(oldCp.isReadOnlySnapshot()).isTrue();
        assertThat(record.getReadOnlySnapshotIdsJson()).contains(oldCp.getId().toString());
        // 闸门回到 HOLD，流恢复旧规则
        assertThat(gateService.get(stream.getId()).getStatus()).isEqualTo(GateStatus.HOLD);
        StreamDefinition after = streamRepo.findById(stream.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(StreamStatus.ROLLED_BACK);
        assertThat(after.getStateSchema()).isEqualTo(StateSchema.GRID_WINDOW_V1);
        assertThat(after.getRulePackageVersion()).isEqualTo("v1.0.0");
    }

    @Test
    void replayCrossingSwitchWatermarkDistinguishesEvidenceKinds() {
        // 无检查点分区 → 限定窗口回放路线
        StreamDefinition stream = scenario.stream("T-CROSS", 1, 48);
        scenario.events(stream.getId(), 0, 10, 2.6, 2.7, true, null);
        proverService.prove(stream.getId(), null);
        MigrationPlan plan = plannerService.generatePlans(stream.getId()).get(0);
        assertThat(plan.getRoute()).isEqualTo(PlanRoute.REPLAY);
        plannerService.execute(plan.getId(), null, null);
        dualTrackService.start(stream.getId(), null, null);
        gateService.release(stream.getId());
        // 回放候选 + 双轨候选均被发布
        assertThat(evidenceRepo.findByStreamIdAndKind(stream.getId(), EvidenceKind.NEW_OUTPUT)).hasSize(2);
        long switchOffset = scenario.maxOffset(stream.getId(), 0);

        // 切换后又有新事件到达
        scenario.events(stream.getId(), 0, 12, 2.7, 2.8, true, null);
        assertThat(scenario.maxOffset(stream.getId(), 0)).isGreaterThan(switchOffset);

        // 复核回放越过切换水印
        Instant start = Instant.now().minus(125, ChronoUnit.MINUTES);
        Instant end = Instant.now();
        MigrationPlan replayed = plannerService.execute(plan.getId(), start, end);

        assertThat(replayed.getResultJson()).contains("\"crossesSwitchWatermark\":true");
        // 已发布的新输出被撤回，复核结果仅作新候选
        assertThat(evidenceRepo.findByStreamIdAndKind(stream.getId(), EvidenceKind.NEW_OUTPUT)).isEmpty();
        assertThat(evidenceRepo.findByStreamIdAndKind(stream.getId(), EvidenceKind.RETRACTED)).isNotEmpty();
        List<EvidenceEntry> candidates = evidenceRepo.findByStreamIdAndPartitionNoAndKind(
                stream.getId(), 0, EvidenceKind.NEW_CANDIDATE);
        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(candidates.size() - 1).getNote()).contains("越过切换水印");
    }
}
