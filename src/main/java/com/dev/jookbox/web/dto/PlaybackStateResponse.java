package com.dev.jookbox.web.dto;

import java.time.Instant;
import java.util.UUID;

public record PlaybackStateResponse(
        UUID nowPlayingQueueItemId,
        int positionMs,
        boolean playing,
        Instant lastUpdateTs
) {
}
