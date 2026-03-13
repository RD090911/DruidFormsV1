package me.druid.v1;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import java.lang.reflect.Method;

public class DruidPlugin extends JavaPlugin {

    private ShapeshiftHandler shapeshiftHandler;

    public DruidPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        System.out.println("[DruidPlugin] Initializing V1 (Command Mode)...");

        this.shapeshiftHandler = new ShapeshiftHandler();

        try {
            Object cmdManager = HytaleServer.get().getCommandManager();
            Object command = new ShapeshiftCommand(shapeshiftHandler);
            for (Method m : cmdManager.getClass().getMethods()) {
                if (m.getName().equals("register") && m.getParameterCount() == 1) {
                    m.invoke(cmdManager, command);
                    System.out.println("[DruidPlugin] Command /shapeshift successfully registered.");
                    break;
                }
            }
        } catch (Exception e) {
            System.out.println("[DruidPlugin] Error registering command: " + e.getMessage());
        }

        try {
            HytaleServer.get().getEventBus().register(PlayerConnectEvent.class, shapeshiftHandler::handlePlayerConnect);
            HytaleServer.get().getEventBus().register(PlayerDisconnectEvent.class, shapeshiftHandler::handlePlayerDisconnect);
            System.out.println("[DruidPlugin] Player event handlers registered.");
        } catch (Exception e) {
            System.out.println("[DruidPlugin] Error registering player events: " + e.getMessage());
        }

        System.out.println("[DruidPlugin] V1 Ready!");
    }
}
