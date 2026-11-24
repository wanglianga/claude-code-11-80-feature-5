package com.flood.statemigrate.service;

import com.flood.statemigrate.domain.*;
import com.flood.statemigrate.repo.FieldObservationRepo;
import com.flood.statemigrate.repo.InterventionRecordRepo;
import com.flood.statemigrate.repo.StreamEventRepo;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 时间对齐器：把现场水深、封路、泵站动作与预警发生时间连接。
 *
 * 原则：处置之后的下降水位不得用来反证原先风险。
 *  - 观测按事件时间纳入（视频峰值后补传仍归到事件时间）；
 *  - 处置（抽排/封路）之后的观测标记 postIntervention，不参与「未积水」判定；
 *  - 处置之后的水位事件标记 usableForDisproof=false。
 */
@Component
public class TimeAligner {

    private final FieldObservationRepo observationRepo;
    private final InterventionRecordRepo interventionRepo;
    private final StreamEventRepo eventRepo;
    private final AggregationService aggregationService;

    public TimeAligner(FieldObservationRepo observationRepo, InterventionRecordRepo interventionRepo,
                       StreamEventRepo eventRepo, AggregationService aggregationService) {
        this.observationRepo = observationRepo;
        this.interventionRepo = interventionRepo;
        this.eventRepo = eventRepo;
        this.aggregationService = aggregationService;
    }

    /** 某条目窗口内的对齐结果 */
    public record Alignment(
            OutcomeLedgerEntry entry,
            FieldObservation peakObservation,
            InterventionRecord firstIntervention,
            boolean roadClosed,
            List<AlignedPoint> points,
            List<AlignedPoint> regulatedWaterLevels
    ) {
        public boolean hasObservation() { return peakObservation != null; }
    }

    /**
     * 对齐点：把一条观测/水位/处置与预警时刻连接。
     * role: DISPATCH_ROAD_CLOSED / DISPATCH_PUMP / OBSERVATION / WATER_LEVEL
     * usableForDisproof=false 表示发生在处置之后，不能反证原先风险。
     */
    public record AlignedPoint(String role, Instant at, Double depthCm, boolean afterIntervention,
                               boolean usableForDisproof, String ref) {
    }

    public Alignment align(OutcomeLedgerEntry entry) {
        Instant winStart = entry.getWindowStart();
        Instant winEnd = entry.getObservationWindowEnd() != null
                ? entry.getObservationWindowEnd() : entry.getWindowEnd();

        List<FieldObservation> observations = observationRepo
                .findByStreamIdAndRoadIdOrderByEventTimeAsc(entry.getStreamId(), entry.getRoadId()).stream()
                .filter(o -> !o.getEventTime().isBefore(winStart) && !o.getEventTime().isAfter(winEnd))
                .toList();

        List<InterventionRecord> interventions = interventionRepo
                .findByStreamIdAndRoadIdOrderByAtAsc(entry.getStreamId(), entry.getRoadId()).stream()
                .filter(i -> !i.getAt().isBefore(winStart) && !i.getAt().isAfter(winEnd))
                .toList();

        boolean roadClosed = interventions.stream().anyMatch(i -> i.getType() == InterventionType.ROAD_CLOSURE);
        InterventionRecord firstIntervention = interventions.stream()
                .min(Comparator.comparing(InterventionRecord::getAt)).orElse(null);

        // 事件时间基准下取窗口内最大水深观测作为该窗口结果
        FieldObservation peak = observations.stream()
                .filter(o -> firstIntervention == null || !o.getEventTime().isBefore(firstIntervention.getAt()))
                .max(Comparator.comparingDouble(FieldObservation::getDepthCm))
                .orElseGet(() -> observations.stream()
                        .max(Comparator.comparingDouble(FieldObservation::getDepthCm)).orElse(null));

        List<AlignedPoint> points = new ArrayList<>();
        for (InterventionRecord i : interventions) {
            points.add(new AlignedPoint("DISPATCH_" + i.getType().name(), i.getAt(), null,
                    false, true, i.getRef() + ":" + i.getType()));
        }
        for (FieldObservation o : observations) {
            boolean after = firstIntervention != null && !o.getEventTime().isBefore(firstIntervention.getAt());
            points.add(new AlignedPoint("OBSERVATION", o.getEventTime(), o.getDepthCm(), after,
                    !after, o.getSource() + ":" + o.getId()));
        }

        // 处置之后的水位事件：记录但不用于反证原先风险
        List<AlignedPoint> regulatedLevels = new ArrayList<>();
        if (firstIntervention != null) {
            for (StreamEvent e : eventRepo.findByStreamIdAndPartitionNoAndEventTimeBetweenOrderByEventTimeAsc(
                    entry.getStreamId(), entry.getPartitionNo(), winStart, winEnd)) {
                if ("WATER_LEVEL".equals(e.getType()) && !e.getEventTime().isBefore(firstIntervention.getAt())) {
                    double level = aggregationService.readTree(e.getPayloadJson()).path("levelM").asDouble(0);
                    regulatedLevels.add(new AlignedPoint("WATER_LEVEL", e.getEventTime(), null, true, false,
                            "levelM=" + level + " 处置后水位不反证风险"));
                }
            }
        }

        points.sort(Comparator.comparing(AlignedPoint::at));
        return new Alignment(entry, peak, firstIntervention, roadClosed, points, regulatedLevels);
    }
}
