package com.dev.jookbox.web.dto;

import com.dev.jookbox.domain.QueueItemStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record QueueItemView(
        UUID id,
        String videoId,
        String title,
        int durationSeconds,
        String thumbUrl,
        int position,
        QueueItemStatus status,
        OffsetDateTime enqueuedAt,
        String addedBy
) {
}
