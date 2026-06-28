package me.druid.v1;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.VelocityThresholdStyle;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.splitvelocity.VelocityConfig;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.druid.v1.forms.FormId;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class StalkerBreachAbilityService {
    static final String BREACH_ITEM_ID = "Shark_Breach";
    static final long BREACH_COOLDOWN_MILLIS = 8_000L;

    private static final int BREACH_FALLBACK_SLOT_INDEX = 3;
    private static final double BREACH_UPWARD_SPEED = 20.0d;
    private static final double BREACH_FORWARD_ASSIST = 3.0d;
    private static final Map<UUID, Long> COOLDOWN_END_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, ActiveBreach> ACTIVE_BREACHES = new ConcurrentHashMap<>();
    private static final long BREACH_MONITOR_INTERVAL_MILLIS = 75L;
    private static final long BREACH_DRY_STOP_GRACE_MILLIS = 125L;
    private static final long BREACH_MAX_ACTIVE_MILLIS = 1_500L;

    private StalkerBreachAbilityService() {
    }

    static boolean tryStart(Player player, int activeHotbarSlot) {
        BreachContext context = resolveBreachContext(player);
        if (context == null) {
            System.out.println("[StalkerBreach] blocked reason=invalid-form-or-not-submerged");
            return false;
        }

        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return false;
        }

        long nowMillis = System.currentTimeMillis();
        boolean[] started = {false};
        long cooldownEndMillis = COOLDOWN_END_BY_PLAYER.compute(playerUuid, (ignored, currentEndMillis) -> {
            if (currentEndMillis != null && currentEndMillis > nowMillis) {
                return currentEndMillis;
            }
            started[0] = true;
            return nowMillis + BREACH_COOLDOWN_MILLIS;
        });

        long remainingMillis = cooldownEndMillis - nowMillis;
        int hudSlotIndex = activeHotbarSlot >= 0 ? activeHotbarSlot : BREACH_FALLBACK_SLOT_INDEX;
        if (!started[0]) {
            System.out.println("[StalkerBreach] blocked remainingMs="
                    + remainingMillis + " slot=" + hudSlotIndex);
            showCooldownHud(player, hudSlotIndex, remainingMillis);
            return false;
        }

        try {
            if (!context.world().isAlive()) {
                COOLDOWN_END_BY_PLAYER.remove(playerUuid, cooldownEndMillis);
                System.out.println("[StalkerBreach] blocked reason=world-unavailable");
                return false;
            }
            context.world().execute(() -> applyBreach(player, context.world(), context.impulse()));
        } catch (RuntimeException exception) {
            COOLDOWN_END_BY_PLAYER.remove(playerUuid, cooldownEndMillis);
            System.out.println("[StalkerBreach] blocked reason=world-rejected-task");
            return false;
        }

        showCooldownHud(player, hudSlotIndex, remainingMillis);
        return true;
    }

    static boolean isBreachItemId(String itemId) {
        return BREACH_ITEM_ID.equals(itemId);
    }

    static void cleanupPlayer(Player player, String reason) {
        UUID playerUuid = player == null ? null : player.getUuid();
        if (playerUuid == null) {
            return;
        }
        cancelActiveBreach(playerUuid);

        World world = player.getWorld();
        if (world == null || !world.isAlive()) {
            return;
        }
        try {
            world.execute(() -> clearBreachVelocityOnWorldThread(player));
        } catch (RuntimeException ignored) {
        }
    }

    static void handleDisconnect(PlayerRef playerRef) {
        UUID playerUuid = playerRef == null ? null : playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }
        cancelActiveBreach(playerUuid);
    }

    static void shutdown() {
        for (ActiveBreach activeBreach : ACTIVE_BREACHES.values()) {
            if (activeBreach != null && activeBreach.future != null) {
                activeBreach.future.cancel(false);
            }
        }
        ACTIVE_BREACHES.clear();
    }

    private static BreachContext resolveBreachContext(Player player) {
        if (player == null || ShapeshiftHandler.getActiveFormId(player) != FormId.FORM_STALKER) {
            return null;
        }

        World world = player.getWorld();
        Ref<EntityStore> ref = player.getReference();
        if (world == null || !world.isAlive() || ref == null || !ref.isValid()) {
            return null;
        }

        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            return null;
        }

        MovementStatesComponent movementComponent =
                store.getComponentConcurrent(ref, MovementStatesComponent.getComponentType());
        MovementStates states = movementComponent == null ? null : movementComponent.getMovementStates();
        if (states == null || states.flying || states.gliding || states.mounting || !isSubmerged(states)) {
            return null;
        }

        TransformComponent transformComponent =
                store.getComponentConcurrent(ref, TransformComponent.getComponentType());
        Transform transform = transformComponent == null ? null : transformComponent.getTransform();
        Vector3d rawDirection = transform == null ? null : transform.getDirection();

        Vector3d impulse = new Vector3d(0.0d, BREACH_UPWARD_SPEED, 0.0d);
        if (rawDirection != null) {
            Vector3d forward = new Vector3d(rawDirection.x, 0.0d, rawDirection.z);
            if (forward.lengthSquared() >= 0.0001d) {
                forward.normalize(BREACH_FORWARD_ASSIST);
                impulse.add(forward);
            }
        }

        return new BreachContext(world, impulse);
    }

    private static void applyBreach(Player player, World expectedWorld, Vector3d impulse) {
        if (player == null
                || expectedWorld == null
                || !expectedWorld.isAlive()
                || player.getWorld() != expectedWorld
                || impulse == null
                || ShapeshiftHandler.getActiveFormId(player) != FormId.FORM_STALKER) {
            return;
        }

        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }

        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            return;
        }

        MovementStatesComponent movementComponent =
                store.getComponent(ref, MovementStatesComponent.getComponentType());
        MovementStates states = movementComponent == null ? null : movementComponent.getMovementStates();
        if (states == null || states.flying || states.gliding || states.mounting || !isSubmerged(states)) {
            return;
        }

        Velocity velocity = store.ensureAndGetComponent(ref, Velocity.getComponentType());
        velocity.addInstruction(new Vector3d(impulse), createVelocityConfig(), ChangeVelocityType.Set);
        startSurfaceMonitor(player, expectedWorld);
    }

    private static boolean isSubmerged(MovementStates states) {
        if (states == null) {
            return false;
        }
        return states.inFluid
                || readBooleanState(states, "swimming")
                || readBooleanState(states, "underwater")
                || readBooleanState(states, "inWater");
    }

    private static boolean readBooleanState(Object stateObject, String fieldOrMethod) {
        if (stateObject == null || fieldOrMethod == null || fieldOrMethod.isBlank()) {
            return false;
        }
        try {
            Field field = stateObject.getClass().getField(fieldOrMethod);
            return field.getBoolean(stateObject);
        } catch (Exception ignored) {
        }
        try {
            Method method = stateObject.getClass().getMethod(
                    "is" + Character.toUpperCase(fieldOrMethod.charAt(0)) + fieldOrMethod.substring(1)
            );
            Object value = method.invoke(stateObject);
            return value instanceof Boolean && (Boolean) value;
        } catch (Exception ignored) {
        }
        try {
            Method method = stateObject.getClass().getMethod(
                    "get" + Character.toUpperCase(fieldOrMethod.charAt(0)) + fieldOrMethod.substring(1)
            );
            Object value = method.invoke(stateObject);
            return value instanceof Boolean && (Boolean) value;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static VelocityConfig createVelocityConfig() {
        VelocityConfig config = new VelocityConfig();
        config.setGroundResistance(0.985f);
        config.setGroundResistanceMax(0.96f);
        config.setAirResistance(0.995f);
        config.setAirResistanceMax(0.99f);
        config.setThreshold(0.6f);
        config.setStyle(VelocityThresholdStyle.Linear);
        return config;
    }

    private static void showCooldownHud(Player player, int slotIndex, long remainingMillis) {
        if (remainingMillis <= 0L) {
            DruidAbilityCooldownHotbarHud.remove(player, BREACH_ITEM_ID);
            return;
        }
        DruidAbilityCooldownHotbarHud.showOrUpdate(
                player,
                slotIndex,
                BREACH_ITEM_ID,
                remainingMillis,
                BREACH_COOLDOWN_MILLIS
        );
    }

    private static void startSurfaceMonitor(Player player, World expectedWorld) {
        if (player == null || expectedWorld == null) {
            return;
        }

        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return;
        }

        cancelActiveBreach(playerUuid);

        ActiveBreach activeBreach = new ActiveBreach(expectedWorld, System.currentTimeMillis());
        ScheduledFuture<?> future = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
                () -> tickSurfaceMonitor(playerUuid),
                BREACH_MONITOR_INTERVAL_MILLIS,
                BREACH_MONITOR_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS
        );
        activeBreach.future = future;
        ACTIVE_BREACHES.put(playerUuid, activeBreach);
    }

    private static void tickSurfaceMonitor(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }

        ActiveBreach activeBreach = ACTIVE_BREACHES.get(playerUuid);
        if (activeBreach == null) {
            return;
        }

        Player player = DruidPermissions.getOnlinePlayer(playerUuid);
        if (player == null) {
            cancelActiveBreach(playerUuid);
            return;
        }

        World world = player.getWorld();
        if (world == null || !world.isAlive()) {
            cancelActiveBreach(playerUuid);
            return;
        }

        try {
            world.execute(() -> tickSurfaceMonitorOnWorldThread(player, activeBreach));
        } catch (RuntimeException ignored) {
            cancelActiveBreach(playerUuid);
        }
    }

    private static void tickSurfaceMonitorOnWorldThread(Player player, ActiveBreach activeBreach) {
        if (player == null || activeBreach == null) {
            return;
        }

        UUID playerUuid = player.getUuid();
        if (playerUuid == null || ACTIVE_BREACHES.get(playerUuid) != activeBreach) {
            return;
        }

        if (player.getWorld() != activeBreach.expectedWorld
                || ShapeshiftHandler.getActiveFormId(player) != FormId.FORM_STALKER) {
            stopBreach(player, playerUuid);
            return;
        }

        if ((System.currentTimeMillis() - activeBreach.startedAtMillis) > BREACH_MAX_ACTIVE_MILLIS) {
            stopBreach(player, playerUuid);
            return;
        }

        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            stopBreach(player, playerUuid);
            return;
        }

        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            stopBreach(player, playerUuid);
            return;
        }

        MovementStatesComponent movementComponent =
                store.getComponent(ref, MovementStatesComponent.getComponentType());
        MovementStates states = movementComponent == null ? null : movementComponent.getMovementStates();
        if (states == null || states.flying || states.gliding || states.mounting) {
            stopBreach(player, playerUuid);
            return;
        }

        long nowMillis = System.currentTimeMillis();
        if (isSubmerged(states)) {
            activeBreach.firstDryAtMillis = 0L;
            return;
        }

        if (activeBreach.firstDryAtMillis <= 0L) {
            activeBreach.firstDryAtMillis = nowMillis;
            return;
        }

        if ((nowMillis - activeBreach.firstDryAtMillis) < BREACH_DRY_STOP_GRACE_MILLIS) {
            return;
        }

        stopBreach(player, playerUuid);
    }

    private static void stopBreach(Player player, UUID playerUuid) {
        cancelActiveBreach(playerUuid);
        clearBreachVelocityOnWorldThread(player);
    }

    private static void clearBreachVelocityOnWorldThread(Player player) {
        if (player == null) {
            return;
        }

        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }

        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            return;
        }

        Velocity velocity = store.ensureAndGetComponent(ref, Velocity.getComponentType());
        velocity.addInstruction(new Vector3d(0.0d, 0.0d, 0.0d), createVelocityConfig(), ChangeVelocityType.Set);
    }

    private static void cancelActiveBreach(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        ActiveBreach activeBreach = ACTIVE_BREACHES.remove(playerUuid);
        if (activeBreach != null && activeBreach.future != null) {
            activeBreach.future.cancel(false);
        }
    }

    private record BreachContext(World world, Vector3d impulse) {
    }

    private static final class ActiveBreach {
        private final World expectedWorld;
        private final long startedAtMillis;
        private volatile long firstDryAtMillis;
        private volatile ScheduledFuture<?> future;

        private ActiveBreach(World expectedWorld, long startedAtMillis) {
            this.expectedWorld = expectedWorld;
            this.startedAtMillis = startedAtMillis;
        }
    }
}
