package com.flood.statemigrate.service;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.error.ApiException;
import com.flood.statemigrate.repo.FieldObservationRepo;
import com.flood.statemigrate.repo.InterventionRecordRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 现场观测与处置动作写入（标签与干预的事实来源） */
@Service
public class ObservationService {

    private final FieldObservationRepo observationRepo;
    private final InterventionRecordRepo interventionRepo;
    private final StreamService streamService;

    public ObservationService(FieldObservationRepo observationRepo, InterventionRecordRepo interventionRepo,
                              StreamService streamService) {
        this.observationRepo = observationRepo;
        this.interventionRepo = interventionRepo;
        this.streamService = streamService;
    }

    @Transactional
    public FieldObservation recordObservation(Long streamId, FieldObservation o) {
        StreamDefinition stream = streamService.get(streamId);
        PartitionConfig pc = streamService.partition(stream, o.getPartitionNo());
        o.setStreamId(streamId);
        if (o.getRoadId() == null || o.getRoadId().isBlank()) {
            o.setRoadId(pc.roadId());
        }
        if (o.getBasin() == null) {
            o.setBasin(pc.basin());
        }
        if (o.getObservedAt() == null) {
            o.setObservedAt(java.time.Instant.now());
        }
        if (o.getEventTime() == null) {
            throw ApiException.badRequest("观测缺少 eventTime（事件时间）");
        }
        return observationRepo.save(o);
    }

    @Transactional
    public InterventionRecord recordIntervention(Long streamId, InterventionRecord r) {
        StreamDefinition stream = streamService.get(streamId);
        PartitionConfig pc = streamService.partition(stream, r.getPartitionNo());
        r.setStreamId(streamId);
        if (r.getRoadId() == null || r.getRoadId().isBlank()) {
            r.setRoadId(pc.roadId());
        }
        if (r.getAt() == null) {
            throw ApiException.badRequest("处置缺少 at（处置发生时刻）");
        }
        return interventionRepo.save(r);
    }

    public List<FieldObservation> observations(Long streamId, Integer partitionNo) {
        return partitionNo != null
                ? observationRepo.findByStreamIdAndPartitionNoOrderByEventTimeAsc(streamId, partitionNo)
                : observationRepo.findByStreamIdOrderByIdAsc(streamId);
    }

    public List<InterventionRecord> interventions(Long streamId, Integer partitionNo) {
        return partitionNo != null
                ? interventionRepo.findByStreamIdAndPartitionNoOrderByAtAsc(streamId, partitionNo)
                : interventionRepo.findByStreamIdOrderByIdAsc(streamId);
    }
}
