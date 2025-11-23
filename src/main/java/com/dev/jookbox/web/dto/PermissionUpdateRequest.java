package com.dev.jookbox.web.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record PermissionUpdateRequest(
        @NotEmpty Set<String> capabilities
) {
}
