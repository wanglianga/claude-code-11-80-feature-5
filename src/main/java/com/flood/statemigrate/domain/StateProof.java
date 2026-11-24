package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 状态语义证明：比较旧/新字段、聚合粒度与依赖关系，
 * 给出旧状态确实可转换的守恒条件。证明绑定目标规则包版本，规则二次修订后过期。
 *
 * fieldMappingsJson: [{"oldField":"rain2hAcc","newField":"road.rain2hAcc","transform":"IDENTITY","reversible":true,"granularityOk":true,"convertible":true}]
 * conservationJson:  [{"name":"PUMP_ON_OFF_CONSERVATION","expression":"sum(runtime(on/off events)) == pumpRuntimeMin","tolerance":"PT1M"}]
 * dependenciesJson:  [{"newField":"reach.inflowAcc","requires":["upstreamInflowAcc","gridToReachMapping"]}]
 */
@Entity
@Table(name = "state_proof")
public class StateProof {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StateSchema oldSchema;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StateSchema newSchema;

    /** 本证明绑定的新规则包版本 */
    @Column(nullable = false, length = 32)
    private String boundRulePackageVersion;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String fieldMappingsJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String conservationJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String dependenciesJson;

    /** 确实可转换的旧字段 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String convertibleFieldsJson;

    /** 模式级缺失的中间量（适配器必须拒绝而非零值填充） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String missingIntermediatesJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProofVerdict verdict;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProofStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }
    public StateSchema getOldSchema() { return oldSchema; }
    public void setOldSchema(StateSchema oldSchema) { this.oldSchema = oldSchema; }
    public StateSchema getNewSchema() { return newSchema; }
    public void setNewSchema(StateSchema newSchema) { this.newSchema = newSchema; }
    public String getBoundRulePackageVersion() { return boundRulePackageVersion; }
    public void setBoundRulePackageVersion(String v) { this.boundRulePackageVersion = v; }
    public String getFieldMappingsJson() { return fieldMappingsJson; }
    public void setFieldMappingsJson(String fieldMappingsJson) { this.fieldMappingsJson = fieldMappingsJson; }
    public String getConservationJson() { return conservationJson; }
    public void setConservationJson(String conservationJson) { this.conservationJson = conservationJson; }
    public String getDependenciesJson() { return dependenciesJson; }
    public void setDependenciesJson(String dependenciesJson) { this.dependenciesJson = dependenciesJson; }
    public String getConvertibleFieldsJson() { return convertibleFieldsJson; }
    public void setConvertibleFieldsJson(String convertibleFieldsJson) { this.convertibleFieldsJson = convertibleFieldsJson; }
    public String getMissingIntermediatesJson() { return missingIntermediatesJson; }
    public void setMissingIntermediatesJson(String missingIntermediatesJson) { this.missingIntermediatesJson = missingIntermediatesJson; }
    public ProofVerdict getVerdict() { return verdict; }
    public void setVerdict(ProofVerdict verdict) { this.verdict = verdict; }
    public ProofStatus getStatus() { return status; }
    public void setStatus(ProofStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
