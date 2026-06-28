package me.druid.v1;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.VelocityThresholdStyle;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.splitvelocity.VelocityConfig;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.druid.v1.forms.FormId;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class StalkerSurgeAbilityService {
    static final String SURGE_ITEM_ID = "Shark_Surge";
    static final long SURGE_COOLDOWN_MILLIS = 7_500L;

    private static final int SURGE_FALLBACK_SLOT_INDEX = 1;
    private static final double WATER_SURGE_SPEED = 16.0d;
    private static final double LAND_SURGE_SPEED = 6.5d;
    private static final Map<UUID, Long> COOLDOWN_END_BY_PLAYER = new ConcurrentHashMap<>();

    private StalkerSurgeAbilityService() {
    }

    static boolean tryStart(Player player, int activeHotbarSlot) {
        SurgeContext context = resolveSurgeContext(player);
        if (context == null) {
            System.out.println("[StalkerSurge] blocked reason=invalid-form-or-movement-state");
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
            return nowMillis + SURGE_COOLDOWN_MILLIS;
        });

        long remainingMillis = cooldownEndMillis - nowMillis;
        int hudSlotIndex = activeHotbarSlot >= 0 ? activeHotbarSlot : SURGE_FALLBACK_SLOT_INDEX;
        if (!started[0]) {
            System.out.println("[StalkerSurge] blocked remainingMs="
                    + remainingMillis + " slot=" + hudSlotIndex);
            showCooldownHud(player, hudSlotIndex, remainingMillis);
            return false;
        }

        try {
            if (!context.world().isAlive()) {
                COOLDOWN_END_BY_PLAYER.remove(playerUuid, cooldownEndMillis);
                System.out.println("[StalkerSurge] blocked reason=world-unavailable");
                return false;
            }
            context.world().execute(() -> applySurge(player, context.world(), context.impulse(), context.submerged()));
        } catch (RuntimeException exception) {
            COOLDOWN_END_BY_PLAYER.remove(playerUuid, cooldownEndMillis);
            System.out.println("[StalkerSurge] blocked reason=world-rejected-task");
            return false;
        }

        showCooldownHud(player, hudSlotIndex, remainingMillis);
        return true;
    }

    static boolean isSurgeItemId(String itemId) {
        return SURGE_ITEM_ID.equals(itemId);
    }

    private static SurgeContext resolveSurgeContext(Player player) {
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
        if (states == null || states.flying || states.gliding || states.mounting) {
            return null;
        }

        boolean submerged = isSubmerged(states);
        if (!submerged && !states.onGround) {
            return null;
        }

        TransformComponent transformComponent =
                store.getComponentConcurrent(ref, TransformComponent.getComponentType());
        Transform transform = transformComponent == null ? null : transformComponent.getTransform();
        if (transform == null) {
            return null;
        }

        Vector3d rawDirection = transform.getDirection();
        if (rawDirection == null || rawDirection.lengthSquared() < 0.0001d) {
            return null;
        }

        Vector3d impulse = new Vector3d(rawDirection);
        if (submerged) {
            impulse.normalize(WATER_SURGE_SPEED);
        } else {
            impulse.y = 0.0d;
            if (impulse.lengthSquared() < 0.0001d) {
                return null;
            }
            impulse.normalize(LAND_SURGE_SPEED);
        }

        return new SurgeContext(world, impulse, submerged);
    }

    private static void applySurge(Player player, World expectedWorld, Vector3d impulse, boolean submerged) {
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
        if (states == null || states.flying || states.gliding || states.mounting) {
            return;
        }

        boolean currentlySubmerged = isSubmerged(states);
        if (!currentlySubmerged && !states.onGround) {
            return;
        }

        Velocity velocity = store.ensureAndGetComponent(ref, Velocity.getComponentType());
        velocity.addInstruction(
                new Vector3d(impulse),
                createVelocityConfig(currentlySubmerged || submerged),
                ChangeVelocityType.Set
        );
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

    private static VelocityConfig createVelocityConfig(boolean submerged) {
        VelocityConfig config = new VelocityConfig();
        if (submerged) {
            config.setGroundResistance(0.985f);
            config.setGroundResistanceMax(0.96f);
            config.setAirResistance(0.995f);
            config.setAirResistanceMax(0.99f);
            config.setThreshold(0.8f);
        } else {
            config.setGroundResistance(0.92f);
            config.setGroundResistanceMax(0.25f);
            config.setAirResistance(0.985f);
            config.setAirResistanceMax(0.97f);
            config.setThreshold(2.0f);
        }
        config.setStyle(VelocityThresholdStyle.Linear);
        return config;
    }

    private static void showCooldownHud(Player player, int slotIndex, long remainingMillis) {
        if (remainingMillis <= 0L) {
            DruidAbilityCooldownHotbarHud.remove(player, SURGE_ITEM_ID);
            return;
        }
        DruidAbilityCooldownHotbarHud.showOrUpdate(
                player,
                slotIndex,
                SURGE_ITEM_ID,
                remainingMillis,
                SURGE_COOLDOWN_MILLIS
        );
    }

    private record SurgeContext(World world, Vector3d impulse, boolean submerged) {
    }
}
