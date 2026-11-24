package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.MigrationPlan;
import com.flood.statemigrate.domain.PlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MigrationPlanRepo extends JpaRepository<MigrationPlan, Long> {
    List<MigrationPlan> findByStreamIdOrderByPartitionNoAsc(Long streamId);
    Optional<MigrationPlan> findFirstByStreamIdAndPartitionNoOrderByIdDesc(Long streamId, int partitionNo);
    List<MigrationPlan> findByStreamIdAndStatus(Long streamId, PlanStatus status);
}
