package com.flood.statemigrate.web;

import com.flood.statemigrate.service.CheckpointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "检查点比对")
@RestController
@RequestMapping("/api/streams/{streamId}/checkpoint-compare")
public class CheckpointCompareController {

    private final CheckpointService checkpointService;

    public CheckpointCompareController(CheckpointService checkpointService) {
        this.checkpointService = checkpointService;
    }

    @Operation(summary = "检查点比对：位点/水印滞后、快照与迁移就绪度")
    @GetMapping
    public Map<String, Object> compare(@PathVariable Long streamId) {
        return checkpointService.compare(streamId);
    }
}
