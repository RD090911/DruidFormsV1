package me.druid.v1;

import java.util.UUID;

/**
 * This class handles the actual player command.
 */
public class ShapeshiftCommand {
    private final ShapeshiftHandler handler;

    public ShapeshiftCommand(ShapeshiftHandler handler) {
        this.handler = handler;
    }

    // This simulates what happens when a player types the command
    public void onCommandExecuted(UUID playerId, String formName) {
        // 1. Pack the info into our data container
        ShapeshiftEventData data = new ShapeshiftEventData(playerId, formName);

        // 2. Send it to the "Brain" (Handler) to process
        handler.executeShapeshift(data);
    }
}