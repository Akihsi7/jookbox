package com.dev.jookbox.repository;

import com.dev.jookbox.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {
    Optional<Room> findByCode(String code);
    boolean existsByCode(String code);
}
