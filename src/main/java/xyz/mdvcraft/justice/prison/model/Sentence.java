package xyz.mdvcraft.justice.prison.model;

import java.util.UUID;

public final class Sentence {
    private final UUID uuid;
    private final String playerName;
    private String status;
    private final int requiredPoints;
    private int currentPoints;
    private final String reason;
    private final String staff;
    private final long createdAt;
    private final PlayerSnapshot snapshot;

    public Sentence(UUID uuid, String playerName, String status, int requiredPoints,
                    int currentPoints, String reason, String staff,
                    long createdAt, PlayerSnapshot snapshot) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.status = status;
        this.requiredPoints = requiredPoints;
        this.currentPoints = currentPoints;
        this.reason = reason;
        this.staff = staff;
        this.createdAt = createdAt;
        this.snapshot = snapshot;
    }

    public UUID uuid() { return uuid; }
    public String playerName() { return playerName; }
    public String status() { return status; }
    public int requiredPoints() { return requiredPoints; }
    public int currentPoints() { return currentPoints; }
    public String reason() { return reason; }
    public String staff() { return staff; }
    public long createdAt() { return createdAt; }
    public PlayerSnapshot snapshot() { return snapshot; }

    public void setStatus(String status) {
        this.status = status;
    }

    public int addPoints(int amount) {
        this.currentPoints = Math.min(requiredPoints, Math.max(0, currentPoints + amount));
        return this.currentPoints;
    }

    public int remaining() {
        return Math.max(0, requiredPoints - currentPoints);
    }

    public boolean completed() {
        return currentPoints >= requiredPoints;
    }
}
