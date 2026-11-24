package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.StreamDefinition;
import com.flood.statemigrate.domain.StreamStatus;
import com.flood.statemigrate.service.StreamService;
import com.flood.statemigrate.web.dto.StreamRegisterReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "流定义登记")
@RestController
@RequestMapping("/api/streams")
public class StreamController {

    private final StreamService streamService;

    public StreamController(StreamService streamService) {
        this.streamService = streamService;
    }

    @Operation(summary = "登记流定义（输入主题/事件时间/水位基准/状态模式/规则包版本）")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StreamDefinition register(@Valid @RequestBody StreamRegisterReq req) {
        StreamDefinition def = new StreamDefinition();
        def.setStreamCode(req.streamCode());
        def.setName(req.name());
        def.setInputTopic(req.inputTopic());
        def.setEventTimeField(req.eventTimeField());
        def.setWaterLevelBaseline(req.waterLevelBaseline());
        def.setStateSchema(req.stateSchema());
        def.setRulePackageVersion(req.rulePackageVersion());
        def.setTargetRulePackageVersion(req.targetRulePackageVersion());
        def.setTotalPartitions(req.totalPartitions());
        def.setEventRetentionHours(req.eventRetentionHours());
        def.setPartitionConfigJson(req.partitionConfigJson());
        def.setStatus(StreamStatus.ACTIVE);
        return streamService.register(def);
    }

    @Operation(summary = "流定义列表")
    @GetMapping
    public List<StreamDefinition> list() {
        return streamService.list();
    }

    @Operation(summary = "流定义详情")
    @GetMapping("/{id}")
    public StreamDefinition get(@PathVariable Long id) {
        return streamService.get(id);
    }
}
