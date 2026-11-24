package com.flood.statemigrate.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.flood.statemigrate.domain.StreamDefinition;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.StreamDefinitionRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class StreamService {

    private final StreamDefinitionRepo streamRepo;
    private final ObjectMapper om;

    public StreamService(StreamDefinitionRepo streamRepo, ObjectMapper om) {
        this.streamRepo = streamRepo;
        this.om = om;
    }

    @Transactional
    public StreamDefinition register(StreamDefinition def) {
        streamRepo.findByStreamCode(def.getStreamCode()).ifPresent(s -> {
            throw ApiException.conflict("流编码已存在: " + def.getStreamCode());
        });
        // 校验分区配置可解析且数量一致
        List<PartitionConfig> pcs = partitions(def);
        if (pcs.size() != def.getTotalPartitions()) {
            throw ApiException.badRequest("partitionConfig 分区数(" + pcs.size() + ")与 totalPartitions("
                    + def.getTotalPartitions() + ")不一致");
        }
        return streamRepo.save(def);
    }

    public StreamDefinition get(Long id) {
        return streamRepo.findById(id).orElseThrow(() -> ApiException.notFound("流定义不存在: " + id));
    }

    public List<StreamDefinition> list() {
        return streamRepo.findAll();
    }

    public StreamDefinition save(StreamDefinition def) {
        return streamRepo.save(def);
    }

    public List<PartitionConfig> partitions(StreamDefinition def) {
        try {
            List<Map<String, Object>> raw = om.readValue(def.getPartitionConfigJson(),
                    new TypeReference<>() {});
            return raw.stream().map(m -> new PartitionConfig(
                    ((Number) m.get("partitionNo")).intValue(),
                    String.valueOf(m.get("roadId")),
                    String.valueOf(m.get("pumpId")),
                    String.valueOf(m.get("reachId")),
                    Boolean.TRUE.equals(m.get("key")),
                    optionalText(m, "roadType", PartitionConfig.DEFAULT_ROAD_TYPE),
                    optionalText(m, "basin", PartitionConfig.DEFAULT_BASIN)
            )).toList();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("partitionConfigJson 解析失败: " + e.getMessage());
        }
    }

    public PartitionConfig partition(StreamDefinition def, int partitionNo) {
        return partitions(def).stream()
                .filter(p -> p.partitionNo() == partitionNo)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("流 " + def.getStreamCode() + " 无分区 " + partitionNo));
    }

    private static String optionalText(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        if (v == null || String.valueOf(v).isBlank() || "null".equals(String.valueOf(v))) {
            return fallback;
        }
        return String.valueOf(v);
    }
}
