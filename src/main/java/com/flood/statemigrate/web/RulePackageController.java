package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.RulePackage;
import com.flood.statemigrate.service.RulePackageService;
import com.flood.statemigrate.web.dto.RulePackageReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "规则包")
@RestController
@RequestMapping("/api/streams/{streamId}/rule-packages")
public class RulePackageController {

    private final RulePackageService rulePackageService;

    public RulePackageController(RulePackageService rulePackageService) {
        this.rulePackageService = rulePackageService;
    }

    @Operation(summary = "登记/二次修订规则包；多因子新规则修订会使相关比较与适配证明过期并重新绑定目标版本")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@PathVariable Long streamId, @Valid @RequestBody RulePackageReq req) {
        RulePackage pkg = new RulePackage();
        pkg.setVersion(req.version());
        pkg.setKind(req.kind());
        pkg.setConfigJson(req.configJson());
        return rulePackageService.register(streamId, pkg);
    }

    @Operation(summary = "规则包列表")
    @GetMapping
    public List<RulePackage> list(@PathVariable Long streamId) {
        return rulePackageService.list(streamId);
    }
}
