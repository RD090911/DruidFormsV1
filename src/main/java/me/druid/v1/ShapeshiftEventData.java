package me.druid.v1;

import java.util.UUID;

/**
 * This class acts as the data container for our shapeshift event.
 * It tells the system: "When this happens, here is the Player and the Form name."
 */
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