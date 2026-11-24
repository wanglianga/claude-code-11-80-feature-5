package com.flood.statemigrate.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 原始流事件：雨量/水位/泵站启停/设备健康度/人工上报与视频识别。
 * payloadJson 按类型：
 *  RAIN {"mm":4.5}  WATER_LEVEL {"levelM":3.2}  PUMP {"on":true}
 *  DEVICE_HEALTH {"score":0.92}  ROAD_REPORT|VIDEO {"depthCm":35}
 */
@Entity
@Table(name = "stream_event", uniqueConstraints = @UniqueConstraint(columnNames = {"streamId", "partitionNo", "eventOffset"}))
public class StreamEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long streamId;

    @Column(nullable = false)
    private int partitionNo;

    @Column(nullable = false)
    private long eventOffset;

    @Column(nullable = false)
    private Instant eventTime;

    @Column(nullable = false, length = 24)
    private String type;

    @Column(length = 64)
    private String roadId;

    @Column(length = 64)
    private String pumpId;

    @Column(length = 64)
    private String reachId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStreamId() { return streamId; }
    public void setStreamId(Long streamId) { this.streamId = streamId; }
    public int getPartitionNo() { return partitionNo; }
    public void setPartitionNo(int partitionNo) { this.partitionNo = partitionNo; }
    public long getEventOffset() { return eventOffset; }
    public void setEventOffset(long eventOffset) { this.eventOffset = eventOffset; }
    public Instant getEventTime() { return eventTime; }
    public void setEventTime(Instant eventTime) { this.eventTime = eventTime; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getRoadId() { return roadId; }
    public void setRoadId(String roadId) { this.roadId = roadId; }
    public String getPumpId() { return pumpId; }
    public void setPumpId(String pumpId) { this.pumpId = pumpId; }
    public String getReachId() { return reachId; }
    public void setReachId(String reachId) { this.reachId = reachId; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
}
