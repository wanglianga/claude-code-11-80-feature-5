package com.flood.statemigrate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 接口层冒烟：鉴权与流定义登记 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiSmokeTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/streams"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerAndGetStream() throws Exception {
        String body = """
                {
                  "streamCode": "T-API", "name": "接口测试流",
                  "inputTopic": "flood.api.raw", "eventTimeField": "eventTime",
                  "waterLevelBaseline": 2.5, "stateSchema": "GRID_WINDOW_V1",
                  "rulePackageVersion": "v1.0.0", "targetRulePackageVersion": "v2.0.0",
                  "totalPartitions": 1, "eventRetentionHours": 48,
                  "partitionConfigJson": "[{\\"partitionNo\\":0,\\"roadId\\":\\"R-0\\",\\"pumpId\\":\\"P-0\\",\\"reachId\\":\\"RCH-0\\",\\"key\\":true}]"
                }
                """;
        mockMvc.perform(post("/api/streams").with(httpBasic("operator", "operator123"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.streamCode").value("T-API"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/streams").with(httpBasic("operator", "operator123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].streamCode").value("T-API"));
    }

    @Test
    void registerStreamRejectsMismatchedPartitionConfig() throws Exception {
        String body = """
                {
                  "streamCode": "T-API-BAD", "name": "错误配置",
                  "inputTopic": "t", "eventTimeField": "eventTime",
                  "waterLevelBaseline": 2.5, "stateSchema": "GRID_WINDOW_V1",
                  "rulePackageVersion": "v1.0.0",
                  "totalPartitions": 2, "eventRetentionHours": 48,
                  "partitionConfigJson": "[{\\"partitionNo\\":0,\\"roadId\\":\\"R-0\\",\\"pumpId\\":\\"P-0\\",\\"reachId\\":\\"RCH-0\\",\\"key\\":true}]"
                }
                """;
        mockMvc.perform(post("/api/streams").with(httpBasic("operator", "operator123"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
