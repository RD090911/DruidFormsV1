package me.druid.v1;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.util.logging.Logger;

public class DruidPlugin extends JavaPlugin {
    private ShapeshiftHandler handler;
    private ShapeshiftCommand command;
    private Logger logger;

    // The Constructor: The "Key" the server uses to start us.
    public DruidPlugin(JavaPluginInit init) {
        super(init);
    }

    // FIXED: The official Hytale API method is 'start()', NOT 'onStart'
    @Override
    public void start() {
        // Create our own logger to be safe
        this.logger = Logger.getLogger("DruidFormsV1");

        this.logger.info("Initializing Druid Plugin...");

        // Initialize the handler with our logger
        this.handler = new ShapeshiftHandler(this.logger);

        // Initialize the command
        this.command = new ShapeshiftCommand(handler);

        this.logger.info("Druid Plugin has been enabled with commands!");
    }
}