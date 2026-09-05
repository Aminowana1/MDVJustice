package xyz.mdvcraft.justice.chat.model;

import java.util.UUID;

public record MuteRecord(
        UUID uuid,
        String playerName,
        long expiresAt,
        String reason,
        String staff
) {
    public boolean permanent() {
        return expiresAt < 0;
    }

    public boolean active(long now) {
        return permanent() || expiresAt > now;
    }

    public long remaining(long now) {
        return permanent() ? -1L : Math.max(0L, expiresAt - now);
    }
}
