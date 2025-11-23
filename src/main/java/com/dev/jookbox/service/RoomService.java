package com.dev.jookbox.service;

import com.dev.jookbox.domain.*;
import com.dev.jookbox.repository.MembershipRepository;
import com.dev.jookbox.repository.RoomRepository;
import com.dev.jookbox.repository.UserRepository;
import com.dev.jookbox.security.JwtService;
import com.dev.jookbox.web.BadRequestException;
import com.dev.jookbox.web.ResourceNotFoundException;
import com.dev.jookbox.web.dto.JoinRoomRequest;
import com.dev.jookbox.web.dto.MembershipTokenResponse;
import com.dev.jookbox.web.dto.RoomCreationRequest;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

@Service
public class RoomService {

    private static final String ROOM_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int ROOM_CODE_LENGTH = 6;

    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final JwtService jwtService;
    private final SecureRandom secureRandom = new SecureRandom();

    public RoomService(RoomRepository roomRepository,
                       UserRepository userRepository,
                       MembershipRepository membershipRepository,
                       JwtService jwtService) {
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.jwtService = jwtService;
    }

    @Transactional
    public MembershipTokenResponse createRoom(RoomCreationRequest request) {
        OffsetDateTime now = OffsetDateTime.now();
        User host = userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .displayName(request.hostDisplayName())
                .createdAt(now)
                .build());

        Room room = roomRepository.save(Room.builder()
                .id(UUID.randomUUID())
                .code(generateUniqueCode())
                .host(host)
                .status(RoomStatus.ACTIVE)
                .createdAt(now)
                .build());

        Membership membership = membershipRepository.save(Membership.builder()
                .id(UUID.randomUUID())
                .room(room)
                .user(host)
                .role(Role.HOST)
                .capabilities(Capability.toMask(Set.of(
                        Capability.PLAYBACK_CONTROL,
                        Capability.REORDER_QUEUE,
                        Capability.REMOVE_ITEMS,
                        Capability.SKIP_OVERRIDE)))
                .joinedAt(now)
                .build());

        String token = jwtService.generateToken(membership);
        return new MembershipTokenResponse(room.getCode(), token, membership.getRole(),
                Capability.fromMask(membership.getCapabilities()).stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()));
    }

    @Transactional
    public MembershipTokenResponse joinRoom(String roomCode, JoinRoomRequest request) {
        Room room = roomRepository.findByCode(roomCode)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        if (room.getStatus() != RoomStatus.ACTIVE) {
            throw new BadRequestException("Room is not active");
        }
        long currentMembers = membershipRepository.countByRoom(room);
        if (currentMembers >= 10) {
            throw new BadRequestException("Room is full");
        }
        OffsetDateTime now = OffsetDateTime.now();
        User user = userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .displayName(request.displayName())
                .createdAt(now)
                .build());
        Membership membership = membershipRepository.save(Membership.builder()
                .id(UUID.randomUUID())
                .room(room)
                .user(user)
                .role(Role.GUEST)
                .capabilities(Capability.toMask(Set.of()))
                .joinedAt(now)
                .build());

        String token = jwtService.generateToken(membership);
        return new MembershipTokenResponse(room.getCode(), token, membership.getRole(), Set.of());
    }

    private String generateUniqueCode() {
        String code;
        do {
            code = randomCode();
        } while (roomRepository.existsByCode(code));
        return code;
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(ROOM_CODE_LENGTH);
        for (int i = 0; i < ROOM_CODE_LENGTH; i++) {
            int idx = secureRandom.nextInt(ROOM_CODE_CHARS.length());
            sb.append(ROOM_CODE_CHARS.charAt(idx));
        }
        return sb.toString();
    }
}
