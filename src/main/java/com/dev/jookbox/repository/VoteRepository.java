package com.dev.jookbox.repository;

import com.dev.jookbox.domain.QueueItem;
import com.dev.jookbox.domain.Room;
import com.dev.jookbox.domain.User;
import com.dev.jookbox.domain.Vote;
import com.dev.jookbox.domain.VoteType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface VoteRepository extends JpaRepository<Vote, UUID> {
    boolean existsByQueueItemAndUserAndType(QueueItem queueItem, User user, VoteType type);

    /**
     * Votes for an item cast by users who are still current members of the room.
     * Votes from members who have left must not count toward the threshold.
     */
    @Query("""
            select count(v) from Vote v
            where v.queueItem = :item and v.type = :type
              and exists (
                  select 1 from Membership m
                  where m.user = v.user and m.room = :room and m.leftAt is null
              )
            """)
    long countCurrentMemberVotes(@Param("item") QueueItem item,
                                 @Param("type") VoteType type,
                                 @Param("room") Room room);
}
