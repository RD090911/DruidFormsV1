package me.druid.v1;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.util.logging.Logger;

// This is the import that was missing!
import com.hypixel.hytale.server.core.command.system.CommandManager;

public class DruidPlugin extends JavaPlugin {
    private ShapeshiftHandler handler;
    private ShapeshiftCommand command;
    private Logger logger;

    public DruidPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    public void start() {
        this.logger = Logger.getLogger("DruidFormsV1");
        this.logger.info("Initializing Druid Plugin...");

        this.handler = new ShapeshiftHandler(this.logger);
        this.command = new ShapeshiftCommand(handler);

        // This is the specific registration line from your working backup
        CommandManager.get().register(this.command);

        this.logger.info("Druid Plugin has been enabled with commands!");
    }
}