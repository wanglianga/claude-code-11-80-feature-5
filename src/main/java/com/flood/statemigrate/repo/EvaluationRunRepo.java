package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.EvaluationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EvaluationRunRepo extends JpaRepository<EvaluationRun, Long> {
    List<EvaluationRun> findByStreamIdOrderByIdDesc(Long streamId);
    Optional<EvaluationRun> findFirstByStreamIdOrderByIdDesc(Long streamId);
}
