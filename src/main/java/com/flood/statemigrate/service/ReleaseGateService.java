package com.flood.statemigrate.service;

import tools.jackson.databind.ObjectMapper;
import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.repo.EvaluationRunRepo;
import com.flood.statemigrate.repo.OutcomeLedgerRepo;
import com.flood.statemigrate.repo.ReleaseGateRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * 上线门禁：只允许把通过最低覆盖与安全下限的规则扩大到更多道路。
 *  - 最低覆盖：已核验样本覆盖的道路比例（strictVerifiedCoverage）；
 *  - 安全下限：已评分样本中「预警未升级但真实积水」的比例上限（漏报率 ≤ safetyFloor）；
 *  - 覆盖选择偏差：某流域仅低风险道路被核验时，阻止全流域校准结论。
 * 影子运行结果不参与放行。返回允许扩大（allowedRoadIds）与被阻止（blockedRoadIds）的道路。
 */
@Service
public class ReleaseGateService {

    private final ReleaseGateRepo gateRepo;
    private final EvaluationRunRepo runRepo;
    private final OutcomeLedgerRepo ledgerRepo;
    private final EvaluationService evaluationService;
    private final StreamService streamService;
    private final ObjectMapper om;

    public ReleaseGateService(ReleaseGateRepo gateRepo, EvaluationRunRepo runRepo,
                              OutcomeLedgerRepo ledgerRepo, EvaluationService evaluationService,
                              StreamService streamService, ObjectMapper om) {
        this.gateRepo = gateRepo;
        this.runRepo = runRepo;
        this.ledgerRepo = ledgerRepo;
        this.evaluationService = evaluationService;
        this.streamService = streamService;
        this.om = om;
    }

    public record GatePolicy(double minCoverage, int minStrictSamples, double safetyFloor) {
        public static final GatePolicy DEFAULT = new GatePolicy(0.5, 1, 0.2);
    }

