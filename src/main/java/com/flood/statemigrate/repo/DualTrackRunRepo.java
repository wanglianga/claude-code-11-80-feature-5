package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.DualTrackRun;
import com.flood.statemigrate.domain.DualTrackStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DualTrackRunRepo extends JpaRepository<DualTrackRun, Long> {
    List<DualTrackRun> findByStreamIdOrderByIdAsc(Long streamId);
    Optional<DualTrackRun> findFirstByStreamIdAndPartitionNoOrderByIdDesc(Long streamId, int partitionNo);
    List<DualTrackRun> findByStreamIdAndStatus(Long streamId, DualTrackStatus status);
    List<DualTrackRun> findByStreamIdAndNewRuleVersionAndStatus(Long streamId, String version, DualTrackStatus status);
}
