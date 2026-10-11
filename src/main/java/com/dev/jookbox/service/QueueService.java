package com.dev.jookbox.service;

import com.dev.jookbox.domain.*;
import com.dev.jookbox.repository.QueueItemRepository;
import com.dev.jookbox.repository.RoomRepository;
import com.dev.jookbox.security.AuthenticatedMember;
import com.dev.jookbox.web.BadRequestException;
import com.dev.jookbox.web.ResourceNotFoundException;
import com.dev.jookbox.web.dto.QueueAddRequest;
import com.dev.jookbox.web.dto.QueueItemView;
import com.dev.jookbox.web.dto.QueueResponse;
import com.dev.jookbox.web.dto.QueueMoveRequest;
import jakarta.transaction.Transactional;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class QueueService {

    private final RoomRepository roomRepository;
    private final QueueItemRepository queueItemRepository;
    private final RoomAccessGuard roomAccessGuard;
    private final SimpMessagingTemplate messagingTemplate;

    public QueueService(RoomRepository roomRepository,
                        QueueItemRepository queueItemRepository,
                        RoomAccessGuard roomAccessGuard,
                        SimpMessagingTemplate messagingTemplate) {
        this.roomRepository = roomRepository;
        this.queueItemRepository = queueItemRepository;
        this.roomAccessGuard = roomAccessGuard;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public QueueResponse getQueue(String roomCode) {
        Room room = roomRepository.findByCode(roomCode)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        return new QueueResponse(activeItems(room).stream().map(this::toView).toList());
    }

    @Transactional
    public QueueItemView enqueue(String roomCode, AuthenticatedMember member, QueueAddRequest request) {
        RoomAccessGuard.RoomAccess access = roomAccessGuard.requireActiveMember(roomCode, member);
        Room room = access.room();
        requireActiveRoom(room);
        int position = activeItems(room).size();
        QueueItem item = queueItemRepository.save(QueueItem.builder()
                .id(UUID.randomUUID())
                .room(room)
                .position(position)
                .videoId(request.videoId())
                .title(request.title())
                .durationSeconds(request.durationSeconds())
                .thumbUrl(request.thumbUrl())
                .addedBy(access.membership().getUser())
                .status(QueueItemStatus.QUEUED)
                .enqueuedAt(OffsetDateTime.now())
                .build());
        QueueItemView view = toView(item);
        broadcastQueue(roomCode);
        return view;
    }

    @Transactional
    public QueueResponse move(String roomCode, UUID itemId, QueueMoveRequest request, AuthenticatedMember member) {
        Room room = roomAccessGuard.requireCapability(roomCode, member, Capability.REORDER_QUEUE).room();
        requireActiveRoom(room);
        QueueItem target = requireItemInRoom(itemId, room);
        // Only active (QUEUED/PLAYING) items participate: PLAYED/REMOVED rows are
        // parked at position -1 and must keep it, and newPosition is an index
        // into the queue the user actually sees.
        List<QueueItem> items = new ArrayList<>(activeItems(room));
        boolean removed = items.removeIf(q -> q.getId().equals(itemId));
        if (!removed) {
            throw new ResourceNotFoundException("Item not found in queue");
        }
        int newIndex = Math.min(request.newPosition(), items.size());
        items.add(newIndex, target);
        reindex(items);
        queueItemRepository.saveAll(items);
        QueueResponse response = new QueueResponse(items.stream().map(this::toView).toList());
        broadcastQueue(roomCode);
        return response;
    }

    @Transactional
    public void removeItem(String roomCode, UUID itemId, AuthenticatedMember member) {
        Room room = roomAccessGuard.requireCapability(roomCode, member, Capability.REMOVE_ITEMS).room();
        requireActiveRoom(room);
        QueueItem item = requireItemInRoom(itemId, room);
        item.setStatus(QueueItemStatus.REMOVED);
        item.setPosition(-1);
        queueItemRepository.save(item);
        reindexActiveExcluding(room, itemId);
        broadcastQueue(roomCode);
    }

    /**
     * Reassigns contiguous positions to the room's active items, excluding the
     * given (just finished/removed) item. Returns the reindexed list in order.
     */
    @Transactional
    public List<QueueItem> reindexActiveExcluding(Room room, UUID excludedItemId) {
        List<QueueItem> remaining = activeItems(room).stream()
                .filter(q -> !q.getId().equals(excludedItemId))
                .toList();
        reindex(remaining);
        queueItemRepository.saveAll(remaining);
        return remaining;
    }

    public void broadcastQueue(String roomCode) {
        QueueResponse payload = getQueue(roomCode);
        messagingTemplate.convertAndSend("/topic/rooms/" + roomCode + "/queue", payload);
    }

    private List<QueueItem> activeItems(Room room) {
        return queueItemRepository.findByRoomOrderByPosition(room).stream()
                .filter(item -> item.getStatus() == QueueItemStatus.QUEUED || item.getStatus() == QueueItemStatus.PLAYING)
                .toList();
    }

    private void reindex(List<QueueItem> items) {
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setPosition(i);
        }
    }

    private QueueItem requireItemInRoom(UUID itemId, Room room) {
        QueueItem item = queueItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Queue item not found"));
        if (!item.getRoom().getId().equals(room.getId())) {
            throw new BadRequestException("Item not in room");
        }
        return item;
    }

    private void requireActiveRoom(Room room) {
        if (room.getStatus() != RoomStatus.ACTIVE) {
            throw new BadRequestException("Room is not active");
        }
    }

    private QueueItemView toView(QueueItem item) {
        return new QueueItemView(
                item.getId(),
                item.getVideoId(),
                item.getTitle(),
                item.getDurationSeconds(),
                item.getThumbUrl(),
                item.getPosition(),
                item.getStatus(),
                item.getEnqueuedAt(),
                item.getAddedBy() != null ? item.getAddedBy().getDisplayName() : null
        );
    }
}
