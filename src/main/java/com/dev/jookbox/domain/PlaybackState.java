package com.dev.jookbox.domain;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class PlaybackState {
    UUID roomId;
    UUID nowPlayingQueueItemId;
    int positionMs;
    boolean playing;
    Instant lastUpdateTs;
}
