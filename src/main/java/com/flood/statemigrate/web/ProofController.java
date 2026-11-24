package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.StateProof;
import com.flood.statemigrate.service.ProverService;
import com.flood.statemigrate.web.dto.ProofReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "状态语义证明器")
@RestController
public class ProofController {

    private final ProverService proverService;

    public ProofController(ProverService proverService) {
        this.proverService = proverService;
    }

    @Operation(summary = "运行证明器：比较旧新字段/聚合粒度/依赖关系，给出守恒条件")
    @PostMapping("/api/streams/{streamId}/proofs")
    @ResponseStatus(HttpStatus.CREATED)
    public StateProof prove(@PathVariable Long streamId,
                            @RequestBody(required = false) ProofReq req) {
        return proverService.prove(streamId, req == null ? null : req.targetRulePackageVersion());
    }

    @Operation(summary = "证明列表")
    @GetMapping("/api/streams/{streamId}/proofs")
    public List<StateProof> list(@PathVariable Long streamId) {
        return proverService.list(streamId);
    }

    @Operation(summary = "证明详情")
    @GetMapping("/api/proofs/{proofId}")
    public StateProof get(@PathVariable Long proofId) {
        return proverService.get(proofId);
    }
}
