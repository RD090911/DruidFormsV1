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

final class StalkerDiveAbilityService {
    static final String DIVE_ITEM_ID = "Shark_Dive";
    static final long DIVE_COOLDOWN_MILLIS = 6_500L;
    private static final String DIVE_SOUND_EVENT_ID = "SFX_Water_MoveIn";
    private static final float DIVE_SOUND_VOLUME_MODIFIER = 5.6234133f;

    private static final int DIVE_FALLBACK_SLOT_INDEX = 2;
    private static final double DIVE_DOWNWARD_SPEED = 16.5d;
    private static final double DIVE_FORWARD_ASSIST = 2.75d;
    private static final Map<UUID, Long> COOLDOWN_END_BY_PLAYER = new ConcurrentHashMap<>();

    private StalkerDiveAbilityService() {
    }

    static boolean tryStart(Player player, int activeHotbarSlot) {
        DiveContext context = resolveDiveContext(player);
        if (context == null) {
            System.out.println("[StalkerDive] blocked reason=invalid-form-or-not-submerged");
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
            return nowMillis + DIVE_COOLDOWN_MILLIS;
        });

        long remainingMillis = cooldownEndMillis - nowMillis;
        int hudSlotIndex = activeHotbarSlot >= 0 ? activeHotbarSlot : DIVE_FALLBACK_SLOT_INDEX;
        if (!started[0]) {
            System.out.println("[StalkerDive] blocked remainingMs="
                    + remainingMillis + " slot=" + hudSlotIndex);
            showCooldownHud(player, hudSlotIndex, remainingMillis);
            return false;
        }

        try {
            if (!context.world().isAlive()) {
                COOLDOWN_END_BY_PLAYER.remove(playerUuid, cooldownEndMillis);
                System.out.println("[StalkerDive] blocked reason=world-unavailable");
                return false;
            }
            context.world().execute(() -> applyDive(player, context.world(), context.impulse()));
        } catch (RuntimeException exception) {
            COOLDOWN_END_BY_PLAYER.remove(playerUuid, cooldownEndMillis);
            System.out.println("[StalkerDive] blocked reason=world-rejected-task");
            return false;
        }

        showCooldownHud(player, hudSlotIndex, remainingMillis);
        return true;
    }

    static boolean isDiveItemId(String itemId) {
        return itemId != null && itemId.contains(DIVE_ITEM_ID);
    }

    private static DiveContext resolveDiveContext(Player player) {
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

        Vector3d impulse = new Vector3d(0.0d, -DIVE_DOWNWARD_SPEED, 0.0d);
        if (rawDirection != null) {
            Vector3d forward = new Vector3d(rawDirection.x, 0.0d, rawDirection.z);
            if (forward.lengthSquared() >= 0.0001d) {
                forward.normalize(DIVE_FORWARD_ASSIST);
                impulse.add(forward);
            }
        }

        return new DiveContext(world, impulse);
    }

    private static void applyDive(Player player, World expectedWorld, Vector3d impulse) {
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

        DruidSoundFeedback.playForPlayer(
                player,
                DIVE_SOUND_EVENT_ID,
                DIVE_SOUND_EVENT_ID,
                DIVE_SOUND_VOLUME_MODIFIER
        );
        Velocity velocity = store.ensureAndGetComponent(ref, Velocity.getComponentType());
        velocity.addInstruction(new Vector3d(impulse), createVelocityConfig(), ChangeVelocityType.Set);
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
            DruidAbilityCooldownHotbarHud.remove(player, DIVE_ITEM_ID);
            return;
        }
        DruidAbilityCooldownHotbarHud.showOrUpdate(
                player,
                slotIndex,
                DIVE_ITEM_ID,
                remainingMillis,
                DIVE_COOLDOWN_MILLIS
        );
    }

    private record DiveContext(World world, Vector3d impulse) {
    }
}
