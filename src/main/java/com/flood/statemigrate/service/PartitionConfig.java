package com.flood.statemigrate.service;

/** 分区→道路/泵站/河段映射，key=关键分区；basin/roadType 用于分层评估与选择偏差检查 */
public record PartitionConfig(int partitionNo, String roadId, String pumpId, String reachId, boolean key,
                              String roadType, String basin) {

    public static final String DEFAULT_ROAD_TYPE = "LOCAL";
    public static final String DEFAULT_BASIN = "BASIN-DEFAULT";
}
