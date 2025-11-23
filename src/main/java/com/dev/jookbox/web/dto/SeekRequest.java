package com.dev.jookbox.web.dto;

import jakarta.validation.constraints.Min;

public record SeekRequest(
        @Min(0) int positionMs
) {
}