    @Transactional
    public Map<String, Object> evaluate(Long streamId, GatePolicy policy) {
        StreamDefinition stream = streamService.get(streamId);
        GatePolicy p = policy != null ? policy : GatePolicy.DEFAULT;
        // 先经过时间对齐 + 分类回写，确保标签状态最新
        evaluationService.classifyAll(streamId, Instant.now());
        List<OutcomeLedgerEntry> entries = ledgerRepo.findByStreamIdOrderByIdAsc(streamId);

        List<String> allRoads = streamService.partitions(stream).stream()
                .map(PartitionConfig::roadId).distinct().toList();

        // 严格核验样本（排除影子、未观察、受处置影响、右删失）
        List<OutcomeLedgerEntry> verified = entries.stream()
                .filter(e -> e.getStage() != OutcomeStage.SHADOW_SCREENING)
                .filter(e -> e.getObservability() == ObservabilityClass.VERIFIED_DEPTH)
                .filter(e -> !e.isCensored())
                .toList();
        Set<String> verifiedRoads = new LinkedHashSet<>();
        verified.forEach(e -> verifiedRoads.add(e.getRoadId()));
        double coverage = allRoads.isEmpty() ? 0 : (double) verifiedRoads.size() / allRoads.size();

        // 安全下限：仅对可识别样本评分；受处置影响/右删失/未观察不计漏报（不可识别）
        int scoredWithDepth = 0;
        int dangerousMisses = 0;
        for (OutcomeLedgerEntry e : verified) {
            if (e.getLabelDepthCm() == null) continue;
            scoredWithDepth++;
            if (e.getRiskLevel() == RiskLevel.LOW
                    && e.getLabelDepthCm() >= EvaluationService.PONDING_THRESHOLD_CM) {
                dangerousMisses++;
            }
        }
        // 安全指标：可按安全下限放行的比例 = 1 - 漏报率
        double missRate = scoredWithDepth == 0 ? 1.0 : (double) dangerousMisses / scoredWithDepth;
        double measuredSafety = 1.0 - missRate;

        // 覆盖选择偏差：按流域检查
        List<Map<String, Object>> biasFlags = evaluationService.basinCoverageBias(streamId);
        boolean coverageBias = !biasFlags.isEmpty();

        List<String> reasons = new ArrayList<>();
        if (coverage < p.minCoverage()) {
            reasons.add("严格核验覆盖不足: " + verifiedRoads.size() + "/" + allRoads.size()
                    + " = " + EvaluationService.round4(coverage) + " < 最低覆盖 " + p.minCoverage());
        }
        if (verified.size() < p.minStrictSamples()) {
            reasons.add("严格核验样本不足: " + verified.size() + " < 最低样本 " + p.minStrictSamples());
        }
        if (measuredSafety < 1.0 - p.safetyFloor()) {
            reasons.add("安全下限未满足: 漏报率 " + EvaluationService.round4(missRate)
                    + " > 允许 " + p.safetyFloor() + "（核验" + scoredWithDepth + "条，漏报" + dangerousMisses + "条）");
        }
        if (coverageBias) {
            reasons.add("覆盖选择偏差：" + biasFlags.size()
                    + " 个流域仅低风险道路得到核验，阻止全流域校准结论");
        }

        boolean allow = reasons.isEmpty();

        // 允许扩大范围：有严格核验样本覆盖的流域内道路才可扩大；
        // 覆盖选择偏差流域中被阻止（仅低风险核验的结论不外推）。
        Set<String> biasedBasins = new HashSet<>();
        biasFlags.forEach(f -> biasedBasins.add(String.valueOf(f.get("basin"))));
        Set<String> verifiedBasins = new HashSet<>();
        verified.forEach(e -> verifiedBasins.add(e.getBasin()));

        List<String> allowed = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        for (PartitionConfig pc : streamService.partitions(stream)) {
            boolean roadAllowed = allow
                    && verifiedBasins.contains(pc.basin())
                    && !biasedBasins.contains(pc.basin());
            if (roadAllowed) allowed.add(pc.roadId());
            else blocked.add(pc.roadId());
        }

        ReleaseGate gate = new ReleaseGate();
        gate.setStreamId(streamId);
        gate.setDecision(allow ? GateDecision.ALLOW : GateDecision.BLOCK);
        // 门禁必须绑定一次评估运行：无则先建立严格评估作为依据
        Long latestRunId = runRepo.findFirstByStreamIdOrderByIdDesc(streamId)
                .map(EvaluationRun::getId).orElse(null);
        if (latestRunId == null && !entries.isEmpty()) {
            Map<String, Object> baseline = evaluationService.evaluate(
                    streamId, EvaluationRoute.STRICT_VERIFIED, Instant.now(), null);
            latestRunId = (Long) baseline.get("evaluationRunId");
        }
        gate.setEvaluationRunId(latestRunId);
        gate.setMinCoverage(p.minCoverage());
        gate.setMinStrictSamples(p.minStrictSamples());
        gate.setSafetyFloor(p.safetyFloor());
        gate.setMeasuredCoverage(EvaluationService.round4(coverage));
        gate.setStrictSamples(verified.size());
        gate.setMeasuredSafety(EvaluationService.round4(measuredSafety));
        gate.setCoverageBias(coverageBias);
        gate.setAllowedRoadIdsJson(writeJson(allowed));
        gate.setBlockedRoadIdsJson(writeJson(blocked));
        gate.setReasonsJson(writeJson(reasons));
        gateRepo.save(gate);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("streamId", streamId);
        result.put("decision", gate.getDecision());
        result.put("evaluationRunId", latestRunId);
        result.put("policy", Map.of("minCoverage", p.minCoverage(),
                "minStrictSamples", p.minStrictSamples(), "safetyFloor", p.safetyFloor()));
        result.put("measuredCoverage", gate.getMeasuredCoverage());
        result.put("strictSamples", gate.getStrictSamples());
        result.put("measuredSafety", gate.getMeasuredSafety());
        result.put("missRate", EvaluationService.round4(missRate));
        result.put("coverageBias", coverageBias);
        result.put("coverageBiasFlags", biasFlags);
        result.put("allowedRoadIds", allowed);
        result.put("blockedRoadIds", blocked);
        result.put("reasons", reasons);
        result.put("shadowScreeningExcluded", entries.stream()
                .filter(e -> e.getStage() == OutcomeStage.SHADOW_SCREENING).count());
        return result;
    }

    public List<ReleaseGate> history(Long streamId) {
        return gateRepo.findByStreamIdOrderByIdDesc(streamId);
    }

    public ReleaseGate latest(Long streamId) {
        return gateRepo.findFirstByStreamIdOrderByIdDesc(streamId)
                .orElseThrow(() -> com.flood.statemigrate.error.ApiException.notFound("该流尚无门禁评估记录"));
    }

    private String writeJson(Object value) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
