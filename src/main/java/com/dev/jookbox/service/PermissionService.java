package com.dev.jookbox.service;

import com.dev.jookbox.domain.Capability;
import com.dev.jookbox.domain.Role;
import com.dev.jookbox.repository.MembershipRepository;
import com.dev.jookbox.repository.RoomRepository;
import com.dev.jookbox.security.AuthenticatedMember;
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
    private final RoomRepository roomRepository;

    public PermissionService(MembershipRepository membershipRepository, RoomRepository roomRepository) {
        this.membershipRepository = membershipRepository;
        this.roomRepository = roomRepository;
    }

    @Transactional
    public Set<String> updateCapabilities(String roomCode, UUID membershipId, PermissionUpdateRequest request, AuthenticatedMember actor) {
        var room = roomRepository.findByCode(roomCode)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        if (actor.role() != Role.HOST || !actor.roomId().equals(room.getId())) {
            throw new ForbiddenOperationException("Only the host can update permissions");
        }
        var membership = membershipRepository.findById(membershipId)
                .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));
        Set<Capability> caps = request.capabilities().stream()
                .map(String::toUpperCase)
                .map(Capability::valueOf)
                .collect(Collectors.toSet());
        membership.setCapabilities(Capability.toMask(caps));
        membershipRepository.save(membership);
        return caps.stream().map(Enum::name).collect(Collectors.toSet());
    }
}
