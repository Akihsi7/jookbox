package com.dev.jookbox.web.rest;

import com.dev.jookbox.security.AuthenticatedMember;
import com.dev.jookbox.service.VoteService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/rooms/{code}/queue/{itemId}")
public class VoteController {

    private final VoteService voteService;

    public VoteController(VoteService voteService) {
        this.voteService = voteService;
    }

    @PostMapping("/vote-skip")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> voteSkip(@PathVariable String code,
                                        @PathVariable UUID itemId,
                                        @AuthenticationPrincipal AuthenticatedMember member) {
        boolean applied = voteService.vote(code, itemId, com.dev.jookbox.domain.VoteType.SKIP, member);
        return Map.of("applied", applied);
    }

    @PostMapping("/vote-remove")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> voteRemove(@PathVariable String code,
                                          @PathVariable UUID itemId,
                                          @AuthenticationPrincipal AuthenticatedMember member) {
        boolean applied = voteService.vote(code, itemId, com.dev.jookbox.domain.VoteType.REMOVE, member);
        return Map.of("applied", applied);
    }
}
