package com.dev.jookbox.service;

import com.dev.jookbox.domain.PlaybackState;
import com.dev.jookbox.domain.QueueItem;
import com.dev.jookbox.domain.QueueItemStatus;
import com.dev.jookbox.domain.Role;
import com.dev.jookbox.domain.Room;
import com.dev.jookbox.domain.RoomStatus;
import com.dev.jookbox.domain.Vote;
import com.dev.jookbox.domain.VoteType;
import com.dev.jookbox.repository.MembershipRepository;
import com.dev.jookbox.repository.QueueItemRepository;
import com.dev.jookbox.repository.VoteRepository;
import com.dev.jookbox.security.AuthenticatedMember;
import com.dev.jookbox.web.BadRequestException;
import com.dev.jookbox.web.ForbiddenOperationException;
import com.dev.jookbox.web.ResourceNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class VoteService {

    private final VoteRepository voteRepository;
    private final QueueItemRepository queueItemRepository;
    private final MembershipRepository membershipRepository;
    private final RoomAccessGuard roomAccessGuard;
    private final QueueService queueService;
    private final PlaybackService playbackService;

    public VoteService(VoteRepository voteRepository,
                       QueueItemRepository queueItemRepository,
                       MembershipRepository membershipRepository,
                       RoomAccessGuard roomAccessGuard,
                       QueueService queueService,
                       PlaybackService playbackService) {
        this.voteRepository = voteRepository;
        this.queueItemRepository = queueItemRepository;
        this.membershipRepository = membershipRepository;
        this.roomAccessGuard = roomAccessGuard;
        this.queueService = queueService;
        this.playbackService = playbackService;
    }

    @Transactional
    public boolean vote(String roomCode, UUID itemId, VoteType type, AuthenticatedMember member) {
        RoomAccessGuard.RoomAccess access = roomAccessGuard.requireActiveMember(roomCode, member);
        Room room = access.room();
        if (room.getStatus() != RoomStatus.ACTIVE) {
            throw new ForbiddenOperationException("Room is not active");
        }
        QueueItem item = queueItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Queue item not found"));
        if (!item.getRoom().getId().equals(room.getId())) {
            throw new BadRequestException("Item not in room");
        }
        if (access.membership().getRole() == Role.HOST) {
            applyOutcome(type, item, roomCode);
            return true;
        }
        if (voteRepository.existsByQueueItemAndUserAndType(item, access.membership().getUser(), type)) {
            throw new ForbiddenOperationException("Vote already recorded");
        }
        Vote vote = Vote.builder()
                .id(UUID.randomUUID())
                .queueItem(item)
                .user(access.membership().getUser())
                .type(type)
                .createdAt(OffsetDateTime.now())
                .build();
        voteRepository.save(vote);

        // Strict majority (> half) of CURRENT members, counting only votes cast
        // by users still in the room — a track must not cross the threshold just
        // because earlier voters left and shrank the denominator.
        long totalMembers = membershipRepository.countByRoomAndLeftAtIsNull(room);
        long votes = voteRepository.countCurrentMemberVotes(item, type, room);
        long required = Math.max(1, (totalMembers / 2) + 1);
        if (votes >= required) {
            applyOutcome(type, item, roomCode);
            return true;
        }
        return false;
    }

    private void applyOutcome(VoteType type, QueueItem item, String roomCode) {
        QueueItemStatus terminalStatus = type == VoteType.SKIP ? QueueItemStatus.PLAYED : QueueItemStatus.REMOVED;

        // If the vote hit the currently playing track, playback must advance too —
        // otherwise the shared state keeps pointing at the skipped item and the
        // room stalls. advance() marks the item, reindexes, and broadcasts both
        // playback and queue.
        UUID nowPlaying = playbackService.readCurrentState(roomCode)
                .map(PlaybackState::getNowPlayingQueueItemId)
                .orElse(null);
        if (item.getId().equals(nowPlaying)) {
            playbackService.advance(roomCode, item.getId(), terminalStatus);
            return;
        }

        item.setStatus(terminalStatus);
        item.setPosition(-1);
        queueItemRepository.save(item);
        queueService.reindexActiveExcluding(item.getRoom(), item.getId());
        queueService.broadcastQueue(roomCode);
    }
}
