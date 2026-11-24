package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.SwitchGate;
import com.flood.statemigrate.service.GateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "切换闸门")
@RestController
@RequestMapping("/api/streams/{streamId}/gate")
public class GateController {

    private final GateService gateService;

    public GateController(GateService gateService) {
        this.gateService = gateService;
    }

    @Operation(summary = "闸门状态")
    @GetMapping
    public SwitchGate get(@PathVariable Long streamId) {
        return gateService.get(streamId);
    }

    @Operation(summary = "评估闸门：证据/覆盖阈值/待验证升级；不满足时维持旧输出")
    @PostMapping("/evaluate")
    public Map<String, Object> evaluate(@PathVariable Long streamId) {
        return gateService.evaluate(streamId);
    }

    @Operation(summary = "发布新输出（关键分区满足证据与覆盖阈值才放行）")
    @PostMapping("/release")
    public Map<String, Object> release(@PathVariable Long streamId) {
        return gateService.release(streamId);
    }
}
