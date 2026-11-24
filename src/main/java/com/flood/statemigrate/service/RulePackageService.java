package com.flood.statemigrate.service;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则包登记与二次修订。
 * 双轨期间若新一侧规则（MULTI_FACTOR）二次修订：相关双轨比较与适配证明过期，
 * 引用旧证明的迁移计划置为 STALE，目标版本重新绑定到新规则包。
 */
@Service
public class RulePackageService {

    private final RulePackageRepo rulePackageRepo;
    private final StreamDefinitionRepo streamRepo;
    private final StateProofRepo proofRepo;
    private final DualTrackRunRepo runRepo;
    private final MigrationPlanRepo planRepo;

    public RulePackageService(RulePackageRepo rulePackageRepo, StreamDefinitionRepo streamRepo,
                              StateProofRepo proofRepo, DualTrackRunRepo runRepo, MigrationPlanRepo planRepo) {
        this.rulePackageRepo = rulePackageRepo;
        this.streamRepo = streamRepo;
        this.proofRepo = proofRepo;
        this.runRepo = runRepo;
        this.planRepo = planRepo;
    }

    @Transactional
    public Map<String, Object> register(Long streamId, RulePackage pkg) {
        StreamDefinition stream = streamRepo.findById(streamId)
                .orElseThrow(() -> ApiException.notFound("流定义不存在: " + streamId));
        rulePackageRepo.findByStreamIdAndVersion(streamId, pkg.getVersion()).ifPresent(p -> {
            throw ApiException.conflict("规则包版本已存在: " + pkg.getVersion());
        });
        pkg.setStreamId(streamId);
        pkg.setStatus("ACTIVE");
        rulePackageRepo.save(pkg);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rulePackage", pkg);

        if (pkg.getKind() == RuleKind.MULTI_FACTOR) {
            String previousTarget = stream.getTargetRulePackageVersion();
            // 取代同侧旧版本
            List<RulePackage> all = rulePackageRepo.findByStreamIdOrderByCreatedAtAsc(streamId);
            for (RulePackage p : all) {
                if (p.getKind() == RuleKind.MULTI_FACTOR && !p.getVersion().equals(pkg.getVersion())
                        && "ACTIVE".equals(p.getStatus())) {
                    p.setStatus("SUPERSEDED");
                    rulePackageRepo.save(p);
                }
            }
            int expiredProofs = 0;
            int expiredRuns = 0;
            int stalePlans = 0;
            if (previousTarget != null && !previousTarget.equals(pkg.getVersion())) {
                // 双轨期间规则二次修订：比较与适配证明过期，重新绑定规则包
                for (StateProof proof : proofRepo.findByStreamIdAndBoundRulePackageVersionAndStatus(
                        streamId, previousTarget, ProofStatus.VALID)) {
                    proof.setStatus(ProofStatus.EXPIRED);
                    proofRepo.save(proof);
                    expiredProofs++;
                }
                for (DualTrackRun run : runRepo.findByStreamIdAndNewRuleVersionAndStatus(
                        streamId, previousTarget, DualTrackStatus.DONE)) {
                    run.setStatus(DualTrackStatus.EXPIRED);
                    runRepo.save(run);
                    expiredRuns++;
                }
                for (DualTrackRun run : runRepo.findByStreamIdAndNewRuleVersionAndStatus(
                        streamId, previousTarget, DualTrackStatus.RUNNING)) {
                    run.setStatus(DualTrackStatus.EXPIRED);
                    runRepo.save(run);
                    expiredRuns++;
                }
                List<Long> validProofIds = proofRepo
                        .findByStreamIdAndBoundRulePackageVersionAndStatus(streamId, pkg.getVersion(), ProofStatus.VALID)
                        .stream().map(StateProof::getId).toList();
                for (MigrationPlan plan : planRepo.findByStreamIdOrderByPartitionNoAsc(streamId)) {
                    if (plan.getProofId() != null && !validProofIds.contains(plan.getProofId())
                            && plan.getStatus() != PlanStatus.STALE && plan.getStatus() != PlanStatus.DONE) {
                        plan.setStatus(PlanStatus.STALE);
                        plan.setReason(plan.getReason() + "；绑定证明随规则包 " + previousTarget + " 过期，需重新编排");
                        planRepo.save(plan);
                        stalePlans++;
                    }
                }
            }
            stream.setTargetRulePackageVersion(pkg.getVersion());
            streamRepo.save(stream);
            result.put("previousTargetVersion", previousTarget);
            result.put("reboundTargetVersion", pkg.getVersion());
            result.put("expiredProofs", expiredProofs);
            result.put("expiredDualTrackRuns", expiredRuns);
            result.put("stalePlans", stalePlans);
        }
        return result;
    }

    public List<RulePackage> list(Long streamId) {
        return rulePackageRepo.findByStreamIdOrderByCreatedAtAsc(streamId);
    }

    public RulePackage get(Long streamId, String version) {
        return rulePackageRepo.findByStreamIdAndVersion(streamId, version)
                .orElseThrow(() -> ApiException.notFound("规则包不存在: " + version));
    }
}
