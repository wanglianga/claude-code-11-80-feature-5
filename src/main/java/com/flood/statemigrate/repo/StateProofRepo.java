package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.ProofStatus;
import com.flood.statemigrate.domain.StateProof;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StateProofRepo extends JpaRepository<StateProof, Long> {
    List<StateProof> findByStreamIdOrderByCreatedAtDesc(Long streamId);
    List<StateProof> findByStreamIdAndBoundRulePackageVersionAndStatus(Long streamId, String version, ProofStatus status);
    Optional<StateProof> findFirstByStreamIdAndStatusOrderByCreatedAtDesc(Long streamId, ProofStatus status);
    List<StateProof> findByStreamIdAndStatus(Long streamId, ProofStatus status);
}
