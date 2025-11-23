package com.dev.jookbox.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PlayRequest(
        @NotNull UUID queueItemId,
        @Min(0) int positionMs
) {
}
