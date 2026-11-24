package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.RollbackRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RollbackRecordRepo extends JpaRepository<RollbackRecord, Long> {
    List<RollbackRecord> findByStreamIdOrderByIdDesc(Long streamId);
}
