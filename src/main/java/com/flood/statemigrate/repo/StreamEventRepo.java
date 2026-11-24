package com.flood.statemigrate.repo;

import com.flood.statemigrate.domain.StreamEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StreamEventRepo extends JpaRepository<StreamEvent, Long> {

    List<StreamEvent> findByStreamIdAndPartitionNoAndEventOffsetBetweenOrderByEventOffsetAsc(
            Long streamId, int partitionNo, long from, long to);

    List<StreamEvent> findByStreamIdAndPartitionNoAndEventOffsetLessThanEqualOrderByEventOffsetAsc(
            Long streamId, int partitionNo, long to);

    List<StreamEvent> findByStreamIdAndPartitionNoAndEventTimeBetweenOrderByEventTimeAsc(
            Long streamId, int partitionNo, Instant from, Instant to);

    @Query("select max(e.eventOffset) from StreamEvent e where e.streamId=:streamId and e.partitionNo=:p")
    Optional<Long> maxOffset(Long streamId, int p);

    @Query("select min(e.eventTime) from StreamEvent e where e.streamId=:streamId and e.partitionNo=:p")
    Optional<Instant> earliestEventTime(Long streamId, int p);

    List<StreamEvent> findByStreamIdOrderByPartitionNoAscEventOffsetAsc(Long streamId);
}
