package me.druid.v1;

import java.util.logging.Logger;

public class DruidPlugin {
    private final Logger logger;
    private ShapeshiftHandler handler; // This stores our "Brain"

    public DruidPlugin(Logger logger) {
        this.logger = logger;
    }

    public void onEnable() {
        // We "initialize" the handler here
        this.handler = new ShapeshiftHandler(logger);

        logger.info("Druid Plugin has been enabled!");
    }
}