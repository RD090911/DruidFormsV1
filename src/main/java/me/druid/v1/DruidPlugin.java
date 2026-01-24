package me.druid.v1;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import java.util.logging.Logger;

public class DruidPlugin extends JavaPlugin {
    private final Logger logger;
    private ShapeshiftHandler handler;
    private ShapeshiftCommand command;

    // We bring back the constructor because Hytale NEEDS it to inject the logger!
    public DruidPlugin(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void onEnable() {
        // Now we use the local 'logger' variable again, which fixes the red lines
        this.handler = new ShapeshiftHandler(logger);

        // Initialize the command
        this.command = new ShapeshiftCommand(handler);

        logger.info("Druid Plugin has been enabled with commands!");
    }
}