package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.FieldObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FieldObservationRepo extends JpaRepository<FieldObservation, Long> {
    List<FieldObservation> findByStreamIdOrderByIdAsc(Long streamId);
    List<FieldObservation> findByStreamIdAndPartitionNoOrderByEventTimeAsc(Long streamId, int partitionNo);
    List<FieldObservation> findByStreamIdAndRoadIdOrderByEventTimeAsc(Long streamId, String roadId);
}
