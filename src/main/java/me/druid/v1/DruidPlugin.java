package me.druid.v1;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.event.EventHandler;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.logger.HytaleLogger;
import javax.annotation.Nonnull;

public class DruidPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public DruidPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("DruidFormV1 is setting up...");
        EventRegistry.register(this);
    }

    @Override
    protected void start() {
        LOGGER.atInfo().log("DruidFormV1 is ready!");
    }

    @Override
    protected void shutdown() {
        LOGGER.atInfo().log("DruidFormV1 is shutting down.");
    }

    @EventHandler
    public void onPlayerConnect(PlayerConnectEvent event) {
        LOGGER.atInfo().log("A Druid has connected to the server!");
    }
}