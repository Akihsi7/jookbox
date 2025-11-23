package com.dev.jookbox.web.dto;

import jakarta.validation.constraints.NotBlank;

public record JoinRoomRequest(
        @NotBlank String displayName
) {
}
