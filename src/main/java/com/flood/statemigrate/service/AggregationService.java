package com.flood.statemigrate.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.flood.statemigrate.domain.StreamEvent;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 事件聚合：把原始事件聚合为规则输入；并构建泵站—河段—道路三层中间状态。
 * 高水位判定：水位 > 基准 + 0.5m。泵站启停滞后 = 首个高水位时刻 → 首次泵开；
 * 泵始终未开且出现过高水位 → 滞后 = 高水位至窗口末（绝不把缺失启停区间零值填充）。
 */
@Service
public class AggregationService {

    public static final double HIGH_WATER_MARGIN_M = 0.5;
    private static final double DEFAULT_CREDIBILITY = 0.9;

    private final ObjectMapper om;

    public AggregationService(ObjectMapper om) {
        this.om = om;
    }

    /** 聚合 [windowEnd - windowHours, windowEnd] 内的事件 */
    public AggInput aggregate(List<StreamEvent> events, double baseline, Instant windowEnd, int windowHours) {
        Instant windowStart = windowEnd.minus(Duration.ofHours(windowHours));
        List<StreamEvent> in = events.stream()
                .filter(e -> !e.getEventTime().isBefore(windowStart) && !e.getEventTime().isAfter(windowEnd))
                .sorted(Comparator.comparing(StreamEvent::getEventTime))
                .toList();

        double rain = 0;
        double depth = 0;
        double credSum = 0;
        int credCount = 0;
        List<double[]> levels = new ArrayList<>(); // {epochSecond, levelM}
        List<StreamEvent> pumpEvents = new ArrayList<>();
        List<Long> eventIds = new ArrayList<>();
        long from = Long.MAX_VALUE, to = Long.MIN_VALUE;

        for (StreamEvent e : in) {
            eventIds.add(e.getId());
            from = Math.min(from, e.getEventOffset());
            to = Math.max(to, e.getEventOffset());
            JsonNode p = readTree(e.getPayloadJson());
            switch (e.getType()) {
                case "RAIN" -> rain += p.path("mm").asDouble(0);
                case "WATER_LEVEL" -> levels.add(new double[]{e.getEventTime().getEpochSecond(), p.path("levelM").asDouble(0)});
                case "PUMP" -> pumpEvents.add(e);
                case "DEVICE_HEALTH" -> { credSum += p.path("score").asDouble(DEFAULT_CREDIBILITY); credCount++; }
                case "ROAD_REPORT", "VIDEO" -> depth = Math.max(depth, p.path("depthCm").asDouble(0));
                default -> { /* 未知类型不参与聚合 */ }
            }
        }

        double trend = 0;
        boolean trendPresent = levels.size() >= 2;
        if (trendPresent) {
            double[] first = levels.get(0);
            double[] last = levels.get(levels.size() - 1);
            double hours = (last[0] - first[0]) / 3600.0;
            if (hours > 0) {
                trend = (last[1] - first[1]) * 100.0 / hours; // cm/h
            }
        }

        double highWaterThreshold = baseline + HIGH_WATER_MARGIN_M;
        Instant highWaterSince = null;
        for (StreamEvent e : in) {
            if ("WATER_LEVEL".equals(e.getType())
                    && readTree(e.getPayloadJson()).path("levelM").asDouble(0) > highWaterThreshold) {
                highWaterSince = e.getEventTime();
                break;
            }
        }

        double lagMin = 0;
        if (highWaterSince != null) {
            Instant firstPumpOn = null;
            for (StreamEvent e : pumpEvents) {
                if (readTree(e.getPayloadJson()).path("on").asBoolean(false)
                        && !e.getEventTime().isBefore(highWaterSince)) {
                    firstPumpOn = e.getEventTime();
                    break;
                }
            }
            Instant lagEnd = firstPumpOn != null ? firstPumpOn : windowEnd;
            lagMin = Duration.between(highWaterSince, lagEnd).toMillis() / 60000.0;
        }

        double credibility = credCount > 0 ? credSum / credCount : DEFAULT_CREDIBILITY;
        if (from == Long.MAX_VALUE) { from = 0; to = 0; }
        return new AggInput(round2(rain), round2(trend), round2(lagMin), round3(credibility), round2(depth),
                from, to, eventIds, !pumpEvents.isEmpty(), trendPresent);
    }

    /** 由事件构建泵站—河段—道路三层中间状态 */
    public LayeredState buildLayeredState(PartitionConfig pc, List<StreamEvent> events, double baseline,
                                          Instant windowStart, Instant windowEnd) {
        AggInput agg = aggregate(events, baseline, windowEnd, (int) Duration.between(windowStart, windowEnd).toHours());
        List<PumpTransition> transitions = pumpTransitions(events);
        double runtimeMin = pumpRuntimeMin(transitions, windowStart, windowEnd);
        Double lastLevel = null;
        for (StreamEvent e : events) {
            if ("WATER_LEVEL".equals(e.getType())) {
                lastLevel = readTree(e.getPayloadJson()).path("levelM").asDouble(0);
            }
        }
        return new LayeredState(pc, agg, transitions, round2(runtimeMin), lastLevel, baseline, windowStart, windowEnd);
    }

