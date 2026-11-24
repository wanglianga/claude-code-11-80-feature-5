package com.flood.statemigrate;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.service.DualTrackService;
import com.flood.statemigrate.service.GateService;
import com.flood.statemigrate.service.PlannerService;
import com.flood.statemigrate.service.ProverService;
import com.flood.statemigrate.service.RulePackageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** 双轨期间规则二次修订：比较与适配证明过期并重新绑定规则包 */
class RuleRevisionTest extends BaseIntegrationTest {

    @Autowired
    private ProverService proverService;
    @Autowired
    private PlannerService plannerService;
    @Autowired
    private DualTrackService dualTrackService;
    @Autowired
    private RulePackageService rulePackageService;
    @Autowired
    private GateService gateService;

    @Test
    void revisionExpiresProofsRunsAndStalePlansThenRebinds() {
        StreamDefinition stream = scenario.stream("T-REVISE", 1, 48);
        scenario.events(stream.getId(), 0, 10, 2.6, 2.7, true, null);
        scenario.checkpoint(stream.getId(), 0, 5, CheckpointStatus.COMPLETE, scenario.consistentSnapshot());
        proverService.prove(stream.getId(), null);
        plannerService.generatePlans(stream.getId()); // 不执行，保持 PLANNED
        dualTrackService.start(stream.getId(), null, null);

        // 双轨期间可信度规则二次修订 v2.1.0
        RulePackage revision = new RulePackage();
        revision.setVersion("v2.1.0");
        revision.setKind(RuleKind.MULTI_FACTOR);
        revision.setConfigJson("{\"medium\":25,\"high\":55,\"wRain\":0.6,\"wTrend\":1.0,"
                + "\"wLag\":0.15,\"wDepth\":0.2,\"credibilityFloor\":0.6}");
        Map<String, Object> result = rulePackageService.register(stream.getId(), revision);

        assertThat(result.get("expiredProofs")).isEqualTo(1);
        assertThat(result.get("expiredDualTrackRuns")).isEqualTo(1);
        assertThat(result.get("stalePlans")).isEqualTo(1);
        assertThat(result.get("reboundTargetVersion")).isEqualTo("v2.1.0");

        // 证明、比较、计划状态核验
        assertThat(proofRepo.findByStreamIdOrderByCreatedAtDesc(stream.getId()))
                .allMatch(p -> p.getStatus() == ProofStatus.EXPIRED);
        assertThat(runRepo.findByStreamIdOrderByIdAsc(stream.getId()))
                .allMatch(r -> r.getStatus() == DualTrackStatus.EXPIRED);
        assertThat(planRepo.findByStreamIdOrderByPartitionNoAsc(stream.getId()))
                .allMatch(p -> p.getStatus() == PlanStatus.STALE);
        assertThat(streamRepo.findById(stream.getId()).orElseThrow().getTargetRulePackageVersion())
                .isEqualTo("v2.1.0");
        // v2.0.0 被取代
        assertThat(rulePackageRepo.findByStreamIdAndVersion(stream.getId(), "v2.0.0").orElseThrow().getStatus())
                .isEqualTo("SUPERSEDED");

        // 过期状态下闸门不可发布
        Map<String, Object> eval = gateService.evaluate(stream.getId());
        assertThat(eval.get("canRelease")).isEqualTo(false);
        assertThat(eval.get("proofValid")).isEqualTo(false);

        // 重新绑定：新证明 + 新计划 + 新双轨后可恢复
        proverService.prove(stream.getId(), null);
        assertThat(proverService.currentValidProof(
                streamRepo.findById(stream.getId()).orElseThrow()).getBoundRulePackageVersion())
                .isEqualTo("v2.1.0");
    }
}
