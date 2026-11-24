package com.flood.statemigrate.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("防汛平台状态迁移与规则升级服务")
                        .version("1.0.0")
                        .description("流定义登记、检查点比对、状态语义证明、迁移编排（适配/回放/人工）、"
                                + "双轨校验、切换闸门、回退控制与事件证据账；"
                                + "预警结果账、标签可观察性分类、时间对齐、校准评估（等级命中/提前量/校准区间/右删失）"
                                + "与上线门禁（最低覆盖+安全下限，覆盖选择偏差）。")
                        .contact(new Contact().name("flood-ops")))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"))
                .schemaRequirement("basicAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("basic"));
    }
}
