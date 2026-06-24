package me.druid.v1;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.blackboard.Blackboard;
import com.hypixel.hytale.server.npc.blackboard.view.attitude.AttitudeView;
import com.hypixel.hytale.server.npc.blackboard.view.attitude.IAttitudeProvider;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import org.joml.Vector3d;

final class ProwlerStealthNpcAttitudeService {
    private static final float ATTITUDE_CACHE_REFRESH_SECONDS = 0.11f;
    private static final double CLOSE_DETECT_RADIUS_BLOCKS = 4.0d;
    private static final double CLOSE_DETECT_RADIUS_SQUARED =
            CLOSE_DETECT_RADIUS_BLOCKS * CLOSE_DETECT_RADIUS_BLOCKS;
    private static final boolean DEBUG_PROVIDER_RESULTS = Boolean.getBoolean("druid.debugProwlerStealthNpc");
    private static final IAttitudeProvider PROVIDER = new ProwlerStealthAttitudeProvider();
    private static final Set<String> REGISTERED_WORLD_NAMES = ConcurrentHashMap.newKeySet();

    private ProwlerStealthNpcAttitudeService() {
    }

    static void registerExistingWorlds() {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        Map<String, World> worlds = universe.getWorlds();
        if (worlds == null || worlds.isEmpty()) {
            return;
        }

        for (World world : worlds.values()) {
            registerWorld(world);
        }
    }

    static void registerWorld(World world) {
        if (world == null || !world.isAlive()) {
            return;
        }

        String worldName = world.getName();
        if (worldName == null || worldName.isBlank()) {
            return;
        }
        if (!REGISTERED_WORLD_NAMES.add(worldName)) {
            return;
        }

        try {
            world.execute(() -> registerWorldOnWorldThread(world, worldName));
        } catch (RuntimeException exception) {
            REGISTERED_WORLD_NAMES.remove(worldName);
            System.out.println("[DruidProwlerStealthNpc] registration failed reason=world-rejected-task world="
                    + sanitizeWorldName(worldName));
        }
    }

    static void unregisterWorld(World world) {
        if (world == null) {
            return;
        }
        unregisterWorldName(world.getName());
    }

    static void shutdown() {
        REGISTERED_WORLD_NAMES.clear();
    }

    static void refreshWorldAttitudeCaches(Player player, String reason) {
        if (player == null) {
            return;
        }

        World world = player.getWorld();
        if (world == null || !world.isAlive()) {
            return;
        }

        UUID playerUuid = player.getUuid();
        try {
            world.execute(() -> refreshWorldAttitudeCachesOnWorldThread(world, playerUuid, reason));
        } catch (RuntimeException exception) {
            System.out.println("[DruidProwlerStealthNpc] cache refresh failed reason=world-rejected-task player="
                    + sanitizeUuid(playerUuid));
        }
    }

    private static void registerWorldOnWorldThread(World world, String worldName) {
        try {
            if (world == null || !world.isAlive()) {
                REGISTERED_WORLD_NAMES.remove(worldName);
                return;
            }

            NPCPlugin npcPlugin = NPCPlugin.get();
            EntityStore entityStore = world.getEntityStore();
            Store<EntityStore> store = entityStore == null ? null : entityStore.getStore();
            Blackboard blackboard = npcPlugin == null || store == null
                    ? null
                    : store.getResource(npcPlugin.getBlackboardResourceType());
            if (blackboard == null) {
                REGISTERED_WORLD_NAMES.remove(worldName);
                System.out.println("[DruidProwlerStealthNpc] registration skipped reason=blackboard-unavailable world="
                        + sanitizeWorldName(worldName));
                return;
            }

            blackboard.forEachView(
                    AttitudeView.class,
                    view -> view.registerProvider(IAttitudeProvider.OVERRIDE_PRIORITY, PROVIDER)
            );
            System.out.println("[DruidProwlerStealthNpc] registered world=" + sanitizeWorldName(worldName));
        } catch (RuntimeException exception) {
            REGISTERED_WORLD_NAMES.remove(worldName);
            System.out.println("[DruidProwlerStealthNpc] registration failed reason="
                    + exception.getClass().getSimpleName()
                    + " world=" + sanitizeWorldName(worldName));
        }
    }

    private static void unregisterWorldName(String worldName) {
        if (worldName != null) {
            REGISTERED_WORLD_NAMES.remove(worldName);
        }
    }

