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
@Table(name = "queue_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id")
    private Room room;

    @Column(nullable = false)
    private int position;

    @Column(name = "video_id", nullable = false, length = 64)
    private String videoId;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "thumb_url", length = 500)
    private String thumbUrl;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "added_by")
    private User addedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QueueItemStatus status;

    @Column(name = "enqueued_at", nullable = false)
    private OffsetDateTime enqueuedAt;
}
