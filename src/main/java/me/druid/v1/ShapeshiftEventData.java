package me.druid.v1;

import java.util.UUID;

public class ShapeshiftEventData {
    private final UUID playerId;
    private final String targetForm;

    public ShapeshiftEventData(UUID playerId, String targetForm) {
        this.playerId = playerId;
        this.targetForm = targetForm;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getTargetForm() {
        return targetForm;
    }
}