package com.flood.statemigrate.service;

import tools.jackson.databind.ObjectMapper;
import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.OutcomeLedgerRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 预警结果账：冻结每个「道路—窗口」当时的规则版本、状态水印、输入可信度、
 * 风险输出与实际派单干预。冻结后不随新规则/新标签改变。
 * 规则升级跨越同一积水事件时，按输出时刻相对规则切换边界拆分暴露。
 */
@Service
public class OutcomeLedgerService {

    private final OutcomeLedgerRepo ledgerRepo;
    private final StreamService streamService;
    private final ObjectMapper om;

    public OutcomeLedgerService(OutcomeLedgerRepo ledgerRepo, StreamService streamService, ObjectMapper om) {
        this.ledgerRepo = ledgerRepo;
        this.streamService = streamService;
        this.om = om;
    }

    /** 冻结一条结果账条目。partitionNo 决定 roadType/basin；ruleChangeBoundary 用于暴露拆分。 */
    @Transactional
    public OutcomeLedgerEntry freeze(Long streamId, OutcomeLedgerEntry input) {
        StreamDefinition stream = streamService.get(streamId);
        PartitionConfig pc = streamService.partition(stream, input.getPartitionNo());
        input.setStreamId(streamId);
        if (input.getRoadId() == null || input.getRoadId().isBlank()) {
            input.setRoadId(pc.roadId());
        }
        if (input.getRoadType() == null) {
            input.setRoadType(pc.roadType());
        }
        if (input.getBasin() == null) {
            input.setBasin(pc.basin());
        }
        if (input.getOutputTime() == null) {
            throw ApiException.badRequest("结果账条目缺少 outputTime（预警输出时刻）");
        }
        if (input.getStage() == null) {
            throw ApiException.badRequest("结果账条目缺少 stage（OLD_PUBLISHED/NEW_PUBLISHED/SHADOW_SCREENING）");
        }
        if (input.getWindowEnd() == null) {
            input.setWindowEnd(input.getOutputTime());
        }
        if (input.getWindowStart() == null) {
            input.setWindowStart(input.getWindowEnd().minus(Duration.ofHours(2)));
        }
        if (input.getObservationWindowEnd() == null) {
            input.setObservationWindowEnd(input.getOutputTime().plus(Duration.ofHours(6)));
        }
        if (input.getExposureGroup() == null) {
            input.setExposureGroup(input.getRoadId() + "@" + input.getOutputTime());
        }
        input.setExposureSplit(exposureSplit(input.getOutputTime(), input.getRuleChangeBoundary()));
        return ledgerRepo.save(input);
    }

    /** 暴露拆分：规则切换边界之前的输出 = BEFORE_RULE_CHANGE，之后 = AFTER_RULE_CHANGE，无边界 = SINGLE */
    public static String exposureSplit(Instant outputTime, Instant ruleChangeBoundary) {
        if (ruleChangeBoundary == null || outputTime == null) {
            return "SINGLE";
        }
        return outputTime.isBefore(ruleChangeBoundary) ? "BEFORE_RULE_CHANGE" : "AFTER_RULE_CHANGE";
    }

    public List<OutcomeLedgerEntry> list(Long streamId, Integer partitionNo) {
        return partitionNo != null
                ? ledgerRepo.findByStreamIdAndPartitionNoOrderByIdAsc(streamId, partitionNo)
                : ledgerRepo.findByStreamIdOrderByIdAsc(streamId);
    }

    public OutcomeLedgerEntry get(Long entryId) {
        return ledgerRepo.findById(entryId).orElseThrow(() -> ApiException.notFound("结果账条目不存在: " + entryId));
    }

    /** 按暴露组统计拆分暴露（规则升级跨越同一事件时） */
    public java.util.Map<String, Object> exposureSummary(Long streamId) {
        List<OutcomeLedgerEntry> all = ledgerRepo.findByStreamIdOrderByIdAsc(streamId);
        java.util.Map<String, java.util.List<OutcomeLedgerEntry>> groups = new java.util.LinkedHashMap<>();
        for (OutcomeLedgerEntry e : all) {
            groups.computeIfAbsent(e.getExposureGroup(), k -> new java.util.ArrayList<>()).add(e);
        }
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        for (var g : groups.entrySet()) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("exposureGroup", g.getKey());
            row.put("entries", g.getValue().size());
            row.put("splits", g.getValue().stream().map(OutcomeLedgerEntry::getExposureSplit).distinct().toList());
            row.put("ruleVersions", g.getValue().stream().map(OutcomeLedgerEntry::getRuleVersion).distinct().toList());
            row.put("crossEventSplit", g.getValue().stream().map(OutcomeLedgerEntry::getExposureSplit).distinct().count() > 1);
            rows.add(row);
        }
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("streamId", streamId);
        result.put("groups", rows);
        return result;
    }

    @SuppressWarnings("unused")
    private String writeJson(Object value) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
