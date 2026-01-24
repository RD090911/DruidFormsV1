package me.druid.v1;

import java.util.logging.Logger;

/**
 * This class is the "Brain."
 * It will eventually handle the actual transformation logic.
 */
public class ShapeshiftHandler {
    private final Logger logger;

    public ShapeshiftHandler(Logger logger) {
        this.logger = logger;
    }

    // This is a placeholder method we will call later to test if things work
    public void executeShapeshift(ShapeshiftEventData data) {
        logger.info("Attempting to shapeshift player " + data.getPlayerId() + " into a " + data.getTargetForm());

        // This is where we will eventually put the Hytale-specific
        // code to change the player's model!
    }
}