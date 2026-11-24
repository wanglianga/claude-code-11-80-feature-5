package com.flood.statemigrate.service;

import com.flood.statemigrate.domain.StreamEvent;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.StreamEventRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class EventService {

    private static final Set<String> TYPES =
            Set.of("RAIN", "WATER_LEVEL", "PUMP", "DEVICE_HEALTH", "ROAD_REPORT", "VIDEO");

    private final StreamEventRepo eventRepo;
    private final StreamService streamService;

    public EventService(StreamEventRepo eventRepo, StreamService streamService) {
        this.eventRepo = eventRepo;
        this.streamService = streamService;
    }

    /** 事件写入：位点按分区自增分配 */
    @Transactional
    public StreamEvent ingest(Long streamId, StreamEvent event) {
        streamService.partition(streamService.get(streamId), event.getPartitionNo());
        if (!TYPES.contains(event.getType())) {
            throw ApiException.badRequest("未知事件类型: " + event.getType() + "，支持: " + TYPES);
        }
        long next = eventRepo.maxOffset(streamId, event.getPartitionNo()).orElse(0L) + 1;
        event.setStreamId(streamId);
        event.setEventOffset(next);
        return eventRepo.save(event);
    }

    public List<StreamEvent> list(Long streamId) {
        return eventRepo.findByStreamIdOrderByPartitionNoAscEventOffsetAsc(streamId);
    }

    public List<StreamEvent> listByPartition(Long streamId, int partitionNo) {
        return eventRepo.findByStreamIdAndPartitionNoAndEventOffsetLessThanEqualOrderByEventOffsetAsc(
                streamId, partitionNo, Long.MAX_VALUE);
    }
}
