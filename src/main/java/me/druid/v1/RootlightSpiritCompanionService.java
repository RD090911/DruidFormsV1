package me.druid.v1;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.protocol.MovementStates;
import me.druid.v1.forms.FormId;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class RootlightSpiritCompanionService {
    private static final String ROLE_ID = "Rootlight_Spirit";
    private static final String OWNER_TARGET_SLOT = "LockedTarget";
    private static final String WALK_MOTION_CONTROLLER = "Walk";
    private static final String FLY_MOTION_CONTROLLER = "Fly";
    private static final long WATCHDOG_INTERVAL_MILLIS = 2_000L;
    private static final long REFRESH_COOLDOWN_MILLIS = 2_500L;
    private static final double REFRESH_DISTANCE_SQUARED = 20.0 * 20.0;
    private static final double GROUNDED_SPAWN_Y_OFFSET = 1.0;
    private static final double FLIGHT_SPAWN_Y_OFFSET = 1.75;

    private static final Map<UUID, Ref<EntityStore>> ACTIVE_COMPANIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_REFRESH_MILLIS = new ConcurrentHashMap<>();
    private static final Set<UUID> PENDING_REFRESHES = ConcurrentHashMap.newKeySet();
    private static final Object WATCHDOG_LOCK = new Object();
    private static ScheduledFuture<?> watchdog;

    private RootlightSpiritCompanionService() {
    }

    static void toggle(Player player) {
        if (player == null) {
            return;
        }

        World world = player.getWorld();
        if (world == null) {
            return;
        }

        world.execute(() -> toggleOnWorldThread(player));
    }

    static void handleDisconnect(PlayerRef playerRef) {
        if (playerRef == null) {
            return;
        }

        UUID playerId = playerRef.getUuid();
        Ref<EntityStore> companionRef = playerId == null ? null : ACTIVE_COMPANIONS.remove(playerId);
        clearRefreshState(playerId);
        removeCompanion(companionRef);
        stopWatchdogIfIdle();
    }

    static void shutdown() {
        ArrayList<Ref<EntityStore>> companionRefs = new ArrayList<>(ACTIVE_COMPANIONS.values());
        ACTIVE_COMPANIONS.clear();
        LAST_REFRESH_MILLIS.clear();
        PENDING_REFRESHES.clear();
        stopWatchdog();

        for (Ref<EntityStore> companionRef : companionRefs) {
            removeCompanion(companionRef);
        }
    }

    private static void toggleOnWorldThread(Player player) {
        UUID playerId = player.getUuid();
        if (playerId == null) {
            return;
        }

        Ref<EntityStore> existingCompanion = ACTIVE_COMPANIONS.remove(playerId);
        if (existingCompanion != null) {
            clearRefreshState(playerId);
            removeCompanion(existingCompanion);
            stopWatchdogIfIdle();
            sendPlayerMessage(player, "Rootlight Spirit returns to the earth.");
            System.out.println("[RootlightSpiritCompanion] disabled owner="
                    + DruidPlayerCompat.getPlayerNameOrUnknown(player));
            return;
        }

        if (ShapeshiftHandler.getActiveFormId(player) == null) {
            sendPlayerMessage(player, "Rootlight Spirit requires a druid form.");
            return;
        }

        if (isDead(player)) {
            return;
        }

        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        Store<EntityStore> store = playerRef.getStore();
        TransformComponent playerTransform = store == null
                ? null
                : store.getComponent(playerRef, TransformComponent.getComponentType());
        if (store == null || playerTransform == null) {
            return;
        }

        boolean flightOwnerAirborne = isFlightOwnerAirborne(player, store, playerRef);
        Ref<EntityStore> companionRef = spawnCompanion(
                store,
                playerRef,
                playerTransform,
                flightOwnerAirborne
        );
        if (companionRef == null) {
            sendPlayerMessage(player, "Rootlight Spirit could not be summoned.");
            System.out.println("[RootlightSpiritCompanion] spawn failed role=" + ROLE_ID);
            return;
        }

        ACTIVE_COMPANIONS.put(playerId, companionRef);
        ensureWatchdogRunning();
        sendPlayerMessage(player, "Rootlight Spirit answers your call.");
        System.out.println("[RootlightSpiritCompanion] enabled owner="
                + DruidPlayerCompat.getPlayerNameOrUnknown(player)
                + " role=" + ROLE_ID
                + " modelLight=#8b5 radius=20");
    }

    private static void runWatchdog() {
        if (ACTIVE_COMPANIONS.isEmpty()) {
            stopWatchdogIfIdle();
            return;
        }

        for (UUID playerId : new ArrayList<>(ACTIVE_COMPANIONS.keySet())) {
            Player player = DruidPermissions.getOnlinePlayer(playerId);
            if (player == null) {
                removeTrackedCompanion(playerId);
                continue;
            }

            World world = player.getWorld();
            if (world == null) {
                removeTrackedCompanion(playerId);
                continue;
            }

            world.execute(() -> validateOnWorldThread(playerId, player));
        }

        stopWatchdogIfIdle();
    }

    private static void validateOnWorldThread(UUID playerId, Player player) {
        Ref<EntityStore> companionRef = ACTIVE_COMPANIONS.get(playerId);
        if (companionRef == null) {
            return;
        }

        Ref<EntityStore> playerRef = player.getReference();
        boolean invalidOwner = playerRef == null
                || !playerRef.isValid()
                || ShapeshiftHandler.getActiveFormId(player) == null
                || isDead(player);
        Store<EntityStore> playerStore = invalidOwner ? null : playerRef.getStore();
        Store<EntityStore> companionStore = companionRef.isValid() ? companionRef.getStore() : null;
        boolean invalidCompanion = !companionRef.isValid()
                || companionStore == null
                || companionStore != playerStore;
        if (invalidOwner || invalidCompanion) {
            if (ACTIVE_COMPANIONS.remove(playerId, companionRef)) {
                clearRefreshState(playerId);
                removeCompanion(companionRef);
                stopWatchdogIfIdle();
                System.out.println("[RootlightSpiritCompanion] watchdog cleanup owner="
                        + DruidPlayerCompat.getPlayerNameOrUnknown(player));
            }
            return;
        }

        NPCEntity companion = companionStore.getComponent(companionRef, NPCEntity.getComponentType());
        TransformComponent companionTransform = companionStore.getComponent(
                companionRef,
                TransformComponent.getComponentType()
        );
        TransformComponent playerTransform = playerStore.getComponent(
                playerRef,
                TransformComponent.getComponentType()
        );
        if (companion == null
                || companion.getRole() == null
                || companionTransform == null
                || playerTransform == null) {
            ACTIVE_COMPANIONS.remove(playerId, companionRef);
            clearRefreshState(playerId);
            removeCompanionOnWorldThread(companionRef);
            stopWatchdogIfIdle();
            return;
        }

        bindOwner(companion, playerRef, false);
        Vector3d companionPosition = companionTransform.getPosition();
        Vector3d playerPosition = playerTransform.getPosition();
        boolean flightOwnerAirborne = isFlightOwnerAirborne(player, playerStore, playerRef);
        updateMotionController(
                companionRef,
                companion,
                companionTransform,
                playerTransform,
                playerStore,
                flightOwnerAirborne
        );
        if (companionPosition.distanceSquared(playerPosition) > REFRESH_DISTANCE_SQUARED) {
            refreshCompanion(
                    playerId,
                    player,
                    playerRef,
                    playerTransform,
                    companionRef,
                    playerStore,
                    flightOwnerAirborne,
                    "too_far"
            );
        }
    }

    private static void bindOwner(NPCEntity companion, Ref<EntityStore> playerRef, boolean resetInstructions) {
        var role = companion.getRole();
        Ref<EntityStore> currentOwner = role.getMarkedEntitySupport().getMarkedEntityRef(OWNER_TARGET_SLOT);
        boolean ownerChanged = currentOwner == null || !currentOwner.equals(playerRef);

        role.setMarkedTarget(OWNER_TARGET_SLOT, playerRef);
        if (resetInstructions || ownerChanged) {
            role.resetAllInstructions();
        }
    }

    private static void refreshCompanion(
            UUID playerId,
            Player player,
            Ref<EntityStore> playerRef,
            TransformComponent playerTransform,
            Ref<EntityStore> companionRef,
            Store<EntityStore> store,
            boolean elevatedSpawn,
            String reason
    ) {
        long now = System.currentTimeMillis();
        Long lastRefresh = LAST_REFRESH_MILLIS.get(playerId);
        if ((lastRefresh != null && now - lastRefresh < REFRESH_COOLDOWN_MILLIS)
                || !PENDING_REFRESHES.add(playerId)) {
            return;
        }

        try {
            if (!ACTIVE_COMPANIONS.remove(playerId, companionRef)) {
                return;
            }

            LAST_REFRESH_MILLIS.put(playerId, now);
            removeCompanionOnWorldThread(companionRef);

            Ref<EntityStore> freshCompanion = spawnCompanion(
                    store,
                    playerRef,
                    playerTransform,
                    elevatedSpawn
            );
            if (freshCompanion != null) {
                ACTIVE_COMPANIONS.put(playerId, freshCompanion);
                System.out.println("[RootlightSpiritCompanion] refreshed owner="
                        + DruidPlayerCompat.getPlayerNameOrUnknown(player)
                        + " reason=" + reason);
            }
        } finally {
            PENDING_REFRESHES.remove(playerId);
            stopWatchdogIfIdle();
        }
    }

    private static Ref<EntityStore> spawnCompanion(
            Store<EntityStore> store,
            Ref<EntityStore> playerRef,
            TransformComponent playerTransform,
            boolean elevatedSpawn
    ) {
        double yOffset = elevatedSpawn ? FLIGHT_SPAWN_Y_OFFSET : GROUNDED_SPAWN_Y_OFFSET;
        Vector3d spawnPosition = new Vector3d(playerTransform.getPosition()).add(1.5, yOffset, 0.0);
        var spawned = NPCPlugin.get().spawnNPC(
                store,
                ROLE_ID,
                null,
                spawnPosition,
                playerTransform.getRotation()
        );
        Ref<EntityStore> companionRef = spawned == null ? null : spawned.first();
        if (companionRef == null || !companionRef.isValid()) {
            return null;
        }

        NPCEntity companion = store.getComponent(companionRef, NPCEntity.getComponentType());
        if (companion == null || companion.getRole() == null) {
            removeCompanionOnWorldThread(companionRef);
            return null;
        }

        bindOwner(companion, playerRef, true);
        updateMotionController(
                companionRef,
                companion,
                store.getComponent(companionRef, TransformComponent.getComponentType()),
                playerTransform,
                store,
                elevatedSpawn
        );
        return companionRef;
    }

    private static void updateMotionController(
            Ref<EntityStore> companionRef,
            NPCEntity companion,
            TransformComponent companionTransform,
            TransformComponent playerTransform,
            Store<EntityStore> store,
            boolean flightOwnerAirborne
    ) {
        String desiredController = flightOwnerAirborne
                ? FLY_MOTION_CONTROLLER
                : WALK_MOTION_CONTROLLER;
        if (desiredController.equals(companion.getActiveMotionControllerName())) {
            return;
        }

        if (flightOwnerAirborne && companionTransform != null && playerTransform != null) {
            Vector3d flightPosition = new Vector3d(playerTransform.getPosition())
                    .add(1.5, FLIGHT_SPAWN_Y_OFFSET, 0.0);
            companionTransform.teleportPosition(flightPosition);
            companionTransform.markChunkDirty(store);
        }

        companion.getRole().setActiveMotionController(
                companionRef,
                companion,
                desiredController,
                store
        );
    }

    private static boolean isFlightOwnerAirborne(
            Player player,
            Store<EntityStore> store,
            Ref<EntityStore> playerRef
    ) {
        if (ShapeshiftHandler.getActiveFormId(player) != FormId.FORM_FLIGHT) {
            return false;
        }

        MovementStatesComponent movementComponent = store.getComponent(
                playerRef,
                MovementStatesComponent.getComponentType()
        );
        MovementStates states = movementComponent == null
                ? null
                : movementComponent.getMovementStates();
        return states != null
                && (states.flying || states.gliding || states.falling || !states.onGround);
    }

    private static void removeTrackedCompanion(UUID playerId) {
        Ref<EntityStore> companionRef = ACTIVE_COMPANIONS.remove(playerId);
        clearRefreshState(playerId);
        removeCompanion(companionRef);
    }

    private static void clearRefreshState(UUID playerId) {
        if (playerId == null) {
            return;
        }
        LAST_REFRESH_MILLIS.remove(playerId);
        PENDING_REFRESHES.remove(playerId);
    }

    private static boolean isDead(Player player) {
        Ref<EntityStore> playerRef = player == null ? null : player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return true;
        }

        Store<EntityStore> store = playerRef.getStore();
        return store == null
                || store.getComponent(playerRef, DeathComponent.getComponentType()) != null;
    }

    private static void removeCompanion(Ref<EntityStore> companionRef) {
        if (companionRef == null || !companionRef.isValid()) {
            return;
        }

        Store<EntityStore> store = companionRef.getStore();
        if (store == null) {
            return;
        }

        if (store.isInThread()) {
            removeCompanionOnWorldThread(companionRef);
            return;
        }

        EntityStore entityStore = store.getExternalData();
        World world = entityStore == null ? null : entityStore.getWorld();
        if (world != null) {
            world.execute(() -> removeCompanionOnWorldThread(companionRef));
        }
    }

    private static void removeCompanionOnWorldThread(Ref<EntityStore> companionRef) {
        if (companionRef == null || !companionRef.isValid()) {
            return;
        }

        Store<EntityStore> store = companionRef.getStore();
        if (store != null) {
            store.removeEntity(companionRef, RemoveReason.REMOVE);
        }
    }

    private static void ensureWatchdogRunning() {
        synchronized (WATCHDOG_LOCK) {
            if (watchdog != null && !watchdog.isDone()) {
                return;
            }
            watchdog = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
                    RootlightSpiritCompanionService::runWatchdog,
                    WATCHDOG_INTERVAL_MILLIS,
                    WATCHDOG_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private static void stopWatchdogIfIdle() {
        if (ACTIVE_COMPANIONS.isEmpty()) {
            stopWatchdog();
        }
    }

    private static void stopWatchdog() {
        synchronized (WATCHDOG_LOCK) {
            if (watchdog != null) {
                watchdog.cancel(false);
                watchdog = null;
            }
        }
    }

    private static void sendPlayerMessage(Player player, String text) {
        if (player == null || text == null) {
            return;
        }
        FormattedMessage component = new FormattedMessage();
        component.rawText = text;
        DruidPlayerCompat.sendMessage(player, new Message(component));
    }
}
