package com.dev.jookbox.service;

import com.dev.jookbox.domain.Capability;
import com.dev.jookbox.domain.Role;
import com.dev.jookbox.repository.MembershipRepository;
import com.dev.jookbox.security.AuthenticatedMember;
import com.dev.jookbox.web.BadRequestException;
import com.dev.jookbox.web.ForbiddenOperationException;
import com.dev.jookbox.web.ResourceNotFoundException;
import com.dev.jookbox.web.dto.PermissionUpdateRequest;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PermissionService {

    private final MembershipRepository membershipRepository;
    private final RoomAccessGuard roomAccessGuard;

    public PermissionService(MembershipRepository membershipRepository, RoomAccessGuard roomAccessGuard) {
        this.membershipRepository = membershipRepository;
        this.roomAccessGuard = roomAccessGuard;
    }

    @Transactional
    public Set<String> updateCapabilities(String roomCode, UUID membershipId, PermissionUpdateRequest request, AuthenticatedMember actor) {
        RoomAccessGuard.RoomAccess access = roomAccessGuard.requireActiveMember(roomCode, actor);
        if (access.membership().getRole() != Role.HOST) {
            throw new ForbiddenOperationException("Only the host can update permissions");
        }
        var membership = membershipRepository.findById(membershipId)
                // Same error for "does not exist" and "belongs to another room" so
                // membership ids can't be probed across rooms.
                .filter(m -> m.getRoom().getId().equals(access.room().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Membership not found in this room"));
        Set<Capability> caps = request.capabilities().stream()
                .map(this::parseCapability)
                .collect(Collectors.toSet());
        membership.setCapabilities(Capability.toMask(caps));
        membershipRepository.save(membership);
        return caps.stream().map(Enum::name).collect(Collectors.toSet());
    }

    private Capability parseCapability(String name) {
        try {
            return Capability.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown capability: " + name);
        }
    }
}
