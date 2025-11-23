package com.dev.jookbox.web.rest;

import com.dev.jookbox.security.AuthenticatedMember;
import com.dev.jookbox.service.QueueService;
import com.dev.jookbox.web.dto.QueueAddRequest;
import com.dev.jookbox.web.dto.QueueItemView;
import com.dev.jookbox.web.dto.QueueMoveRequest;
import com.dev.jookbox.web.dto.QueueResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/rooms/{code}/queue")
public class QueueController {

    private final QueueService queueService;

    public QueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QueueItemView enqueue(@PathVariable String code,
                                 @AuthenticationPrincipal AuthenticatedMember member,
                                 @Valid @RequestBody QueueAddRequest request) {
        return queueService.enqueue(code, member, request);
    }

    @PutMapping("/{itemId}/move")
    public QueueResponse move(@PathVariable String code,
                              @PathVariable UUID itemId,
                              @AuthenticationPrincipal AuthenticatedMember member,
                              @Valid @RequestBody QueueMoveRequest request) {
        return queueService.move(code, itemId, request, member);
    }

    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String code,
                       @PathVariable UUID itemId,
                       @AuthenticationPrincipal AuthenticatedMember member) {
        queueService.removeItem(code, itemId, member);
    }
}
