package me.druid.v1;

import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import java.util.EventListener;
import java.util.List;

// Imports
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;

public class DruidAbilityListener implements EventListener {

    public void onPlayerInteract(PlayerInteractEvent event) {
        // DEBUG 1: Did the server even hear the click?
        System.out.println(">>> DEBUG: Interaction Event Detected!");

        Player player = event.getPlayer();
        String playerName = player.getDisplayName();

        // DEBUG 2: Who clicked?
        System.out.println(">>> DEBUG: Player is " + playerName);

        if (!ShapeshiftHandler.activeAbilities.containsKey(playerName)) {
            System.out.println(">>> DEBUG: FAIL - Player has no active abilities.");
            return;
        }

        String currentForm = ShapeshiftHandler.activeForms.get(playerName);
        System.out.println(">>> DEBUG: Current Form: " + currentForm);

        if (currentForm == null || !currentForm.equalsIgnoreCase("ram")) {
            System.out.println(">>> DEBUG: FAIL - Player is not a Ram.");
            return;
        }

        List<ShapeshiftHandler.AbilityConfig> abilities = ShapeshiftHandler.activeAbilities.get(playerName);
        for (ShapeshiftHandler.AbilityConfig ability : abilities) {
            System.out.println(">>> DEBUG: Checking Ability: " + ability.trigger + " -> " + ability.action);

            if (ability.trigger.equalsIgnoreCase("RIGHT_CLICK") && ability.action.equalsIgnoreCase("SMASH_BLOCK")) {
                System.out.println(">>> DEBUG: Success! Executing Smash Logic...");
                handleRamSmash(player);
            }
        }
    }

    @SuppressWarnings("removal")
    private void handleRamSmash(Player player) {
        System.out.println(">>> RAM STOMP LOGIC START");

        PlayerRef ref = player.getPlayerRef();
        if (ref == null) {
            System.out.println(">>> DEBUG: FAIL - PlayerRef is null");
            return;
        }

        Holder<EntityStore> holder = ref.getHolder();
        if (holder == null) {
            System.out.println(">>> DEBUG: FAIL - Holder is null");
            return;
        }

        BoundingBox bbox = (BoundingBox) holder.getComponent(BoundingBox.getComponentType());
        TransformComponent transform = (TransformComponent) holder.getComponent(TransformComponent.getComponentType());

        if (bbox == null || transform == null) {
            System.out.println(">>> DEBUG: FAIL - Missing Hitbox or Transform");
            return;
        }

        Box box = bbox.getBoundingBox();
        Vector3d pos = transform.getPosition();

        BlockType airType = BlockType.getAssetMap().getAsset(0);
        if (airType == null) return;

        System.out.println(">>> DEBUG: Scanning for blocks to smash at " + pos);

        box.forEachBlock(pos, 1.5f, null, (x, y, z, context) -> {
            long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
            WorldChunk chunk = player.getWorld().getChunkIfLoaded(chunkIndex);

            if (chunk != null) {
                int id = chunk.getBlock(x, y, z);
                if (id != 0) {
                    System.out.println(">>> DEBUG: Smashing block at " + x + "," + y + "," + z);
                    chunk.setBlock(x, y, z, 0, airType, 0, 0, 0);
                }
            }
            return true;
        });
    }
}