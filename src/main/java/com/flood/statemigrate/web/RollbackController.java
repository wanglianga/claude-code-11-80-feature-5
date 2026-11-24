package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.RollbackRecord;
import com.flood.statemigrate.service.RollbackService;
import com.flood.statemigrate.web.dto.RollbackReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "回退控制")
@RestController
@RequestMapping("/api/streams/{streamId}/rollbacks")
public class RollbackController {

    private final RollbackService rollbackService;

    public RollbackController(RollbackService rollbackService) {
        this.rollbackService = rollbackService;
    }

    @Operation(summary = "回退：旧规则快照置只读，撤回范围内新输出，闸门回到 HOLD")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RollbackRecord rollback(@PathVariable Long streamId, @Valid @RequestBody RollbackReq req) {
        return rollbackService.rollback(streamId, req.fromOffset(), req.toOffset(),
                req.partitions(), req.reason());
    }

    @Operation(summary = "回退记录")
    @GetMapping
    public List<RollbackRecord> list(@PathVariable Long streamId) {
        return rollbackService.list(streamId);
    }
}
