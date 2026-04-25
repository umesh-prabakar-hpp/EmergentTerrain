package com.emergentterrain.repo;

import com.emergentterrain.domain.GovernmentTimelineSample;
import com.emergentterrain.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GovernmentTimelineSampleRepository extends JpaRepository<GovernmentTimelineSample, UUID> {
    Optional<GovernmentTimelineSample> findTopByRoomOrderByTickDesc(Room room);
    Optional<GovernmentTimelineSample> findByRoomAndBucketMinutesAndBucketStartAt(Room room, int bucketMinutes, Instant bucketStartAt);
    List<GovernmentTimelineSample> findByRoomAndBucketStartAtGreaterThanEqualOrderByBucketStartAtAscBucketMinutesAsc(Room room, Instant bucketStartAt);
    List<GovernmentTimelineSample> findByBucketMinutesAndBucketStartAtLessThanOrderByRoomIdAscBucketStartAtAsc(int bucketMinutes, Instant bucketStartAt);
}
