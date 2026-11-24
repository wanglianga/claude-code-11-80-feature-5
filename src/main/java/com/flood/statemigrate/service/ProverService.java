package com.flood.statemigrate.service;

import tools.jackson.databind.ObjectMapper;
import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.StateProofRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 状态语义证明器：比较旧（网格窗口）新（泵站—河段—道路）状态模式的字段、
 * 聚合粒度与依赖关系，给出旧状态确实可转换的守恒条件。
 * 证明绑定目标规则包版本；规则二次修订后证明过期，需重新运行。
 */
@Service
public class ProverService {

    private final StateProofRepo proofRepo;
    private final StreamService streamService;
    private final RulePackageService rulePackageService;
    private final ObjectMapper om;

    public ProverService(StateProofRepo proofRepo, StreamService streamService,
                         RulePackageService rulePackageService, ObjectMapper om) {
        this.proofRepo = proofRepo;
        this.streamService = streamService;
        this.rulePackageService = rulePackageService;
        this.om = om;
    }

    @Transactional
    public StateProof prove(Long streamId, String targetRulePackageVersion) {
        StreamDefinition stream = streamService.get(streamId);
        String target = targetRulePackageVersion != null ? targetRulePackageVersion
                : stream.getTargetRulePackageVersion();
        if (target == null) {
            throw ApiException.badRequest("流未设置升级目标规则包版本");
        }
        RulePackage targetPkg = rulePackageService.get(streamId, target);
        if (targetPkg.getKind() != RuleKind.MULTI_FACTOR) {
            throw ApiException.badRequest("目标规则包须为多因子规则（MULTI_FACTOR）: " + target);
        }

        // 字段映射：旧网格窗口 → 新三层状态
        List<Map<String, Object>> mappings = new ArrayList<>();
        mappings.add(mapping("rain2hAcc", "road.rain2hAcc", "IDENTITY", true, true, true,
                "两小时降雨累计，窗口粒度一致时恒等映射"));
        mappings.add(mapping("upstreamInflowAcc", "reach.inflowAcc", "AGGREGATE_BY_REACH", false, true, true,
                "按 partitionConfig 的网格→河段映射重聚合，单向不可逆但可转换"));
        mappings.add(mapping("deviceHealth", "road.credibilityScore", "CLIP01", true, true, true,
                "设备健康度截断到[0,1]作为传感器可信度"));
        mappings.add(mapping("pumpRuntimeMin", "pump.runtimeMin", "REQUIRES_EVENTS", true, true, true,
                "须满足 PUMP_ON_OFF_CONSERVATION：由启停事件推导的运行时长与累计量一致"));
        mappings.add(mapping("riskLevel", "road.riskLevel", "RECOMPUTE", false, false, false,
                "风险等级须在新规则包下重算，不可直接转换"));

        // 守恒条件：旧状态确实可转换须满足的条件
        List<Map<String, Object>> conservation = new ArrayList<>();
        conservation.add(Map.of(
                "name", "PUMP_ON_OFF_CONSERVATION",
                "expression", "sum(runtime(pumpOnOffEvents) ∩ window) == pumpRuntimeMin",
                "tolerance", "PT1M",
                "note", "缺失启停事件时禁止以零值填充缺失启停区间，适配器必须拒绝该快照"));
        conservation.add(Map.of(
                "name", "WINDOW_GRANULARITY_MATCH",
                "expression", "windowEnd - windowStart ≈ 2h",
                "tolerance", "PT5M",
                "note", "旧窗口聚合粒度须与新规则两小时累计一致"));

        // 依赖关系
        List<Map<String, Object>> dependencies = new ArrayList<>();
        dependencies.add(Map.of("newField", "reach.inflowAcc",
                "requires", List.of("upstreamInflowAcc", "partitionConfig.gridToReachMapping")));
        dependencies.add(Map.of("newField", "pump.lagMin",
                "requires", List.of("pumpOnOffEvents", "highWaterSince 或水位事件流")));
        dependencies.add(Map.of("newField", "road.riskLevel",
                "requires", List.of("rulePackage:" + target, "重算（不可继承旧结论）")));

        List<String> convertible = List.of("rain2hAcc", "upstreamInflowAcc", "deviceHealth", "pumpRuntimeMin");
        List<String> missingIntermediates = List.of(
                "pump.onOffEvents：旧网格快照可能不携带泵站启停事件，缺失时适配器必须拒绝而非零值填充",
                "pump.lagMin：需高水位起始时刻，旧快照无该字段时须由回放推导");

        // 同流旧的 VALID 证明被本次运行取代
        for (StateProof old : proofRepo.findByStreamIdAndStatus(streamId, ProofStatus.VALID)) {
            old.setStatus(ProofStatus.EXPIRED);
            proofRepo.save(old);
        }

        StateProof proof = new StateProof();
        proof.setStreamId(streamId);
        proof.setOldSchema(StateSchema.GRID_WINDOW_V1);
        proof.setNewSchema(StateSchema.PUMP_REACH_ROAD_V2);
        proof.setBoundRulePackageVersion(target);
        proof.setFieldMappingsJson(writeJson(mappings));
        proof.setConservationJson(writeJson(conservation));
        proof.setDependenciesJson(writeJson(dependencies));
        proof.setConvertibleFieldsJson(writeJson(convertible));
        proof.setMissingIntermediatesJson(writeJson(missingIntermediates));
        proof.setVerdict(ProofVerdict.CONDITIONALLY_ADAPTABLE);
        proof.setStatus(ProofStatus.VALID);
        return proofRepo.save(proof);
    }

    public StateProof get(Long proofId) {
        return proofRepo.findById(proofId).orElseThrow(() -> ApiException.notFound("证明不存在: " + proofId));
    }

    public List<StateProof> list(Long streamId) {
        return proofRepo.findByStreamIdOrderByCreatedAtDesc(streamId);
    }

    /** 当前有效证明（绑定流的目标规则包版本） */
    public StateProof currentValidProof(StreamDefinition stream) {
        if (stream.getTargetRulePackageVersion() == null) return null;
        return proofRepo.findByStreamIdAndBoundRulePackageVersionAndStatus(
                        stream.getId(), stream.getTargetRulePackageVersion(), ProofStatus.VALID)
                .stream().findFirst().orElse(null);
    }

    private Map<String, Object> mapping(String oldField, String newField, String transform,
                                        boolean reversible, boolean granularityOk, boolean convertible,
                                        String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("oldField", oldField);
        m.put("newField", newField);
        m.put("transform", transform);
        m.put("reversible", reversible);
        m.put("granularityOk", granularityOk);
        m.put("convertible", convertible);
        m.put("note", note);
        return m;
    }

    private String writeJson(Object value) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
