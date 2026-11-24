package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.DualTrackDiff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DualTrackDiffRepo extends JpaRepository<DualTrackDiff, Long> {
    List<DualTrackDiff> findByRunId(Long runId);
    List<DualTrackDiff> findByStreamIdAndPartitionNo(Long streamId, int partitionNo);
}
