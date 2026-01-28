package me.druid.v1;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.util.logging.Logger;

// --- THE CORRECT IMPORT (From your GitHub Save) ---
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

        // --- THE FIX ---
        // We use the specific Manager found in your saved version.
        CommandManager.get().register(this.command);

        this.logger.info("Druid Plugin has been enabled with commands!");
    }
}