package me.druid.v1;

import java.util.logging.Logger;

public class DruidPlugin {
    private final Logger logger;
    private ShapeshiftHandler handler;
    private ShapeshiftCommand command; // Add this line

    public DruidPlugin(Logger logger) {
        this.logger = logger;
    }

    public void onEnable() {
        this.handler = new ShapeshiftHandler(logger);

        // Initialize the command and give it access to the handler
        this.command = new ShapeshiftCommand(handler);

        logger.info("Druid Plugin has been enabled with commands!");
    }
}