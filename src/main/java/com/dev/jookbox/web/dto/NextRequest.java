package com.dev.jookbox.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record NextRequest(
        @NotNull UUID currentQueueItemId
) {
}
