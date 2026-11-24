package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.CheckpointManifest;
import com.flood.statemigrate.service.CheckpointService;
import com.flood.statemigrate.web.dto.CheckpointReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "检查点")
@RestController
@RequestMapping("/api/streams/{streamId}/checkpoints")
public class CheckpointController {

    private final CheckpointService checkpointService;

    public CheckpointController(CheckpointService checkpointService) {
        this.checkpointService = checkpointService;
    }

    @Operation(summary = "登记检查点（消费位点/窗口水印/状态快照/输出批次）")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CheckpointManifest record(@PathVariable Long streamId, @Valid @RequestBody CheckpointReq req) {
        CheckpointManifest cp = new CheckpointManifest();
        cp.setPartitionNo(req.partitionNo());
        cp.setConsumeOffset(req.consumeOffset());
        cp.setWindowWatermark(req.windowWatermark());
        cp.setStateSnapshotJson(req.stateSnapshotJson());
        cp.setOutputBatchId(req.outputBatchId());
        cp.setRulePackageVersion(req.rulePackageVersion());
        cp.setStateSchema(req.stateSchema());
        cp.setStatus(req.status());
        return checkpointService.record(streamId, cp);
    }

    @Operation(summary = "检查点清单")
    @GetMapping
    public List<CheckpointManifest> list(@PathVariable Long streamId) {
        return checkpointService.list(streamId);
    }
}
