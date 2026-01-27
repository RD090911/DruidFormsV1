package me.druid.v1;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.util.logging.Logger;

// --- NEW IMPORT: The Manager you just found ---
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
        // We found "public static CommandManager get()" in your source code.
        // We found "public CommandRegistration register(...)" in your source code.
        // So we chain them together:
        CommandManager.get().register(this.command);

        this.logger.info("Druid Plugin has been enabled with commands!");
    }
}