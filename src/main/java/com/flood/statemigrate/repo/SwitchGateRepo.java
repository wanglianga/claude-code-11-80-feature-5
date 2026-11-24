package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.SwitchGate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SwitchGateRepo extends JpaRepository<SwitchGate, Long> {
    Optional<SwitchGate> findByStreamId(Long streamId);
}
