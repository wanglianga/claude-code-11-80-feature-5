package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 规则包：固定阈值（旧）或多因子（新，含上游水位趋势/泵站启停滞后/传感器可信度权重）。
 * configJson 示例（多因子）：
 * {"medium":25,"high":55,"wRain":0.6,"wTrend":1.0,"wLag":0.15,"wDepth":0.2,"credibilityFloor":0.5}
 */
@Entity
@Table(name = "rule_package", uniqueConstraints = @UniqueConstraint(columnNames = {"streamId", "version"}))
public class RulePackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    @Column(nullable = false, length = 32)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RuleKind kind;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String configJson;

    /** ACTIVE=当前可用；SUPERSEDED=被二次修订取代 */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public RuleKind getKind() { return kind; }
    public void setKind(RuleKind kind) { this.kind = kind; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
