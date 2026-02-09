package me.druid.v1;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;

public class DruidPlugin extends JavaPlugin {

    private ShapeshiftHandler handler;

    public DruidPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void start() {
        System.out.println("DruidPlugin v1.0 is loading...");

        this.handler = new ShapeshiftHandler();
        this.getCommandRegistry().registerCommand(new ShapeshiftCommand(handler));

        // Player Join Event
        this.getEventRegistry().registerGlobal(PlayerConnectEvent.class, event -> {
            // Fix: Use getDisplayName() as discovered
            System.out.println("DruidPlugin: " + event.getPlayer().getDisplayName() + " has connected!");
        });

        // Damage Event Registration
        DruidEvents logic = new DruidEvents();
        this.getEventRegistry().registerGlobal(Damage.class, logic::onEntityDamage);

        System.out.println("DruidPlugin loaded successfully!");
    }

    // Removed broken stop() method. JavaPlugin handles shutdown automatically.
}