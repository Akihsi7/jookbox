package com.dev.jookbox.web.rest;

import com.dev.jookbox.security.AuthenticatedMember;
import com.dev.jookbox.service.PlaybackService;
import com.dev.jookbox.web.dto.PlayRequest;
import com.dev.jookbox.web.dto.PlaybackStateResponse;
import com.dev.jookbox.web.dto.SeekRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rooms/{code}/playback")
public class PlaybackController {

    private final PlaybackService playbackService;

    public PlaybackController(PlaybackService playbackService) {
        this.playbackService = playbackService;
    }

    @GetMapping
    public PlaybackStateResponse getState(@PathVariable String code) {
        return playbackService.getState(code).orElse(null);
    }

    @PostMapping("/play")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PlaybackStateResponse play(@PathVariable String code,
                                      @AuthenticationPrincipal AuthenticatedMember member,
                                      @Valid @RequestBody PlayRequest request) {
        return playbackService.play(code, request.queueItemId(), member, request.positionMs());
    }

    @PostMapping("/pause")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PlaybackStateResponse pause(@PathVariable String code,
                                       @AuthenticationPrincipal AuthenticatedMember member) {
        return playbackService.pause(code, member);
    }

    @PostMapping("/seek")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PlaybackStateResponse seek(@PathVariable String code,
                                      @AuthenticationPrincipal AuthenticatedMember member,
                                      @Valid @RequestBody SeekRequest request) {
        return playbackService.seek(code, request.positionMs(), member);
    }
}
