package com.dev.jookbox.repository;

import com.dev.jookbox.domain.QueueItem;
import com.dev.jookbox.domain.Room;
import com.dev.jookbox.domain.QueueItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QueueItemRepository extends JpaRepository<QueueItem, UUID> {

    List<QueueItem> findByRoomOrderByPosition(Room room);

    Optional<QueueItem> findFirstByRoomOrderByPosition(Room room);

    @Modifying
    @Query("update QueueItem qi set qi.position = qi.position + :delta where qi.room = :room and qi.position >= :start")
    int shiftPositions(@Param("room") Room room, @Param("start") int start, @Param("delta") int delta);

    long countByRoomAndStatus(Room room, QueueItemStatus status);
}
