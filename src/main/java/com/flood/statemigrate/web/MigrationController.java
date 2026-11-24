package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.MigrationPlan;
import com.flood.statemigrate.service.PlannerService;
import com.flood.statemigrate.web.dto.AdjudicateReq;
import com.flood.statemigrate.web.dto.PlanExecuteReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@Tag(name = "迁移编排")
@RestController
public class MigrationController {

    private final PlannerService plannerService;

    public MigrationController(PlannerService plannerService) {
        this.plannerService = plannerService;
    }

    @Operation(summary = "为各分区生成迁移计划（适配/限定窗口回放/人工裁定）")
    @PostMapping("/api/streams/{streamId}/migration-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public List<MigrationPlan> generate(@PathVariable Long streamId) {
        return plannerService.generatePlans(streamId);
    }

    @Operation(summary = "迁移计划列表")
    @GetMapping("/api/streams/{streamId}/migration-plans")
    public List<MigrationPlan> list(@PathVariable Long streamId) {
        return plannerService.list(streamId);
    }

    @Operation(summary = "执行全部待执行计划")
    @PostMapping("/api/streams/{streamId}/migration-plans/execute-all")
    public List<MigrationPlan> executeAll(@PathVariable Long streamId) {
        List<MigrationPlan> executed = new ArrayList<>();
        for (MigrationPlan plan : plannerService.list(streamId)) {
            if (plan.getStatus() == com.flood.statemigrate.domain.PlanStatus.PLANNED
                    && plan.getRoute() != com.flood.statemigrate.domain.PlanRoute.MANUAL) {
                executed.add(plannerService.execute(plan.getId(), null, null));
            }
        }
        return executed;
    }

    @Operation(summary = "执行迁移计划；ADAPT 守恒失败自动转限定窗口回放")
    @PostMapping("/api/migration-plans/{planId}/execute")
    public MigrationPlan execute(@PathVariable Long planId,
                                 @RequestBody(required = false) PlanExecuteReq req) {
        return plannerService.execute(planId,
                req == null ? null : req.replayWindowStart(),
                req == null ? null : req.replayWindowEnd());
    }

    @Operation(summary = "人工裁定 MANUAL 计划的路线")
    @PostMapping("/api/migration-plans/{planId}/adjudicate")
    public MigrationPlan adjudicate(@PathVariable Long planId, @Valid @RequestBody AdjudicateReq req) {
        return plannerService.adjudicate(planId, req.route(), req.reason());
    }

    @Operation(summary = "迁移计划详情")
    @GetMapping("/api/migration-plans/{planId}")
    public MigrationPlan get(@PathVariable Long planId) {
        return plannerService.get(planId);
    }
}
