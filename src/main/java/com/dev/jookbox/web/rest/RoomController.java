package com.dev.jookbox.web.rest;

import com.dev.jookbox.service.QueueService;
import com.dev.jookbox.service.RoomService;
import com.dev.jookbox.web.dto.JoinRoomRequest;
import com.dev.jookbox.web.dto.MembershipTokenResponse;
import com.dev.jookbox.web.dto.QueueResponse;
import com.dev.jookbox.web.dto.RoomCreationRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rooms")
public class RoomController {

    private final RoomService roomService;
    private final QueueService queueService;

    public RoomController(RoomService roomService, QueueService queueService) {
        this.roomService = roomService;
        this.queueService = queueService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MembershipTokenResponse createRoom(@Valid @RequestBody RoomCreationRequest request) {
        return roomService.createRoom(request);
    }

    @PostMapping("/{code}/join")
    public MembershipTokenResponse joinRoom(@PathVariable String code, @Valid @RequestBody JoinRoomRequest request) {
        return roomService.joinRoom(code, request);
    }

    @GetMapping("/{code}/queue")
    public QueueResponse getQueue(@PathVariable String code) {
        return queueService.getQueue(code);
    }
}
