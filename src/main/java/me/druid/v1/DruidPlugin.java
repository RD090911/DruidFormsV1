package me.druid.v1;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;

public class DruidPlugin extends JavaPlugin {

    private ShapeshiftHandler handler;
    private DruidAbilityListener listenerLogic;

    public DruidPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void start() {
        System.out.println("==========================================");
        System.out.println("       DRUID PLUGIN v1.0 STARTING...      ");
        System.out.println("==========================================");

        // 1. Initialize Logic
        this.handler = new ShapeshiftHandler();
        this.listenerLogic = new DruidAbilityListener();

        // 2. Register Listener (FIXED)
        // We use 'registerGlobal' because we want to listen for this event across the whole server
        this.getEventRegistry().registerGlobal(PlayerInteractEvent.class, event -> {
            listenerLogic.onPlayerInteract(event);
        });

        // 3. Register Command (FIXED)
        // Your source code confirmed the method is named 'registerCommand', not 'register'
        this.getCommandRegistry().registerCommand(new ShapeshiftCommand(handler));

        System.out.println(">> Druid Logic Loaded.");
    }

    @Override
    protected void shutdown() {
        System.out.println("Druid Plugin Stopping...");
        if (handler != null) {
            ShapeshiftHandler.activeForms.clear();
            ShapeshiftHandler.activeAbilities.clear();
        }
    }
}