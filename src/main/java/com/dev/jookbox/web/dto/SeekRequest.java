package com.dev.jookbox.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SeekRequest(
        @Min(0) int positionMs,
        // The item the client believes is playing. Rejected with 409 on mismatch
        // so a stale client can't reposition whatever is actually playing.
        @NotNull UUID queueItemId
) {
}
