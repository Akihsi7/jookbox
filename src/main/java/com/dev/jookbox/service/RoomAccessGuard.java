package com.dev.jookbox.service;

import com.dev.jookbox.domain.Capability;
import com.dev.jookbox.domain.Membership;
import com.dev.jookbox.domain.Role;
import com.dev.jookbox.domain.Room;
import com.dev.jookbox.repository.MembershipRepository;
import com.dev.jookbox.repository.RoomRepository;
import com.dev.jookbox.security.AuthenticatedMember;
import com.dev.jookbox.web.ForbiddenOperationException;
import com.dev.jookbox.web.ResourceNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Single place for the room/membership checks every mutating operation needs.
 * Capabilities are verified against the DB, not the JWT claims: a token stays
 * valid (and its capability claims frozen) until expiry, so JWT-based checks
 * would ignore grants and revocations made through the permissions endpoint.
 */
@Component
public class RoomAccessGuard {

    private final RoomRepository roomRepository;
    private final MembershipRepository membershipRepository;

    public RoomAccessGuard(RoomRepository roomRepository, MembershipRepository membershipRepository) {
        this.roomRepository = roomRepository;
        this.membershipRepository = membershipRepository;
    }

    public record RoomAccess(Room room, Membership membership) {
    }

    /**
     * Resolves the room and verifies the caller is a current (not departed)
     * member of it. A departed member's JWT stays valid until expiry, so the
     * DB membership status is the source of truth.
     */
    public RoomAccess requireActiveMember(String roomCode, AuthenticatedMember member) {
        Room room = roomRepository.findByCode(roomCode)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        Membership membership = membershipRepository.findById(member.membershipId())
                .orElseThrow(() -> new ForbiddenOperationException("Membership not found"));
        if (!membership.getRoom().getId().equals(room.getId())) {
            throw new ForbiddenOperationException("Membership not associated with this room");
        }
        if (membership.getLeftAt() != null) {
            throw new ForbiddenOperationException("You have left this room");
        }
        return new RoomAccess(room, membership);
    }

    /**
     * requireActiveMember plus a capability check. The host implicitly holds
     * every capability.
     */
    public RoomAccess requireCapability(String roomCode, AuthenticatedMember member, Capability capability) {
        RoomAccess access = requireActiveMember(roomCode, member);
        Membership membership = access.membership();
        if (membership.getRole() != Role.HOST && !membership.hasCapability(capability)) {
            throw new ForbiddenOperationException("You do not have the " + capability.name() + " permission");
        }
        return access;
    }
}
