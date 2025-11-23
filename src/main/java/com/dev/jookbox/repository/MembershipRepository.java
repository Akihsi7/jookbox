package com.dev.jookbox.repository;

import com.dev.jookbox.domain.Membership;
import com.dev.jookbox.domain.Room;
import com.dev.jookbox.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {
    Optional<Membership> findByRoomAndUser(Room room, User user);
    Optional<Membership> findByRoomCodeAndUserId(String code, UUID userId);
    long countByRoom(Room room);
}
