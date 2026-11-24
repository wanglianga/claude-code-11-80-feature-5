package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.InterventionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterventionRecordRepo extends JpaRepository<InterventionRecord, Long> {
    List<InterventionRecord> findByStreamIdOrderByIdAsc(Long streamId);
    List<InterventionRecord> findByStreamIdAndRoadIdOrderByAtAsc(Long streamId, String roadId);
    List<InterventionRecord> findByStreamIdAndPartitionNoOrderByAtAsc(Long streamId, int partitionNo);
}
