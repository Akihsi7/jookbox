package com.dev.jookbox.web.dto;

import java.util.List;

public record QueueResponse(
        List<QueueItemView> items
) {
}
