package com.flood.statemigrate.service;

import tools.jackson.databind.ObjectMapper;
import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 校准评估器：按流域、道路类型与观测状态计算等级命中、预警提前量、
 * 校准区间（Wilson 二项区间）与右删失范围。
 *
 * 两条路线：
 *  - STRICT_VERIFIED     仅独立核验水深、且已在评估时刻前到达的样本（偏差小、覆盖慢）；
 *  - CENSORED_INTERVENTION 纳入受处置影响/延迟到达的标签，带删失与干预标记，
 *                        报告不可识别部分与敏感性范围。
 *
 * 受处置影响观测不计作假阳性；迟到标签按事件时间纳入并保留当时（asOf）右删失版本；
 * 影子运行（SHADOW_SCREENING）只作筛查，不计入真实减灾收益。
 */
@Service
public class EvaluationService {

    /** 积水阈值：核验水深 ≥ 15cm 视为真实积水 */
    public static final double PONDING_THRESHOLD_CM = 15.0;
    private static final double Z = 1.96;

    private final OutcomeLedgerRepo ledgerRepo;
    private final EvaluationRunRepo runRepo;
    private final TimeAligner aligner;
    private final LabelClassifier classifier;
    private final StreamService streamService;
    private final ObjectMapper om;

    public EvaluationService(OutcomeLedgerRepo ledgerRepo, EvaluationRunRepo runRepo,
                             TimeAligner aligner, LabelClassifier classifier,
                             StreamService streamService, ObjectMapper om) {
        this.ledgerRepo = ledgerRepo;
        this.runRepo = runRepo;
        this.aligner = aligner;
        this.classifier = classifier;
        this.streamService = streamService;
        this.om = om;
    }

    private record Item(OutcomeLedgerEntry entry, LabelClassifier.Classification cls,
                        RiskLevel actualLevel, Boolean ponded, Double leadMin, boolean shadow) {
    }

