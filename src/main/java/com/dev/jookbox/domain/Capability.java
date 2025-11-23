package com.dev.jookbox.domain;

import java.util.Set;
import java.util.stream.Collectors;

public enum Capability {
    PLAYBACK_CONTROL(1),
    REORDER_QUEUE(2),
    REMOVE_ITEMS(4),
    SKIP_OVERRIDE(8);

    private final int mask;

    Capability(int mask) {
        this.mask = mask;
    }

    public int getMask() {
        return mask;
    }

    public static int toMask(Set<Capability> capabilities) {
        return capabilities.stream().mapToInt(Capability::getMask).reduce(0, (a, b) -> a | b);
    }

    public static Set<Capability> fromMask(int mask) {
        return Set.of(values()).stream()
                .filter(cap -> (mask & cap.mask) == cap.mask)
                .collect(Collectors.toSet());
    }

    public static boolean hasCapability(int mask, Capability cap) {
        return (mask & cap.mask) == cap.mask;
    }
}
