package com.dev.jookbox.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record QueueAddRequest(
        @NotBlank String videoId,
        @NotBlank String title,
        @Min(1) int durationSeconds,
        String thumbUrl
) {
}
