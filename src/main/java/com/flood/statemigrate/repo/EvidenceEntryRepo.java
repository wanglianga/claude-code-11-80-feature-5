package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.EvidenceEntry;
import com.flood.statemigrate.domain.EvidenceKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvidenceEntryRepo extends JpaRepository<EvidenceEntry, Long> {
    List<EvidenceEntry> findByStreamIdOrderByIdAsc(Long streamId);
    List<EvidenceEntry> findByStreamIdAndPartitionNoOrderByIdAsc(Long streamId, int partitionNo);
    List<EvidenceEntry> findByStreamIdAndKind(Long streamId, EvidenceKind kind);
    List<EvidenceEntry> findByStreamIdAndPartitionNoAndKind(Long streamId, int partitionNo, EvidenceKind kind);
}
