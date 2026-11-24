package com.flood.statemigrate.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 预警结果账：为每个「道路—窗口」冻结当时的规则版本、状态水印、输入可信度、
 * 风险输出与实际派单干预。冻结后不随之后的新规则/新标签改变；
 * 标签与可观察性分类单独记录在本行（由分类器写入）。
 *
 * 规则升级跨越同一积水事件时，用 exposureGroup 标识同一事件，
 * 并以 ruleChangeBoundary 与 outputTime 的相对位置拆分暴露（exposureSplit）。
 */
@Entity
@Table(name = "outcome_ledger_entry")
public class OutcomeLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    @Column(nullable = false)
    private int partitionNo;

    @Column(nullable = false, length = 64)
    private String roadId;

    /** 道路类型：ARTERIAL/COLLECTOR/LOCAL，用于分层评估 */
    @Column(nullable = false, length = 32)
    private String roadType;

    /** 所属流域，用于分层评估与选择偏差检查 */
    @Column(nullable = false, length = 64)
    private String basin;

    @Column(nullable = false)
    private Instant windowStart;

    @Column(nullable = false)
    private Instant windowEnd;

    /** 预警输出时刻：暴露拆分与提前量均以此为基准 */
    @Column(nullable = false)
    private Instant outputTime;

    @Column(nullable = false, length = 32)
    private String ruleVersion;

    /** 状态水印（状态版本），如 dual:run-3 / replay:plan-5 / snap:chk-12 */
    @Column(nullable = false, length = 64)
    private String stateVersion;

    @Column(nullable = false)
    private long watermarkOffset;

    @Column(nullable = false)
    private double inputCredibility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RiskLevel riskLevel;

    @Column(nullable = false)
    private double riskScore;

    /** 实际派单干预（冻结当时） */
    @Column(nullable = false)
    private boolean dispatchPresent;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private InterventionType interventionType;

    private Instant interventionAt;

    @Column(length = 64)
    private String interventionRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private OutcomeStage stage;

    /** 同一积水事件分组，用于跨规则升序的暴露拆分 */
    @Column(length = 64)
    private String exposureGroup;

    /** 规则切换边界时刻（同一事件跨越升级时使用） */
    private Instant ruleChangeBoundary;

    /** SINGLE / BEFORE_RULE_CHANGE / AFTER_RULE_CHANGE */
    @Column(nullable = false, length = 24)
    private String exposureSplit;

    /** 观测窗口右界（outputTime + 观察窗），标签必须落在其中 */
    @Column(nullable = false)
    private Instant observationWindowEnd;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private ObservabilityClass observability;

    @Column(length = 512)
    private String classificationReason;

    private Double labelDepthCm;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private LabelSource labelSource;

    /** 标签事件时间（积水发生/测量/峰值时刻），按事件时间纳入评估 */
    private Instant labelEventTime;

    /** 标签到达评估系统的时间（迟到标签据此判定与右删失） */
    private Instant labelObservedAt;

    @Column(nullable = false)
    private boolean censored;

    @Column(length = 256)
    private String censoringReason;

    /** 该标签是否在处置动作之后（处置后的下降/未上升水位不得反证原先风险） */
    @Column(nullable = false)
    private boolean postIntervention;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }
    public int getPartitionNo() { return partitionNo; }
    public void setPartitionNo(int partitionNo) { this.partitionNo = partitionNo; }
    public String getRoadId() { return roadId; }
    public void setRoadId(String roadId) { this.roadId = roadId; }
    public String getRoadType() { return roadType; }
    public void setRoadType(String roadType) { this.roadType = roadType; }
    public String getBasin() { return basin; }
    public void setBasin(String basin) { this.basin = basin; }
    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }
    public Instant getOutputTime() { return outputTime; }
    public void setOutputTime(Instant outputTime) { this.outputTime = outputTime; }
    public String getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(String ruleVersion) { this.ruleVersion = ruleVersion; }
    public String getStateVersion() { return stateVersion; }
    public void setStateVersion(String stateVersion) { this.stateVersion = stateVersion; }
    public long getWatermarkOffset() { return watermarkOffset; }
    public void setWatermarkOffset(long watermarkOffset) { this.watermarkOffset = watermarkOffset; }
    public double getInputCredibility() { return inputCredibility; }
    public void setInputCredibility(double inputCredibility) { this.inputCredibility = inputCredibility; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }
    public double getRiskScore() { return riskScore; }
    public void setRiskScore(double riskScore) { this.riskScore = riskScore; }
    public boolean isDispatchPresent() { return dispatchPresent; }
    public void setDispatchPresent(boolean dispatchPresent) { this.dispatchPresent = dispatchPresent; }
    public InterventionType getInterventionType() { return interventionType; }
    public void setInterventionType(InterventionType interventionType) { this.interventionType = interventionType; }
    public Instant getInterventionAt() { return interventionAt; }
    public void setInterventionAt(Instant interventionAt) { this.interventionAt = interventionAt; }
    public String getInterventionRef() { return interventionRef; }
    public void setInterventionRef(String interventionRef) { this.interventionRef = interventionRef; }
    public OutcomeStage getStage() { return stage; }
    public void setStage(OutcomeStage stage) { this.stage = stage; }
    public String getExposureGroup() { return exposureGroup; }
    public void setExposureGroup(String exposureGroup) { this.exposureGroup = exposureGroup; }
    public Instant getRuleChangeBoundary() { return ruleChangeBoundary; }
    public void setRuleChangeBoundary(Instant ruleChangeBoundary) { this.ruleChangeBoundary = ruleChangeBoundary; }
    public String getExposureSplit() { return exposureSplit; }
    public void setExposureSplit(String exposureSplit) { this.exposureSplit = exposureSplit; }
    public Instant getObservationWindowEnd() { return observationWindowEnd; }
    public void setObservationWindowEnd(Instant observationWindowEnd) { this.observationWindowEnd = observationWindowEnd; }
    public ObservabilityClass getObservability() { return observability; }
    public void setObservability(ObservabilityClass observability) { this.observability = observability; }
    public String getClassificationReason() { return classificationReason; }
    public void setClassificationReason(String classificationReason) { this.classificationReason = classificationReason; }
    public Double getLabelDepthCm() { return labelDepthCm; }
    public void setLabelDepthCm(Double labelDepthCm) { this.labelDepthCm = labelDepthCm; }
    public LabelSource getLabelSource() { return labelSource; }
    public void setLabelSource(LabelSource labelSource) { this.labelSource = labelSource; }
    public Instant getLabelEventTime() { return labelEventTime; }
    public void setLabelEventTime(Instant labelEventTime) { this.labelEventTime = labelEventTime; }
    public Instant getLabelObservedAt() { return labelObservedAt; }
    public void setLabelObservedAt(Instant labelObservedAt) { this.labelObservedAt = labelObservedAt; }
    public boolean isCensored() { return censored; }
    public void setCensored(boolean censored) { this.censored = censored; }
    public String getCensoringReason() { return censoringReason; }
    public void setCensoringReason(String censoringReason) { this.censoringReason = censoringReason; }
    public boolean isPostIntervention() { return postIntervention; }
    public void setPostIntervention(boolean postIntervention) { this.postIntervention = postIntervention; }
    public Instant getCreatedAt() { return createdAt; }
}
