package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.EvidenceEntry;
import com.flood.statemigrate.domain.EvidenceKind;
import com.flood.statemigrate.service.EvidenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "事件证据账")
@RestController
@RequestMapping("/api/streams/{streamId}/evidence")
public class EvidenceController {

    private final EvidenceService evidenceService;

    public EvidenceController(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @Operation(summary = "证据账查询：按分区/类别（旧输出/新候选/新输出/已撤回）过滤")
    @GetMapping
    public List<EvidenceEntry> query(@PathVariable Long streamId,
                                     @RequestParam(required = false) Integer partitionNo,
                                     @RequestParam(required = false) EvidenceKind kind) {
        return evidenceService.query(streamId, partitionNo, kind);
    }
}
