package com.dev.jookbox.web.dto;

import com.dev.jookbox.domain.Role;

import java.util.Set;

public record MembershipTokenResponse(
        String roomCode,
        String token,
        Role role,
        Set<String> capabilities
) {
}
