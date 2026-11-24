package com.flood.statemigrate.service;

import com.flood.statemigrate.domain.*;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 标签可观察性分类器：把一条结果账 + 其观测/处置对齐结果分类为
 * 已核验水深 / 受处置影响的观测 / 视频延迟标签 / 人工延迟标签 / 完全未观察。
 *
 * 关键约束：
 *  - 红色预警后抽排且水位未再上升 → 受处置影响的观测，不得计为新规则假阳性；
 *  - 视频识别在峰值后补传 → 按事件时间纳入，保留右删失（评估器处理），此处标记为视频延迟标签；
 *  - 道路封闭导致无标签 → 完全未观察。
 */
@Component
public class LabelClassifier {

    /** 标签到达超过该时长视为延迟标签 */
    public static final Duration DELAY_THRESHOLD = Duration.ofHours(6);

    public record Classification(
            ObservabilityClass observability,
            String reason,
            Double labelDepthCm,
            LabelSource labelSource,
            Instant labelEventTime,
            Instant labelObservedAt,
            boolean censored,
            String censoringReason,
            boolean postIntervention,
            InterventionRecord triggeredBy
    ) {
        public boolean isObservation() { return observability.isObservation(); }
    }

    /**
     * @param entry        结果账条目（其 outputTime 为预警时刻）
     * @param observation  与窗口对齐的观测（可为 null → 未观察）
     * @param intervention 窗口内该道路的首个处置动作（可为 null）
     * @param asOf         评估当时时刻：观测晚于 asOf 到达 → 右删失（尚未可识别）
     * @param roadClosed   道路是否在窗口内被封闭（视频/人工标签因封路缺失）
     */
    public Classification classify(OutcomeLedgerEntry entry, FieldObservation observation,
                                   InterventionRecord intervention, Instant asOf, boolean roadClosed) {
        if (observation == null) {
            if (roadClosed) {
                return new Classification(ObservabilityClass.UNOBSERVED,
                        "道路在窗口内封闭，视频/人工标签缺失，无可用观测", null, null, null, null,
                        true, "道路封闭导致标签缺失（不可识别）", false, intervention);
            }
            boolean rightCensored = entry.getObservationWindowEnd() != null
                    && entry.getObservationWindowEnd().isAfter(asOf);
            return new Classification(ObservabilityClass.UNOBSERVED,
                    rightCensored ? "观察窗尚未结束且无标签到达（右删失，暂不可识别）" : "窗口内完全未观察到结果",
                    null, null, null, null, true,
                    rightCensored ? "右删失：观察窗 " + entry.getObservationWindowEnd() + " 晚于评估时刻 " + asOf
                            : "窗口内无任何观测", false, intervention);
        }

        boolean postIntervention = intervention != null && observation.getEventTime() != null
                && !observation.getEventTime().isBefore(intervention.getAt());
        boolean delayed = observation.getObservedAt() != null && observation.getEventTime() != null
                && Duration.between(observation.getEventTime(), observation.getObservedAt()).compareTo(DELAY_THRESHOLD) > 0;
        boolean arrivalAfterAsOf = observation.getObservedAt() != null && observation.getObservedAt().isAfter(asOf);

        ObservabilityClass cls;
        String reason;
        if (observation.getSource() == LabelSource.FIELD_VERIFICATION) {
            cls = ObservabilityClass.VERIFIED_DEPTH;
            reason = "现场独立核验水深（严格评估集可用）";
        } else if (observation.getSource() == LabelSource.VIDEO) {
            cls = postIntervention ? ObservabilityClass.INTERVENTION_AFFECTED : ObservabilityClass.DELAYED_VIDEO;
            reason = postIntervention
                    ? "视频标签在处置动作之后，受处置影响，不得计作假阳性"
                    : (delayed ? "视频识别在峰值后补传，按事件时间纳入并保留右删失"
                            : "视频识别标签，按事件时间纳入");
        } else {
            cls = postIntervention ? ObservabilityClass.INTERVENTION_AFFECTED : ObservabilityClass.DELAYED_MANUAL;
            reason = postIntervention
                    ? "人工上报在处置动作之后，受处置影响，不得计作假阳性"
                    : "人工上报标签（通常迟到数日），按事件时间纳入";
        }

        // 受处置影响的观测若事件时间早于处置，则重新按延迟标签归类
        if (postIntervention) {
            cls = ObservabilityClass.INTERVENTION_AFFECTED;
            reason = "预警后已处置（" + intervention.getType() + " @" + intervention.getAt()
                    + "），观测发生在处置之后：水位未再上升/下降不可反证原先风险，不得计作假阳性";
        }

        boolean censored = arrivalAfterAsOf;
        String censoringReason = arrivalAfterAsOf
                ? "标签到达 " + observation.getObservedAt() + " 晚于评估时刻 " + asOf + "，本次评估按右删失处理"
                : null;

        return new Classification(cls, reason, observation.getDepthCm(), observation.getSource(),
                observation.getEventTime(), observation.getObservedAt(), censored, censoringReason,
                postIntervention, intervention);
    }
}
