package me.druid.v1;

import java.util.logging.Logger;
import java.util.Optional;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;

public class ShapeshiftHandler {
    private final Logger logger;

    public ShapeshiftHandler(Logger logger) {
        this.logger = logger;
    }

    public void executeShapeshift(ShapeshiftEventData data) {
        logger.info("Attempting to shapeshift player " + data.getPlayerId() + " into a " + data.getTargetForm());

        /* * CONCEPTUAL LOGIC:
         * We will use the ModelComponent we just found!
         * * entity.getComponent(ModelComponent.class).ifPresent(model -> {
         * // We will find the exact method name (like .setModel) later
         * });
         */
    }
}