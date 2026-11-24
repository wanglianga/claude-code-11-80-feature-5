package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.RulePackage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RulePackageRepo extends JpaRepository<RulePackage, Long> {
    List<RulePackage> findByStreamIdOrderByCreatedAtAsc(Long streamId);
    Optional<RulePackage> findByStreamIdAndVersion(Long streamId, String version);
}
