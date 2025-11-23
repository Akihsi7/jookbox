package com.dev.jookbox.service;

import com.dev.jookbox.domain.QueueItem;
import com.dev.jookbox.domain.QueueItemStatus;
import com.dev.jookbox.domain.Role;
import com.dev.jookbox.domain.RoomStatus;
import com.dev.jookbox.domain.Vote;
import com.dev.jookbox.domain.VoteType;
import com.dev.jookbox.repository.MembershipRepository;
import com.dev.jookbox.repository.QueueItemRepository;
import com.dev.jookbox.repository.RoomRepository;
import com.dev.jookbox.repository.VoteRepository;
import com.dev.jookbox.security.AuthenticatedMember;
import com.dev.jookbox.web.BadRequestException;
import com.dev.jookbox.web.ForbiddenOperationException;
import com.dev.jookbox.web.ResourceNotFoundException;
import com.dev.jookbox.web.dto.QueueItemView;
import com.dev.jookbox.web.dto.QueueResponse;
import jakarta.transaction.Transactional;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class VoteService {

    private final VoteRepository voteRepository;
    private final QueueItemRepository queueItemRepository;
    private final RoomRepository roomRepository;
    private final MembershipRepository membershipRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public VoteService(VoteRepository voteRepository,
                       QueueItemRepository queueItemRepository,
                       RoomRepository roomRepository,
                       MembershipRepository membershipRepository,
                       SimpMessagingTemplate messagingTemplate) {
        this.voteRepository = voteRepository;
        this.queueItemRepository = queueItemRepository;
        this.roomRepository = roomRepository;
        this.membershipRepository = membershipRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public boolean vote(String roomCode, UUID itemId, VoteType type, AuthenticatedMember member) {
        var room = roomRepository.findByCode(roomCode)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found"));
        if (room.getStatus() != RoomStatus.ACTIVE) {
            throw new ForbiddenOperationException("Room is not active");
        }
        QueueItem item = queueItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Queue item not found"));
        if (!item.getRoom().getId().equals(room.getId())) {
            throw new BadRequestException("Item not in room");
        }
        if (member.role() == Role.HOST && member.roomId().equals(room.getId())) {
            applyOutcome(type, item, roomCode);
            return true;
        }
        var membership = membershipRepository.findById(member.membershipId())
                .orElseThrow(() -> new ForbiddenOperationException("Membership not found"));
        if (!membership.getRoom().getId().equals(room.getId())) {
            throw new ForbiddenOperationException("Membership not associated with this room");
        }
        if (voteRepository.existsByQueueItemAndUserAndType(item, membership.getUser(), type)) {
            throw new ForbiddenOperationException("Vote already recorded");
        }
        Vote vote = Vote.builder()
                .id(UUID.randomUUID())
                .queueItem(item)
                .user(membership.getUser())
                .type(type)
                .createdAt(OffsetDateTime.now())
                .build();
        voteRepository.save(vote);

        long totalMembers = membershipRepository.countByRoom(room);
        long votes = voteRepository.countByQueueItemAndType(item, type);
        long required = Math.max(1, (totalMembers / 2) + 1);
        if (votes >= required) {
            applyOutcome(type, item, roomCode);
            return true;
        }
        return false;
    }

    private void applyOutcome(VoteType type, QueueItem item, String roomCode) {
        if (type == VoteType.SKIP) {
            item.setStatus(QueueItemStatus.PLAYED);
        } else {
            item.setStatus(QueueItemStatus.REMOVED);
        }
        item.setPosition(-1);
        var remaining = queueItemRepository.findByRoomOrderByPosition(item.getRoom()).stream()
                .filter(q -> !q.getId().equals(item.getId()))
                .filter(q -> q.getStatus() == QueueItemStatus.QUEUED || q.getStatus() == QueueItemStatus.PLAYING)
                .toList();
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setPosition(i);
        }
        queueItemRepository.save(item);
        queueItemRepository.saveAll(remaining);
        var updated = remaining.stream()
                .map(q -> new QueueItemView(
                        q.getId(),
                        q.getVideoId(),
                        q.getTitle(),
                        q.getDurationSeconds(),
                        q.getThumbUrl(),
                        q.getPosition(),
                        q.getStatus(),
                        q.getEnqueuedAt(),
                        q.getAddedBy() != null ? q.getAddedBy().getDisplayName() : null
                ))
                .toList();
        messagingTemplate.convertAndSend("/topic/rooms/" + roomCode + "/queue",
                new QueueResponse(updated));
    }
}
