package com.dev.jookbox.service;

import com.dev.jookbox.domain.Capability;
import com.dev.jookbox.domain.PlaybackState;
import com.dev.jookbox.domain.QueueItem;
import com.dev.jookbox.domain.QueueItemStatus;
import com.dev.jookbox.domain.Room;
import com.dev.jookbox.repository.QueueItemRepository;
import com.dev.jookbox.repository.RoomRepository;
import com.dev.jookbox.security.AuthenticatedMember;
import com.dev.jookbox.web.ConflictException;
import com.dev.jookbox.web.ResourceNotFoundException;
import com.dev.jookbox.web.dto.PlaybackStateResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PlaybackService {

    private final RoomRepository roomRepository;
    private final QueueItemRepository queueItemRepository;
    private final RoomAccessGuard roomAccessGuard;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final QueueService queueService;

    public PlaybackService(RoomRepository roomRepository,
                           QueueItemRepository queueItemRepository,
                           RoomAccessGuard roomAccessGuard,
                           StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper,
                           SimpMessagingTemplate messagingTemplate,
                           QueueService queueService) {
        this.roomRepository = roomRepository;
        this.queueItemRepository = queueItemRepository;
        this.roomAccessGuard = roomAccessGuard;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
        this.queueService = queueService;
    }

    public Optional<PlaybackStateResponse> getState(String roomCode) {
        return readState(roomCode).map(this::toResponse);
    }

    /** Raw state including the room-internal queue item id — for service callers. */
    Optional<PlaybackState> readCurrentState(String roomCode) {
        return readState(roomCode);
    }

    @Transactional
    public PlaybackStateResponse play(String roomCode, UUID queueItemId, AuthenticatedMember member, int positionMs) {
        roomAccessGuard.requireCapability(roomCode, member, Capability.PLAYBACK_CONTROL);
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
        roomAccessGuard.requireCapability(roomCode, member, Capability.PLAYBACK_CONTROL);
        PlaybackState current = readState(roomCode)
                .orElseThrow(() -> new ResourceNotFoundException("Playback state not found"));
        Instant now = Instant.now();
        int positionMs = current.getPositionMs();
        if (current.isPlaying() && current.getLastUpdateTs() != null) {
            positionMs += (int) Duration.between(current.getLastUpdateTs(), now).toMillis();
        }
        PlaybackState next = PlaybackState.builder()
                .roomId(current.getRoomId())
                .nowPlayingQueueItemId(current.getNowPlayingQueueItemId())
                .positionMs(positionMs)
                .playing(false)
                .lastUpdateTs(now)
                .build();
        writeState(roomCode, next);
        broadcast(roomCode, next);
        return toResponse(next);
    }

    @Transactional
    public PlaybackStateResponse resume(String roomCode, AuthenticatedMember member) {
        roomAccessGuard.requireCapability(roomCode, member, Capability.PLAYBACK_CONTROL);
        PlaybackState current = readState(roomCode)
                .orElseThrow(() -> new ResourceNotFoundException("Playback state not found"));
        if (current.getNowPlayingQueueItemId() == null) {
            throw new ResourceNotFoundException("No current item to resume");
        }
        if (current.isPlaying()) {
            return toResponse(current);
        }
        PlaybackState next = PlaybackState.builder()
                .roomId(current.getRoomId())
                .nowPlayingQueueItemId(current.getNowPlayingQueueItemId())
                .positionMs(current.getPositionMs())
                .playing(true)
                .lastUpdateTs(Instant.now())
                .build();
        writeState(roomCode, next);
        broadcast(roomCode, next);
        return toResponse(next);
    }

    @Transactional
    public PlaybackStateResponse seek(String roomCode, int positionMs, UUID queueItemId, AuthenticatedMember member) {
        roomAccessGuard.requireCapability(roomCode, member, Capability.PLAYBACK_CONTROL);
        PlaybackState current = readState(roomCode)
                .orElseThrow(() -> new ResourceNotFoundException("Playback state not found"));
        // The client computes positionMs against the item IT thinks is playing
        // (it clamps to that item's duration). A stale client that missed a
        // track change must not reposition the track actually playing.
        if (!queueItemId.equals(current.getNowPlayingQueueItemId())) {
            throw new ConflictException("Playback has moved to another track");
        }
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

    @Transactional
    public PlaybackStateResponse next(String roomCode, UUID currentQueueItemId, AuthenticatedMember member) {
        roomAccessGuard.requireCapability(roomCode, member, Capability.PLAYBACK_CONTROL);
        return advance(roomCode, currentQueueItemId, QueueItemStatus.PLAYED);
    }

    /**
     * Advances the room past {@code currentQueueItemId}: marks it with the given
     * terminal status, reindexes the queue, starts the next QUEUED item (or
     * stops if none), and broadcasts both playback and queue. Callers are
     * responsible for authorization. Also used by the vote-skip/remove path so a
     * passed vote on the now-playing track doesn't leave playback pointing at it.
     */
    @Transactional
    public PlaybackStateResponse advance(String roomCode, UUID currentQueueItemId, QueueItemStatus terminalStatus) {
        Room room = roomRepository.findByCode(roomCode)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));

        PlaybackState current = readState(roomCode).orElse(null);
        UUID nowPlaying = current != null ? current.getNowPlayingQueueItemId() : null;
        // Idempotency guard: if the state has already advanced past the caller's
        // current item, do nothing and return the state unchanged.
        if (nowPlaying == null || !nowPlaying.equals(currentQueueItemId)) {
            return current != null ? toResponse(current) : null;
        }

        // Mark the finished item (preserves history) and drop it from the queue.
        queueItemRepository.findById(currentQueueItemId).ifPresent(finished -> {
            finished.setStatus(terminalStatus);
            finished.setPosition(-1);
            queueItemRepository.save(finished);
        });

        List<QueueItem> remaining = queueService.reindexActiveExcluding(room, currentQueueItemId);

        QueueItem nextItem = remaining.stream()
                .filter(q -> q.getStatus() == QueueItemStatus.QUEUED)
                .findFirst()
                .orElse(null);

        PlaybackState nextState = PlaybackState.builder()
                .roomId(room.getId())
                .nowPlayingQueueItemId(nextItem != null ? nextItem.getId() : null)
                .positionMs(0)
                .playing(nextItem != null)
                .lastUpdateTs(Instant.now())
                .build();
        writeState(roomCode, nextState);
        broadcast(roomCode, nextState);
        queueService.broadcastQueue(roomCode);
        return toResponse(nextState);
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
                state.getLastUpdateTs(),
                Instant.now()
        );
    }

    private void broadcast(String roomCode, PlaybackState state) {
        messagingTemplate.convertAndSend("/topic/rooms/" + roomCode + "/playback", toResponse(state));
    }
}
