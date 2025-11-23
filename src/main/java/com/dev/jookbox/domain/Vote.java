package com.dev.jookbox.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "votes",
        uniqueConstraints = @UniqueConstraint(name = "uq_vote_per_user", columnNames = {"queue_item_id", "user_id", "type"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Vote {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "queue_item_id")
    private QueueItem queueItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VoteType type;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