    private static void refreshWorldAttitudeCachesOnWorldThread(World world, UUID playerUuid, String reason) {
        if (world == null || !world.isAlive()) {
            return;
        }

        EntityStore entityStore = world.getEntityStore();
        Store<EntityStore> store = entityStore == null ? null : entityStore.getStore();
        if (store == null) {
            return;
        }

        int refreshed = refreshNpcWorldSupportCaches(store);
        System.out.println("[DruidProwlerStealthNpc] cache refresh reason="
                + sanitizeReason(reason)
                + " player=" + sanitizeUuid(playerUuid)
                + " npcs=" + refreshed);
    }

    private static int refreshNpcWorldSupportCaches(Store<EntityStore> store) {
        int[] refreshed = {0};
        store.forEachChunk(Query.any(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> ignored) -> {
            if (chunk == null || !chunk.getArchetype().contains(NPCEntity.getComponentType())) {
                return;
            }
            int size = chunk == null ? 0 : chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                Role role = npc == null ? null : npc.getRole();
                WorldSupport worldSupport = role == null ? null : role.getWorldSupport();
                if (worldSupport == null) {
                    continue;
                }
                worldSupport.tick(ATTITUDE_CACHE_REFRESH_SECONDS);
                refreshed[0]++;
            }
        });
        return refreshed[0];
    }

    private static String sanitizeWorldName(String worldName) {
        return worldName == null || worldName.isBlank()
                ? "unknown"
                : worldName.replaceAll("[^A-Za-z0-9_.-]", "-");
    }

    private static String sanitizeReason(String reason) {
        return reason == null || reason.isBlank()
                ? "unknown"
                : reason.replaceAll("[^A-Za-z0-9_-]", "-");
    }

    private static String sanitizeUuid(UUID playerUuid) {
        return playerUuid == null ? "unknown" : playerUuid.toString();
    }

    private static final class ProwlerStealthAttitudeProvider implements IAttitudeProvider {
        @Override
        public Attitude getAttitude(
                Ref<EntityStore> source,
                Role role,
                Ref<EntityStore> target,
                ComponentAccessor<EntityStore> accessor
        ) {
            if (target == null || accessor == null) {
                return null;
            }

            Player targetPlayer = accessor.getComponent(target, Player.getComponentType());
            if (targetPlayer == null) {
                return null;
            }

            if (!ProwlerStealthService.isStealthed(targetPlayer)) {
                debugProviderDecision(source, target, accessor, targetPlayer, "not-stealthed");
                return null;
            }

            double distanceSquared = getDistanceSquared(source, target, accessor);
            if (distanceSquared <= CLOSE_DETECT_RADIUS_SQUARED) {
                debugProviderDecision(targetPlayer, distanceSquared, "close-detect");
                return null;
            }

            debugProviderDecision(targetPlayer, distanceSquared, "suppressed");
            return Attitude.IGNORE;
        }
    }

    private static double getDistanceSquared(
            Ref<EntityStore> source,
            Ref<EntityStore> target,
            ComponentAccessor<EntityStore> accessor
    ) {
        if (source == null || target == null || accessor == null) {
            return Double.POSITIVE_INFINITY;
        }

        TransformComponent sourceTransform = accessor.getComponent(source, TransformComponent.getComponentType());
        TransformComponent targetTransform = accessor.getComponent(target, TransformComponent.getComponentType());
        Vector3d sourcePosition = sourceTransform == null ? null : sourceTransform.getPosition();
        Vector3d targetPosition = targetTransform == null ? null : targetTransform.getPosition();
        if (sourcePosition == null || targetPosition == null) {
            return Double.POSITIVE_INFINITY;
        }
        return sourcePosition.distanceSquared(targetPosition);
    }

    private static void debugProviderDecision(
            Ref<EntityStore> source,
            Ref<EntityStore> target,
            ComponentAccessor<EntityStore> accessor,
            Player targetPlayer,
            String reason
    ) {
        if (!DEBUG_PROVIDER_RESULTS) {
            return;
        }
        debugProviderDecision(targetPlayer, getDistanceSquared(source, target, accessor), reason);
    }

    private static void debugProviderDecision(Player targetPlayer, double distanceSquared, String reason) {
        if (!DEBUG_PROVIDER_RESULTS || targetPlayer == null) {
            return;
        }

        String distance = Double.isFinite(distanceSquared)
                ? String.format(Locale.ROOT, "%.2f", Math.sqrt(distanceSquared))
                : "unknown";
        System.out.println("[DruidProwlerStealthNpc] provider target="
                + sanitizeUuid(targetPlayer.getUuid())
                + " distance=" + distance
                + " reason=" + reason);
    }
}
