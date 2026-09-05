package xyz.mdvcraft.justice.chat.model;

import java.util.UUID;

public record SlowRecord(
        UUID uuid,
        String playerName,
        long delayMillis,
        long expiresAt
) {
    public boolean active(long now) {
        return expiresAt < 0 || expiresAt > now;
    }

    public long remaining(long now) {
        return expiresAt < 0 ? -1L : Math.max(0L, expiresAt - now);
    }
}