    /** 结果覆盖与标签状态：按流域/道路类型/观测状态统计，并给出不可识别与右删失范围 */
    @Transactional
    public Map<String, Object> coverageSummary(Long streamId) {
        streamService.get(streamId);
        List<Item> items = classifyAll(streamId, Instant.now());
        Map<String, Map<String, Integer>> byBasin = new LinkedHashMap<>();
        Map<String, Integer> byClass = new LinkedHashMap<>();
        for (OutcomeLedgerEntry e : ledgerRepo.findByStreamIdOrderByIdAsc(streamId)) {
            ObservabilityClass oc = e.getObservability() == null ? ObservabilityClass.UNOBSERVED : e.getObservability();
            byClass.merge(oc.name(), 1, Integer::sum);
            byBasin.computeIfAbsent(e.getBasin(), k -> new LinkedHashMap<>())
                    .merge(oc.name(), 1, Integer::sum);
        }
        long unobserved = byClass.getOrDefault(ObservabilityClass.UNOBSERVED.name(), 0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("streamId", streamId);
        result.put("entries", items.size());
        result.put("byObservabilityClass", byClass);
        result.put("byBasin", byBasin);
        result.put("unidentified", unobserved);
        result.put("unidentifiableShare", items.isEmpty() ? 0
                : round4((double) unobserved / items.size()));
        List<Map<String, Object>> bias = basinCoverageBias(streamId);
        result.put("coverageBiasFlags", bias);
        result.put("coverageSelectionBias", !bias.isEmpty());
        return result;
    }

    /**
     * 对齐 + 分类并回写账目（时间对齐器 + 分类器）。返回可评估条目。
     * 评估、结果覆盖统计与上线门禁共用此分类结果，保证「受干预观测不计假阳性」一致。
     */
    @Transactional
    public List<Item> classifyAll(Long streamId, Instant asOf) {
        Instant evalAsOf = asOf != null ? asOf : Instant.now();
        List<OutcomeLedgerEntry> entries = ledgerRepo.findByStreamIdOrderByIdAsc(streamId);
        List<Item> items = new ArrayList<>();
        for (OutcomeLedgerEntry e : entries) {
            TimeAligner.Alignment a = aligner.align(e);
            LabelClassifier.Classification c = classifier.classify(
                    e, a.peakObservation(), a.firstIntervention(), evalAsOf, a.roadClosed());
            persistClassification(e, c);
            boolean shadow = e.getStage() == OutcomeStage.SHADOW_SCREENING;
            RiskLevel actual = c.labelDepthCm() != null ? levelOf(c.labelDepthCm()) : null;
            Boolean ponded = c.labelDepthCm() != null ? c.labelDepthCm() >= PONDING_THRESHOLD_CM : null;
            Double lead = null;
            if (c.labelEventTime() != null) {
                lead = Duration.between(e.getOutputTime(), c.labelEventTime()).toMillis() / 60000.0;
            }
            items.add(new Item(e, c, actual, ponded, lead, shadow));
        }
        return items;
    }

    @Transactional
    public Map<String, Object> evaluate(Long streamId, EvaluationRoute route, Instant asOf, Long replayOfRunId) {
        streamService.get(streamId);
        Instant evalAsOf = asOf != null ? asOf : Instant.now();
        List<OutcomeLedgerEntry> entries = ledgerRepo.findByStreamIdOrderByIdAsc(streamId);
        if (entries.isEmpty()) {
            throw ApiException.conflict("该流尚无结果账条目，无法评估");
        }

        // 1) 对齐 + 分类并回写账目
        List<Item> items = classifyAll(streamId, evalAsOf);

        // 2) 该路线下每个条目的可评估性与敏感性
        List<Map<String, Object>> strata = new ArrayList<>();
        Map<String, List<Item>> grouped = new LinkedHashMap<>();
        for (Item it : items) {
            String key = it.entry.getBasin() + "|" + it.entry.getRoadType() + "|" + statusOf(it, route);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(it);
        }
        for (var g : grouped.entrySet()) {
            String[] parts = g.getKey().split("\\|", -1);
            strata.add(stratum(parts[0], parts[1], parts[2], g.getValue(), route));
        }

        Map<String, Object> overall = stratum("__ALL__", "__ALL__", "MIXED", items, route);

        int unobserved = (int) items.stream().filter(i -> !i.cls.observability().isObservation()).count();
        int rightCensored = (int) items.stream()
                .filter(i -> i.cls.censored() && i.cls.observability().isObservation()).count();
        int interventionAffected = (int) items.stream()
                .filter(i -> i.cls.observability() == ObservabilityClass.INTERVENTION_AFFECTED).count();
        int shadowCount = (int) items.stream().filter(Item::shadow).count();
        int strictN = (int) items.stream().filter(i -> eligible(i, EvaluationRoute.STRICT_VERIFIED)).count();
        int censoredN = (int) items.stream().filter(i -> eligible(i, EvaluationRoute.CENSORED_INTERVENTION)).count();
        double unidentifiable = items.isEmpty() ? 0
                : (double) (unobserved + rightCensored) / items.size();

        List<Instant> censoredEnds = items.stream()
                .filter(i -> i.cls.censored() && i.entry.getObservationWindowEnd() != null)
                .map(i -> i.entry.getObservationWindowEnd()).toList();
        Map<String, Object> censoredRange = new LinkedHashMap<>();
        censoredRange.put("count", rightCensored);
        censoredRange.put("earliestWindowEnd", censoredEnds.stream().min(Comparator.naturalOrder()).orElse(null));
        censoredRange.put("latestWindowEnd", censoredEnds.stream().max(Comparator.naturalOrder()).orElse(null));

        List<String> notes = new ArrayList<>();
        notes.add("路线 " + route + "："
                + (route == EvaluationRoute.STRICT_VERIFIED
                ? "仅用独立核验水深，样本少但解释清晰。"
                : "纳入受处置影响/延迟标签，覆盖更广，但须报告不可识别部分与敏感性范围。"));
        if (shadowCount > 0) {
            notes.add("影子运行结果 " + shadowCount + " 条仅作筛查，未执行规则不得声称真实减灾收益。");
        }
        if (interventionAffected > 0) {
            notes.add("受处置影响的观测 " + interventionAffected + " 条：处置后水位未再上升/下降不反证原先风险，未计入假阳性。");
        }
        if (rightCensored > 0) {
            notes.add("右删失 " + rightCensored + " 条：标签晚于评估时刻 " + evalAsOf + " 到达，按当时不可识别处理。");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("streamId", streamId);
        result.put("route", route);
        result.put("asOf", evalAsOf);
        result.put("replayOfRunId", replayOfRunId);
        result.put("ledgerSize", entries.size());
        result.put("strictSampleSize", strictN);
        result.put("censoredSampleSize", censoredN);
        result.put("unobservableCount", unobserved);
        result.put("rightCensoredCount", rightCensored);
        result.put("interventionAffectedCount", interventionAffected);
        result.put("shadowScreeningCount", shadowCount);
        result.put("unidentifiableShare", round4(unidentifiable));
        result.put("rightCensoredRange", censoredRange);
        result.put("overall", overall);
        result.put("strata", strata);
        result.put("notes", notes);

        EvaluationRun run = new EvaluationRun();
        run.setStreamId(streamId);
        run.setRoute(route);
        run.setAsOf(evalAsOf);
        run.setReplayOfRunId(replayOfRunId);
        run.setLedgerSize(entries.size());
        run.setStrictSampleSize(strictN);
        run.setCensoredSampleSize(censoredN);
        run.setUnidentifiableShare(round4(unidentifiable));
        run.setResultJson(writeJson(result));
        runRepo.save(run);

        result.put("evaluationRunId", run.getId());
        return result;
    }

    /** 可评估性：严格路线只接受已核验且按时到达；删失路线接受任何观测（含删失，计入敏感性） */
    private boolean eligible(Item it, EvaluationRoute route) {
        if (it.shadow) return false; // 影子运行只作筛查，不进入收益评估
        if (route == EvaluationRoute.STRICT_VERIFIED) {
            return it.cls.observability() == ObservabilityClass.VERIFIED_DEPTH && !it.cls.censored();
        }
        return it.cls.observability().isObservation();
    }

    /** 不可识别：右删失、未观察、或受处置影响（处置后水位不可反证原先风险），不进入评分 */
    private boolean unidentifiable(Item it) {
        return it.cls.censored()
                || it.cls.observability() == ObservabilityClass.UNOBSERVED
                || it.cls.observability() == ObservabilityClass.INTERVENTION_AFFECTED;
    }

    private String statusOf(Item it, EvaluationRoute route) {
        if (it.shadow) return "SCREENING_ONLY";
        if (it.cls.observability() == ObservabilityClass.UNOBSERVED) return "UNOBSERVED";
        if (it.cls.observability() == ObservabilityClass.INTERVENTION_AFFECTED) return "INTERVENTION_AFFECTED";
        if (it.cls.censored()) return "RIGHT_CENSORED";
        if (route == EvaluationRoute.STRICT_VERIFIED && it.cls.observability() != ObservabilityClass.VERIFIED_DEPTH) {
            return "EXCLUDED_DELAYED";
        }
        return it.cls.observability().name();
    }

    private Map<String, Object> stratum(String basin, String roadType, String status, List<Item> all,
                                        EvaluationRoute route) {
        // 可评分样本：可观察、非删失、且非受处置影响（处置后水位不能反证原先风险）
        List<Item> eligible = all.stream()
                .filter(i -> eligible(i, route) && !unidentifiable(i)).toList();
        int hits = 0;
        int scored = 0;
        int falsePositives = 0;
        int truePositives = 0;
        int misses = 0;
        List<Double> leads = new ArrayList<>();
        Map<String, int[]> perLevel = new LinkedHashMap<>(); // level -> {n, ponded}
        for (Item it : eligible) {
            if (it.ponded() == null) continue;
            scored++;
            if (it.entry.getRiskLevel() == it.actualLevel()) hits++;
            if (it.entry.getRiskLevel() != RiskLevel.LOW && it.ponded()) truePositives++;
            if (it.entry.getRiskLevel() != RiskLevel.LOW && !it.ponded()) falsePositives++;
            if (it.entry.getRiskLevel() == RiskLevel.LOW && it.ponded()) misses++;
            if (it.ponded() && it.leadMin() != null) leads.add(it.leadMin());
            perLevel.computeIfAbsent(it.entry.getRiskLevel().name(), k -> new int[2]);
            perLevel.get(it.entry.getRiskLevel().name())[0]++;
            if (it.ponded()) perLevel.get(it.entry.getRiskLevel().name())[1]++;
        }

        List<Map<String, Object>> calibration = new ArrayList<>();
        for (var lv : perLevel.entrySet()) {
            int n = lv.getValue()[0];
            int observed = lv.getValue()[1];
            double rate = n == 0 ? 0 : (double) observed / n;
            double[] ci = wilson(observed, n);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("predictedLevel", lv.getKey());
            row.put("n", n);
            row.put("observedPondingRate", round4(rate));
            row.put("ciLow", round4(ci[0]));
            row.put("ciHigh", round4(ci[1]));
            calibration.add(row);
        }

        // 敏感性范围：把删失/受处置影响/未观察视作「未积水」→下界；视作「积水」→上界
        int unknown = (int) all.stream()
                .filter(i -> !i.shadow && (i.cls.censored()
                        || i.cls.observability() == ObservabilityClass.UNOBSERVED
                        || i.cls.observability() == ObservabilityClass.INTERVENTION_AFFECTED))
                .count();
        int denom = scored + unknown;
        double lower = denom == 0 ? 0 : (double) hits / denom;
        double upper = denom == 0 ? 0 : (double) (hits + unknown) / denom;

        List<Instant> censoredEnds = all.stream()
                .filter(i -> i.cls.censored() && i.entry.getObservationWindowEnd() != null)
                .map(i -> i.entry.getObservationWindowEnd()).toList();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("basin", basin);
        row.put("roadType", roadType);
        row.put("observationStatus", status);
        row.put("entries", all.size());
        row.put("scored", scored);
        row.put("levelHits", hits);
        row.put("levelHitRate", scored == 0 ? null : round4((double) hits / scored));
        row.put("truePositives", truePositives);
        row.put("falsePositives", falsePositives);
        row.put("misses", misses);
        row.put("leadTimeAvgMinutes", leads.isEmpty() ? null : round2(avg(leads)));
        row.put("leadTimeMinMinutes", leads.isEmpty() ? null : round2(leads.stream().min(Double::compare).orElse(0.0)));
        row.put("leadTimeMaxMinutes", leads.isEmpty() ? null : round2(leads.stream().max(Double::compare).orElse(0.0)));
        row.put("calibration", calibration);
        row.put("sensitivityHitLower", round4(lower));
        row.put("sensitivityHitUpper", round4(upper));
        Map<String, Object> cr = new LinkedHashMap<>();
        cr.put("unknownCount", unknown);
        cr.put("earliestWindowEnd", censoredEnds.stream().min(Comparator.naturalOrder()).orElse(null));
        cr.put("latestWindowEnd", censoredEnds.stream().max(Comparator.naturalOrder()).orElse(null));
        row.put("rightCensoredRange", cr);
        return row;
    }

    /** 流域覆盖选择偏差：某流域只有低风险道路得到核验，却存在更高风险未核验道路 */
    public List<Map<String, Object>> basinCoverageBias(Long streamId) {
        List<OutcomeLedgerEntry> entries = ledgerRepo.findByStreamIdOrderByIdAsc(streamId);
        Map<String, List<OutcomeLedgerEntry>> byBasin = new LinkedHashMap<>();
        for (OutcomeLedgerEntry e : entries) {
            byBasin.computeIfAbsent(e.getBasin(), k -> new ArrayList<>()).add(e);
        }
        List<Map<String, Object>> flags = new ArrayList<>();
        for (var b : byBasin.entrySet()) {
            boolean anyVerified = b.getValue().stream()
                    .anyMatch(e -> e.getObservability() == ObservabilityClass.VERIFIED_DEPTH);
            boolean anyHigher = b.getValue().stream()
                    .anyMatch(e -> e.getRiskLevel() != RiskLevel.LOW);
            boolean verifiedAllLow = b.getValue().stream()
                    .filter(e -> e.getObservability() == ObservabilityClass.VERIFIED_DEPTH)
                    .allMatch(e -> e.getRiskLevel() == RiskLevel.LOW);
            if (anyVerified && anyHigher && verifiedAllLow) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("basin", b.getKey());
                f.put("coverageSelectionBias", true);
                f.put("reason", "流域 " + b.getKey() + " 仅低风险道路得到核验，存在更高风险道路未核验，"
                        + "禁止据此得出全流域校准结论");
                f.put("verifiedRoads", b.getValue().stream()
                        .filter(e -> e.getObservability() == ObservabilityClass.VERIFIED_DEPTH)
                        .map(OutcomeLedgerEntry::getRoadId).distinct().toList());
                flags.add(f);
            }
        }
        return flags;
    }

    public static RiskLevel levelOf(double depthCm) {
        if (depthCm >= 30) return RiskLevel.HIGH;
        if (depthCm >= 15) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    /** Wilson 二项置信区间 */
    static double[] wilson(int k, int n) {
        if (n == 0) return new double[]{0, 1};
        double p = (double) k / n;
        double z2 = Z * Z;
        double denom = 1 + z2 / n;
        double center = (p + z2 / (2.0 * n)) / denom;
        double half = (Z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n))) / denom;
        return new double[]{Math.max(0, center - half), Math.min(1, center + half)};
    }

    private void persistClassification(OutcomeLedgerEntry e, LabelClassifier.Classification c) {
        e.setObservability(c.observability());
        e.setClassificationReason(c.reason());
        e.setLabelDepthCm(c.labelDepthCm());
        e.setLabelSource(c.labelSource());
        e.setLabelEventTime(c.labelEventTime());
        e.setLabelObservedAt(c.labelObservedAt());
        e.setCensored(c.censored());
        e.setCensoringReason(c.censoringReason());
        e.setPostIntervention(c.postIntervention());
        ledgerRepo.save(e);
    }

    public List<EvaluationRun> runs(Long streamId) {
        return runRepo.findByStreamIdOrderByIdDesc(streamId);
    }

    public EvaluationRun getRun(Long runId) {
        return runRepo.findById(runId).orElseThrow(() -> ApiException.notFound("评估运行不存在: " + runId));
    }

    private double avg(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
    static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private String writeJson(Object value) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
