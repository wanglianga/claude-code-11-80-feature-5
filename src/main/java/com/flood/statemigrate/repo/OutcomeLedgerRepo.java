package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.OutcomeLedgerEntry;
import com.flood.statemigrate.domain.OutcomeStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OutcomeLedgerRepo extends JpaRepository<OutcomeLedgerEntry, Long> {
    List<OutcomeLedgerEntry> findByStreamIdOrderByIdAsc(Long streamId);
    List<OutcomeLedgerEntry> findByStreamIdAndPartitionNoOrderByIdAsc(Long streamId, int partitionNo);
    List<OutcomeLedgerEntry> findByStreamIdAndStageOrderByIdAsc(Long streamId, OutcomeStage stage);
    Optional<OutcomeLedgerEntry> findFirstByStreamIdAndPartitionNoAndExposureGroupOrderByOutputTimeAsc(
            Long streamId, int partitionNo, String exposureGroup);
    List<OutcomeLedgerEntry> findByStreamIdAndExposureGroupOrderByOutputTimeAsc(Long streamId, String exposureGroup);
}
