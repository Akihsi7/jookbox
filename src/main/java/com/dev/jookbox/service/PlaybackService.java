package com.dev.jookbox.service;

import com.dev.jookbox.domain.PlaybackState;
import com.dev.jookbox.domain.QueueItem;
import com.dev.jookbox.repository.QueueItemRepository;
import com.dev.jookbox.repository.RoomRepository;
import com.dev.jookbox.security.AuthenticatedMember;
import com.dev.jookbox.web.ForbiddenOperationException;
import com.dev.jookbox.web.ResourceNotFoundException;
import com.dev.jookbox.web.dto.PlaybackStateResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class PlaybackService {

    private final RoomRepository roomRepository;
    private final QueueItemRepository queueItemRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    public PlaybackService(RoomRepository roomRepository,
                           QueueItemRepository queueItemRepository,
                           StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper,
                           SimpMessagingTemplate messagingTemplate) {
        this.roomRepository = roomRepository;
        this.queueItemRepository = queueItemRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
    }

    public Optional<PlaybackStateResponse> getState(String roomCode) {
        return readState(roomCode).map(this::toResponse);
    }

    @Transactional
    public PlaybackStateResponse play(String roomCode, UUID queueItemId, AuthenticatedMember member, int positionMs) {
        verifyPlaybackPermission(roomCode, member);
        ensureRoomExists(roomCode);
        QueueItem item = queueItemRepository.findById(queueItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Queue item not found"));
        if (!item.getRoom().getCode().equals(roomCode)) {
            throw new ResourceNotFoundException("Item not in room");
        }
        PlaybackState state = PlaybackState.builder()
                .roomId(item.getRoom().getId())
                .nowPlayingQueueItemId(item.getId())
                .positionMs(positionMs)
                .playing(true)
                .lastUpdateTs(Instant.now())
                .build();
        writeState(roomCode, state);
        broadcast(roomCode, state);
        return toResponse(state);
    }

    @Transactional
    public PlaybackStateResponse pause(String roomCode, AuthenticatedMember member) {
        verifyPlaybackPermission(roomCode, member);
        PlaybackState current = readState(roomCode)
                .orElseThrow(() -> new ResourceNotFoundException("Playback state not found"));
        PlaybackState next = PlaybackState.builder()
                .roomId(current.getRoomId())
                .nowPlayingQueueItemId(current.getNowPlayingQueueItemId())
                .positionMs(current.getPositionMs())
                .playing(false)
                .lastUpdateTs(Instant.now())
                .build();
        writeState(roomCode, next);
        broadcast(roomCode, next);
        return toResponse(next);
    }

    @Transactional
    public PlaybackStateResponse seek(String roomCode, int positionMs, AuthenticatedMember member) {
        verifyPlaybackPermission(roomCode, member);
        PlaybackState current = readState(roomCode)
                .orElseThrow(() -> new ResourceNotFoundException("Playback state not found"));
        PlaybackState next = PlaybackState.builder()
                .roomId(current.getRoomId())
                .nowPlayingQueueItemId(current.getNowPlayingQueueItemId())
                .positionMs(positionMs)
                .playing(current.isPlaying())
                .lastUpdateTs(Instant.now()) 
                .build();
        writeState(roomCode, next);
        broadcast(roomCode, next);
        return toResponse(next);
    }

    private void ensureRoomExists(String roomCode) {
        roomRepository.findByCode(roomCode).orElseThrow(() -> new ResourceNotFoundException("Room not found"));
    }

    private void verifyPlaybackPermission(String roomCode, AuthenticatedMember member) {
        if (!member.roomCode().equals(roomCode)) {
            throw new ForbiddenOperationException("Membership not associated with this room");
        }
        if (!member.capabilities().contains("PLAYBACK_CONTROL")) {
            throw new ForbiddenOperationException("You do not have playback control permissions");
        }
    }

    private void writeState(String roomCode, PlaybackState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(playbackKey(roomCode), json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize playback state", e);
        }
    }

    private Optional<PlaybackState> readState(String roomCode) {
        String json = redisTemplate.opsForValue().get(playbackKey(roomCode));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, PlaybackState.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private String playbackKey(String roomCode) {
        return "playback:" + roomCode;
    }

    private PlaybackStateResponse toResponse(PlaybackState state) {
        return new PlaybackStateResponse(
                state.getNowPlayingQueueItemId(),
                state.getPositionMs(),
                state.isPlaying(),
                state.getLastUpdateTs()
        );
    }

    private void broadcast(String roomCode, PlaybackState state) {
        messagingTemplate.convertAndSend("/topic/rooms/" + roomCode + "/playback", toResponse(state));
    }
}
