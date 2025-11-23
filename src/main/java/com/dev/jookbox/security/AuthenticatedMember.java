package com.dev.jookbox.security;

import com.dev.jookbox.domain.Role;

import java.util.Set;
import java.util.UUID;

public record AuthenticatedMember(
        UUID membershipId,
        UUID userId,
        UUID roomId,
        String roomCode,
        Role role,
        Set<String> capabilities
) {
}
