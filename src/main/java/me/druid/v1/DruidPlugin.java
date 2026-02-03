package me.druid.v1;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;

public class DruidPlugin extends JavaPlugin {

    private ShapeshiftHandler handler;

    public DruidPlugin(JavaPluginInit init) {
        super(init);
    }

    // This method runs when the server starts
    protected void start() {
        System.out.println("DruidPlugin v1.0 is loading...");

        // 1. Initialize the handler
        this.handler = new ShapeshiftHandler();

        // 2. Register the Command
        this.getCommandRegistry().registerCommand(new ShapeshiftCommand(handler));

        // 3. Register Events
        this.getEventRegistry().registerGlobal(PlayerConnectEvent.class, event -> {
            var player = event.getPlayer();
            System.out.println("DruidPlugin: " + player.toString() + " has connected!");
        });

        // --- DEBUG START: FIND THE MISSING ITEM ---
        System.out.println("=== STARTING ITEM SEARCH ===");
        try {
            // A. Get the internal map of all registered items using Reflection
            // This forces the server to show us exactly what names it is using
            Class<?> itemClass = Class.forName("com.hypixel.hytale.server.core.asset.type.item.config.Item");
            java.lang.reflect.Method getAssetMap = itemClass.getMethod("getAssetMap");
            Object assetMap = getAssetMap.invoke(null);

            // B. Get all the keys (names)
            java.lang.reflect.Method getKeys = assetMap.getClass().getMethod("keySet");
            java.util.Set<?> keys = (java.util.Set<?>) getKeys.invoke(assetMap);

            // C. Search for our Ram Horn
            boolean found = false;
            for (Object key : keys) {
                String name = key.toString();
                // Check for "ram" or "horn" (case insensitive)
                if (name.toLowerCase().contains("ram") || name.toLowerCase().contains("horn")) {
                    System.out.println("FOUND REGISTERED ITEM: " + name);
                    found = true;
                }
            }

            if (!found) {
                System.out.println("NO 'RAM' or 'HORN' ITEMS FOUND IN REGISTRY.");
                System.out.println("CHECK: Does your folder structure match 'src/main/resources/assets/druid/items/Ram_Horn.json'?");
            }

        } catch (Exception e) {
            System.out.println("DEBUG FAILED: " + e.getMessage());
        }
        System.out.println("============================");
        // --- DEBUG END ---

        System.out.println("DruidPlugin loaded successfully!");
    }

    protected void stop() {
        if (handler != null) {
            // handler.cleanup(); // Optional cleanup
        }
        System.out.println("DruidPlugin shutting down.");
    }
}