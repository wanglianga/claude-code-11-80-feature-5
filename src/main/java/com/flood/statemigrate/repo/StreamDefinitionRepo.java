package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.StreamDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StreamDefinitionRepo extends JpaRepository<StreamDefinition, Long> {
    Optional<StreamDefinition> findByStreamCode(String streamCode);
}
