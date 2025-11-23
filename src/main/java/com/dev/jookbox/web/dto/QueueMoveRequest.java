package com.dev.jookbox.web.dto;

import jakarta.validation.constraints.Min;

public record QueueMoveRequest(
        @Min(0) int newPosition
) {
}
