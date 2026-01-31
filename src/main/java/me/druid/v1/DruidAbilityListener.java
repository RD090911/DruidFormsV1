package me.druid.v1;

import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import java.util.EventListener;
import java.util.List;

// ECS & World Imports
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;

public class DruidAbilityListener implements EventListener {

    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getDisplayName();

        // 1. Safety Checks
        if (!ShapeshiftHandler.activeAbilities.containsKey(playerName)) return;

        String currentForm = ShapeshiftHandler.activeForms.get(playerName);
        if (currentForm == null || !currentForm.equalsIgnoreCase("ram")) return;

        List<ShapeshiftHandler.AbilityConfig> abilities = ShapeshiftHandler.activeAbilities.get(playerName);
        for (ShapeshiftHandler.AbilityConfig ability : abilities) {

            // FIX 1: We accept LEFT_CLICK now because Right Click crashes the internal validator
            boolean isTrigger = ability.trigger.equalsIgnoreCase("LEFT_CLICK") ||
                    ability.trigger.equalsIgnoreCase("RIGHT_CLICK");

            if (isTrigger && ability.action.equalsIgnoreCase("SMASH_BLOCK")) {
                handleRamSmash(player);
            }
        }
    }

    @SuppressWarnings("removal")
    private void handleRamSmash(Player player) {
        System.out.println(">>> RAM SMASH (Mining Mode)!");

        PlayerRef ref = player.getPlayerRef();
        if (ref == null) return;

        Holder<EntityStore> holder = ref.getHolder();
        if (holder == null) return;

        BoundingBox bbox = (BoundingBox) holder.getComponent(BoundingBox.getComponentType());
        TransformComponent transform = (TransformComponent) holder.getComponent(TransformComponent.getComponentType());

        if (bbox == null || transform == null) return;

        Box box = bbox.getBoundingBox();
        Vector3d pos = transform.getPosition();

        // Smash Radius
        box.forEachBlock(pos, 1.5f, null, (x, y, z, context) -> {
            long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
            WorldChunk chunk = player.getWorld().getChunkIfLoaded(chunkIndex);

            if (chunk != null) {
                int id = chunk.getBlock(x, y, z);
                if (id != 0) {
                    // FIX 2: Use breakBlock instead of setBlock
                    // This creates particles AND drops the item (Ore/Wood)
                    // 0 = Default Settings (Physics + Particles)
                    chunk.breakBlock(x, y, z, 0);
                }
            }
            return true;
        });
    }
}