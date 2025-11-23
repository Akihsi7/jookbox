package com.dev.jookbox.repository;

import com.dev.jookbox.domain.QueueItem;
import com.dev.jookbox.domain.User;
import com.dev.jookbox.domain.Vote;
import com.dev.jookbox.domain.VoteType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VoteRepository extends JpaRepository<Vote, UUID> {
    long countByQueueItemAndType(QueueItem queueItem, VoteType type);
    boolean existsByQueueItemAndUserAndType(QueueItem queueItem, User user, VoteType type);
}
