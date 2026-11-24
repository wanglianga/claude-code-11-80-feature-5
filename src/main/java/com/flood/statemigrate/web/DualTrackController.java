package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.DualTrackDiff;
import com.flood.statemigrate.domain.DualTrackRun;
import com.flood.statemigrate.service.DualTrackService;
import com.flood.statemigrate.web.dto.DualTrackReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "双轨校验")
@RestController
public class DualTrackController {

    private final DualTrackService dualTrackService;

    public DualTrackController(DualTrackService dualTrackService) {
        this.dualTrackService = dualTrackService;
    }

    @Operation(summary = "启动双轨：旧新规则在同一输入截点并行计算")
    @PostMapping("/api/streams/{streamId}/dual-track-runs")
    @ResponseStatus(HttpStatus.CREATED)
    public List<DualTrackRun> start(@PathVariable Long streamId,
                                    @RequestBody(required = false) DualTrackReq req) {
        return dualTrackService.start(streamId,
                req == null ? null : req.partitionNo(),
                req == null ? null : req.inputCutOffset());
    }

    @Operation(summary = "双轨运行列表")
    @GetMapping("/api/streams/{streamId}/dual-track-runs")
    public List<DualTrackRun> list(@PathVariable Long streamId) {
        return dualTrackService.list(streamId);
    }

    @Operation(summary = "双轨差异明细（量化风险差异）")
    @GetMapping("/api/dual-track-runs/{runId}/diffs")
    public List<DualTrackDiff> diffs(@PathVariable Long runId) {
        return dualTrackService.diffs(runId);
    }
}
