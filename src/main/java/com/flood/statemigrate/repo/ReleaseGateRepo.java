package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.ReleaseGate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReleaseGateRepo extends JpaRepository<ReleaseGate, Long> {
    List<ReleaseGate> findByStreamIdOrderByIdDesc(Long streamId);
    Optional<ReleaseGate> findFirstByStreamIdOrderByIdDesc(Long streamId);
}
