package me.druid.v1;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.math.vector.Vector3i;
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
        DruidPermissions.initializeStorage(getDataDirectory());

        try {
            Object cmdManager = HytaleServer.get().getCommandManager();
            Object command = new ShapeshiftCommand(shapeshiftHandler);
            Object druidCommand = new DruidAdminCommand(shapeshiftHandler);
            for (Method m : cmdManager.getClass().getMethods()) {
                if (m.getName().equals("register") && m.getParameterCount() == 1) {
                    m.invoke(cmdManager, command);
                    m.invoke(cmdManager, druidCommand);
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
            HytaleServer.get().getEventBus().register(PlayerConnectEvent.class, event -> DruidPermissions.rememberPlayer(event.getPlayer()));
            HytaleServer.get().getEventBus().register(PlayerDisconnectEvent.class, event -> DruidPermissions.forgetPlayer(event.getPlayerRef().getUuid()));
            HytaleServer.get().getEventBus().register(PlayerInteractEvent.class, (String) null, this::handlePlayerInteract);
            System.out.println("[DruidPlugin] Player event handlers registered.");
        } catch (Exception e) {
            System.out.println("[DruidPlugin] Error registering player events: " + e.getMessage());
        }

        System.out.println("[DruidPlugin] V1 Ready!");
    }

    private void handlePlayerInteract(PlayerInteractEvent event) {
        if (event == null) return;
        Player player = event.getPlayer();
        if (player == null) return;
        if (DruidPermissions.canUseShrine(player)) return;

        try {
            Vector3i targetBlock = event.getTargetBlock();
            if (targetBlock == null) return;

            BlockType blockType = player.getWorld().getBlockType(targetBlock);
            if (blockType == null) return;
            if (!DruidPermissions.isDruidShrineBlock(blockType.getId())) return;

            event.setCancelled(true);
            DruidPermissions.sendDenied(player);
        } catch (Exception ignored) {
        }
    }
}
