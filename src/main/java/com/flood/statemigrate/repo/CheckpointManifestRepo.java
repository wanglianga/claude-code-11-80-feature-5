package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.CheckpointManifest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CheckpointManifestRepo extends JpaRepository<CheckpointManifest, Long> {
    List<CheckpointManifest> findByStreamIdOrderByPartitionNoAscCreatedAtDesc(Long streamId);

    Optional<CheckpointManifest> findFirstByStreamIdAndPartitionNoOrderByConsumeOffsetDescIdDesc(
            Long streamId, int partitionNo);

    List<CheckpointManifest> findByStreamIdAndPartitionNoOrderByConsumeOffsetDesc(Long streamId, int partitionNo);
}