    /** 泵站启停守恒：由启停事件推导窗口内运行时长，与快照累计量比对（容差 1 分钟） */
    public List<ConservationResult> checkPumpConservation(JsonNode snapshot) {
        List<ConservationResult> results = new ArrayList<>();
        JsonNode eventsNode = snapshot.get("pumpOnOffEvents");
        JsonNode runtimeNode = snapshot.get("pumpRuntimeMin");
        if (eventsNode == null || eventsNode.isNull()) {
            results.add(new ConservationResult("PUMP_ON_OFF_CONSERVATION", false,
                    "快照缺失泵站启停事件 pumpOnOffEvents，禁止以零值填充缺失启停区间"));
            return results;
        }
        if (runtimeNode == null || runtimeNode.isNull()) {
            results.add(new ConservationResult("PUMP_ON_OFF_CONSERVATION", false,
                    "快照缺失泵站累计运行时长 pumpRuntimeMin，无法核验启停守恒"));
            return results;
        }
        Instant windowStart = Instant.parse(snapshot.path("windowStart").asText());
        Instant windowEnd = Instant.parse(snapshot.path("windowEnd").asText());
        List<PumpTransition> transitions = new ArrayList<>();
        for (JsonNode n : eventsNode) {
            transitions.add(new PumpTransition(Instant.parse(n.path("t").asText()), n.path("on").asBoolean()));
        }
        double derived = pumpRuntimeMin(transitions, windowStart, windowEnd);
        double recorded = runtimeNode.asDouble();
        double diff = Math.abs(derived - recorded);
        boolean ok = diff <= 1.0;
        results.add(new ConservationResult("PUMP_ON_OFF_CONSERVATION", ok,
                "启停事件推导运行时长=" + round2(derived) + "min，快照累计=" + round2(recorded)
                        + "min，偏差=" + round2(diff) + "min（容差1min）" + (ok ? "，守恒成立" : "，不守恒")));
        return results;
    }

    /** 窗口粒度守恒：旧窗口须为约两小时，与新规则两小时累计粒度一致 */
    public ConservationResult checkWindowGranularity(JsonNode snapshot) {
        JsonNode ws = snapshot.get("windowStart");
        JsonNode we = snapshot.get("windowEnd");
        if (ws == null || we == null) {
            return new ConservationResult("WINDOW_GRANULARITY_MATCH", false, "快照缺失 windowStart/windowEnd");
        }
        long minutes = Duration.between(Instant.parse(ws.asText()), Instant.parse(we.asText())).toMinutes();
        boolean ok = Math.abs(minutes - 120) <= 5;
        return new ConservationResult("WINDOW_GRANULARITY_MATCH", ok,
                "窗口长度=" + minutes + "min，期望≈120min" + (ok ? "，粒度一致" : "，粒度不一致"));
    }

    public record PumpTransition(Instant t, boolean on) {
    }

    public record LayeredState(PartitionConfig partition, AggInput agg, List<PumpTransition> pumpTransitions,
                               double pumpRuntimeMin, Double lastLevelM, double baseline,
                               Instant windowStart, Instant windowEnd) {
    }

    public List<PumpTransition> pumpTransitions(List<StreamEvent> events) {
        return events.stream()
                .filter(e -> "PUMP".equals(e.getType()))
                .sorted(Comparator.comparing(StreamEvent::getEventTime))
                .map(e -> new PumpTransition(e.getEventTime(), readTree(e.getPayloadJson()).path("on").asBoolean()))
                .toList();
    }

    /** 由启停事件序列推导窗口内泵运行分钟数（裁剪到窗口边界） */
    public double pumpRuntimeMin(List<PumpTransition> transitions, Instant windowStart, Instant windowEnd) {
        List<PumpTransition> sorted = transitions.stream()
                .sorted(Comparator.comparing(PumpTransition::t)).toList();
        double minutes = 0;
        Instant onAt = null;
        for (PumpTransition tr : sorted) {
            if (tr.on() && onAt == null) {
                onAt = tr.t();
            } else if (!tr.on() && onAt != null) {
                minutes += clampedMinutes(onAt, tr.t(), windowStart, windowEnd);
                onAt = null;
            }
        }
        if (onAt != null) {
            minutes += clampedMinutes(onAt, windowEnd, windowStart, windowEnd);
        }
        return minutes;
    }

    private double clampedMinutes(Instant from, Instant to, Instant windowStart, Instant windowEnd) {
        Instant f = from.isBefore(windowStart) ? windowStart : from;
        Instant t = to.isAfter(windowEnd) ? windowEnd : to;
        if (!t.isAfter(f)) return 0;
        return Duration.between(f, t).toMillis() / 60000.0;
    }

    public JsonNode readTree(String json) {
        try {
            return om.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("非法 JSON 负载: " + e.getMessage(), e);
        }
    }

    public static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    public static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
