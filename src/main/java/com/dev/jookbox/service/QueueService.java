package com.dev.jookbox.service;

import com.dev.jookbox.domain.*;
import com.dev.jookbox.repository.MembershipRepository;
import com.dev.jookbox.repository.QueueItemRepository;
import com.dev.jookbox.repository.RoomRepository;
import com.dev.jookbox.security.AuthenticatedMember;
import com.dev.jookbox.web.BadRequestException;
import com.dev.jookbox.web.ForbiddenOperationException;
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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class QueueService {

    private final RoomRepository roomRepository;
    private final QueueItemRepository queueItemRepository;
    private final MembershipRepository membershipRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public QueueService(RoomRepository roomRepository,
                        QueueItemRepository queueItemRepository,
                        MembershipRepository membershipRepository,
                        SimpMessagingTemplate messagingTemplate) {
        this.roomRepository = roomRepository;
        this.queueItemRepository = queueItemRepository;
        this.membershipRepository = membershipRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public QueueResponse getQueue(String roomCode) {
        Room room = roomRepository.findByCode(roomCode)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        List<QueueItem> items = queueItemRepository.findByRoomOrderByPosition(room).stream()
                .filter(item -> item.getStatus() == QueueItemStatus.QUEUED || item.getStatus() == QueueItemStatus.PLAYING)
                .toList();
        return new QueueResponse(items.stream().map(this::toView).toList());
    }

    @Transactional
    public QueueItemView enqueue(String roomCode, AuthenticatedMember member, QueueAddRequest request) {
        Room room = roomRepository.findByCode(roomCode)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        if (room.getStatus() != RoomStatus.ACTIVE) {
            throw new BadRequestException("Room is not active");
        }
        Membership membership = membershipRepository.findById(member.membershipId())
                .orElseThrow(() -> new ForbiddenOperationException("Membership not found"));
        if (!membership.getRoom().getId().equals(room.getId())) {
            throw new ForbiddenOperationException("Membership not associated with this room");
        }
        OffsetDateTime now = OffsetDateTime.now();
        int position = (int) queueItemRepository.findByRoomOrderByPosition(room).stream()
                .filter(item -> item.getStatus() == QueueItemStatus.QUEUED || item.getStatus() == QueueItemStatus.PLAYING)
                .count();
        QueueItem item = queueItemRepository.save(QueueItem.builder()
                .id(UUID.randomUUID())
                .room(room)
                .position(position)
                .videoId(request.videoId())
                .title(request.title())
                .durationSeconds(request.durationSeconds())
                .thumbUrl(request.thumbUrl())
                .addedBy(membership.getUser())
                .status(QueueItemStatus.QUEUED)
                .enqueuedAt(now)
                .build());
        QueueItemView view = toView(item);
        broadcastQueue(roomCode);
        return view;
    }

    @Transactional
    public QueueResponse move(String roomCode, UUID itemId, QueueMoveRequest request, AuthenticatedMember member) {
        if (!member.capabilities().contains(Capability.REORDER_QUEUE.name())) {
            throw new ForbiddenOperationException("You do not have permission to reorder the queue");
        }
        Room room = roomRepository.findByCode(roomCode)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        if (room.getStatus() != RoomStatus.ACTIVE) {
            throw new BadRequestException("Room is not active");
        }
        membershipRepository.findById(member.membershipId())
                .filter(m -> m.getRoom().getCode().equals(roomCode))
                .orElseThrow(() -> new ForbiddenOperationException("Membership not associated with this room"));
        QueueItem target = queueItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Queue item not found"));
        if (!target.getRoom().getId().equals(room.getId())) {
            throw new BadRequestException("Item not in room");
        }
        List<QueueItem> items = new ArrayList<>(queueItemRepository.findByRoomOrderByPosition(room));
        items.sort(Comparator.comparingInt(QueueItem::getPosition));
        int currentIndex = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getId().equals(itemId)) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex < 0) {
            throw new ResourceNotFoundException("Item not found in queue");
        }
        items.remove(currentIndex);
        int newIndex = Math.min(request.newPosition(), items.size());
        items.add(newIndex, target);
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setPosition(i);
        }
        queueItemRepository.saveAll(items);
        QueueResponse response = new QueueResponse(items.stream().map(this::toView).collect(Collectors.toList()));
        broadcastQueue(roomCode);
        return response;
    }

    @Transactional
    public void removeItem(String roomCode, UUID itemId, AuthenticatedMember member) {
        Room room = roomRepository.findByCode(roomCode)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        if (room.getStatus() != RoomStatus.ACTIVE) {
            throw new BadRequestException("Room is not active");
        }
        membershipRepository.findById(member.membershipId())
                .filter(m -> m.getRoom().getCode().equals(roomCode))
                .orElseThrow(() -> new ForbiddenOperationException("Membership not associated with this room"));
        QueueItem item = queueItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Queue item not found"));
        if (!item.getRoom().getId().equals(room.getId())) {
            throw new BadRequestException("Item not in room");
        }
        if (!member.capabilities().contains(Capability.REMOVE_ITEMS.name()) && member.role() != Role.HOST) {
            throw new ForbiddenOperationException("You do not have permission to remove items");
        }
        List<QueueItem> items = new ArrayList<>(queueItemRepository.findByRoomOrderByPosition(room));
        items.removeIf(q -> q.getId().equals(itemId));
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setPosition(i);
        }
        item.setStatus(QueueItemStatus.REMOVED);
        item.setPosition(-1);
        queueItemRepository.save(item);
        queueItemRepository.saveAll(items);
        broadcastQueue(roomCode);
    }

    private void broadcastQueue(String roomCode) {
        QueueResponse payload = getQueue(roomCode);
        messagingTemplate.convertAndSend("/topic/rooms/" + roomCode + "/queue", payload);
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
