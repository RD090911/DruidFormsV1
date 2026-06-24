package me.druid.v1;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionSyncData;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.SelectedHitEntity;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.VelocityThresholdStyle;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.splitvelocity.VelocityConfig;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.druid.v1.forms.FormId;
import org.joml.Vector3d;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class ProwlerPounceAbilityService {
    static final String POUNCE_ITEM_ID = "Tiger_Pounce";
    static final long POUNCE_COOLDOWN_MILLIS = 12_000L;

    private static final int POUNCE_FALLBACK_SLOT_INDEX = 2;
    private static final long POUNCE_DAMAGE_TIMEOUT_MILLIS = 2_000L;
    private static final float NORMAL_DAMAGE = 16.0f;
    private static final float AMBUSH_DAMAGE = 24.0f;
    private static final double FORWARD_SPEED = 17.0;
    private static final double UPWARD_SPEED = 11.0;
    private static final Map<UUID, Long> COOLDOWN_END_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingPounceDamage> PENDING_DAMAGE_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, ScheduledFuture<?>> PENDING_CLEAR_TASK_BY_PLAYER = new ConcurrentHashMap<>();

    private ProwlerPounceAbilityService() {
    }

    static boolean tryStart(Player player, int activeHotbarSlot, int chainId, boolean wasStealthedAtPounceStart) {
        PounceContext context = resolvePounceContext(player);
        if (context == null) {
            System.out.println("[ProwlerPounce] blocked reason=invalid-form-or-movement-state");
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
            return nowMillis + POUNCE_COOLDOWN_MILLIS;
        });

        long remainingMillis = cooldownEndMillis - nowMillis;
        int hudSlotIndex = activeHotbarSlot >= 0 ? activeHotbarSlot : POUNCE_FALLBACK_SLOT_INDEX;
        if (!started[0]) {
            System.out.println("[ProwlerPounce] blocked remainingMs="
                    + remainingMillis + " slot=" + hudSlotIndex);
            showCooldownHud(player, hudSlotIndex, remainingMillis);
            return false;
        }

        try {
            if (!context.world.isAlive()) {
                COOLDOWN_END_BY_PLAYER.remove(playerUuid, cooldownEndMillis);
                System.out.println("[ProwlerPounce] blocked reason=world-unavailable");
                return false;
            }
            armPendingDamage(playerUuid, chainId, wasStealthedAtPounceStart);
            context.world.execute(() -> applyPounce(player, context.world, context.impulse));
        } catch (RuntimeException exception) {
            COOLDOWN_END_BY_PLAYER.remove(playerUuid, cooldownEndMillis);
            clearPendingDamage(playerUuid, "start-failed");
            System.out.println("[ProwlerPounce] blocked reason=world-rejected-task");
            return false;
        }

        showCooldownHud(player, hudSlotIndex, remainingMillis);
        return true;
    }

    static void handleInteractionUpdate(UUID playerUuid, SyncInteractionChain chain) {
        if (playerUuid == null || chain == null) {
            return;
        }

        PendingPounceDamage pending = PENDING_DAMAGE_BY_PLAYER.get(playerUuid);
        if (pending == null) {
            return;
        }

        long nowMillis = System.currentTimeMillis();
        if (pending.expiresAtMillis < nowMillis) {
            clearPendingDamage(playerUuid, "timeout");
            return;
        }

        if (pending.chainId != chain.chainId) {
            return;
        }

        int[] targetNetworkIds = extractTargetNetworkIds(chain.interactionData);
        if (targetNetworkIds.length > 0) {
            Player player = DruidPermissions.getOnlinePlayer(playerUuid);
            if (player == null) {
                clearPendingDamage(playerUuid, "player-unavailable");
                return;
            }

            clearPendingDamage(playerUuid, "consumed");
            applyDamageFromPacket(player, pending.damageAmount, targetNetworkIds);
            return;
        }

        if (isTerminalState(chain.state)) {
            clearPendingDamage(playerUuid, "finished-without-hit");
        }
    }

    static void cleanupPlayer(Player player, String reason) {
        UUID playerUuid = player == null ? null : player.getUuid();
        if (playerUuid != null) {
            clearPendingDamage(playerUuid, reason);
        }
    }

    static void handleDisconnect(PlayerRef playerRef) {
        UUID playerUuid = playerRef == null ? null : playerRef.getUuid();
        if (playerUuid != null) {
            clearPendingDamage(playerUuid, "disconnect");
        }
    }

    static void shutdown() {
        PENDING_DAMAGE_BY_PLAYER.clear();
        for (ScheduledFuture<?> task : PENDING_CLEAR_TASK_BY_PLAYER.values()) {
            if (task != null) {
                task.cancel(false);
            }
        }
        PENDING_CLEAR_TASK_BY_PLAYER.clear();
    }

    private static PounceContext resolvePounceContext(Player player) {
        if (player == null || ShapeshiftHandler.getActiveFormId(player) != FormId.FORM_PROWLER) {
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
        if (states == null
                || !states.onGround
                || states.inFluid
                || states.flying
                || states.gliding
                || states.mounting) {
            return null;
        }

        TransformComponent transformComponent =
                store.getComponentConcurrent(ref, TransformComponent.getComponentType());
        Transform transform = transformComponent == null ? null : transformComponent.getTransform();
        if (transform == null) {
            return null;
        }

        Vector3d direction = transform.getDirection();
        direction.y = 0.0;
        if (direction.lengthSquared() < 0.0001) {
            return null;
        }
        direction.normalize(FORWARD_SPEED);
        direction.y = UPWARD_SPEED;
        return new PounceContext(world, direction);
    }

    private static void applyPounce(Player player, World expectedWorld, Vector3d impulse) {
        if (player == null
                || expectedWorld == null
                || !expectedWorld.isAlive()
                || player.getWorld() != expectedWorld
                || impulse == null
                || ShapeshiftHandler.getActiveFormId(player) != FormId.FORM_PROWLER) {
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
        if (states == null
                || !states.onGround
                || states.inFluid
                || states.flying
                || states.gliding
                || states.mounting) {
            return;
        }

        Velocity velocity = store.ensureAndGetComponent(ref, Velocity.getComponentType());
        velocity.addInstruction(new Vector3d(impulse), createVelocityConfig(), ChangeVelocityType.Set);
    }

    private static void armPendingDamage(UUID playerUuid, int chainId, boolean wasStealthedAtPounceStart) {
        if (playerUuid == null) {
            return;
        }
        float damageAmount = wasStealthedAtPounceStart ? AMBUSH_DAMAGE : NORMAL_DAMAGE;
        long expiresAtMillis = System.currentTimeMillis() + POUNCE_DAMAGE_TIMEOUT_MILLIS;
        PENDING_DAMAGE_BY_PLAYER.put(playerUuid, new PendingPounceDamage(chainId, damageAmount, expiresAtMillis));
        ScheduledFuture<?> previousTask = PENDING_CLEAR_TASK_BY_PLAYER.remove(playerUuid);
        if (previousTask != null) {
            previousTask.cancel(false);
        }
        ScheduledFuture<?> clearTask = HytaleServer.SCHEDULED_EXECUTOR.schedule(
                () -> expirePendingDamage(playerUuid, chainId),
                POUNCE_DAMAGE_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS
        );
        PENDING_CLEAR_TASK_BY_PLAYER.put(playerUuid, clearTask);
    }

    private static void clearPendingDamage(UUID playerUuid, String reason) {
        if (playerUuid == null) {
            return;
        }
        PENDING_DAMAGE_BY_PLAYER.remove(playerUuid);
        ScheduledFuture<?> clearTask = PENDING_CLEAR_TASK_BY_PLAYER.remove(playerUuid);
        if (clearTask != null) {
            clearTask.cancel(false);
        }
    }

    private static void expirePendingDamage(UUID playerUuid, int chainId) {
        if (playerUuid == null) {
            return;
        }
        PendingPounceDamage pending = PENDING_DAMAGE_BY_PLAYER.get(playerUuid);
        if (pending == null || pending.chainId != chainId) {
            return;
        }
        clearPendingDamage(playerUuid, "timeout");
    }

    private static boolean isTerminalState(InteractionState state) {
        return state == InteractionState.Finished
                || state == InteractionState.Failed
                || state == InteractionState.Skip
                || state == InteractionState.ItemChanged;
    }

    private static int[] extractTargetNetworkIds(InteractionSyncData[] interactionData) {
        if (interactionData == null || interactionData.length == 0) {
            return new int[0];
        }

        LinkedHashSet<Integer> targetNetworkIds = new LinkedHashSet<>();
        for (InteractionSyncData state : interactionData) {
            if (state == null || state.hitEntities == null || state.hitEntities.length == 0) {
                continue;
            }
            for (SelectedHitEntity hitEntity : state.hitEntities) {
                if (hitEntity == null || hitEntity.networkId <= 0) {
                    continue;
                }
                targetNetworkIds.add(hitEntity.networkId);
            }
        }

        int[] networkIds = new int[targetNetworkIds.size()];
        int index = 0;
        for (Integer networkId : targetNetworkIds) {
            networkIds[index++] = networkId;
        }
        return networkIds;
    }

    private static void applyDamageFromPacket(Player player, float damageAmount, int[] targetNetworkIds) {
        if (player == null || targetNetworkIds == null || targetNetworkIds.length == 0) {
            return;
        }

        World world = player.getWorld();
        if (world == null || !world.isAlive()) {
            return;
        }

        try {
            world.execute(() -> applyDamageOnWorldThread(player, damageAmount, targetNetworkIds));
        } catch (RuntimeException exception) {
            System.out.println("[ProwlerPounce] damage skipped reason=world-rejected-task");
        }
    }

    private static void applyDamageOnWorldThread(Player player, float damageAmount, int[] targetNetworkIds) {
        if (player == null
                || damageAmount <= 0.0f
                || targetNetworkIds == null
                || targetNetworkIds.length == 0
                || ShapeshiftHandler.getActiveFormId(player) != FormId.FORM_PROWLER) {
            return;
        }

        Ref<EntityStore> sourceRef = player.getReference();
        if (sourceRef == null || !sourceRef.isValid()) {
            return;
        }

        World world = player.getWorld();
        if (world == null || !world.isAlive()) {
            return;
        }

        EntityStore entityStore = world.getEntityStore();
        if (entityStore == null) {
            return;
        }

        Store<EntityStore> store = entityStore.getStore();
        if (store == null) {
            return;
        }

        Damage.Source source = new Damage.EntitySource(sourceRef);
        for (int targetNetworkId : targetNetworkIds) {
            Ref<EntityStore> targetRef = entityStore.getRefFromNetworkId(targetNetworkId);
            if (targetRef == null || !targetRef.isValid() || targetRef.equals(sourceRef)) {
                continue;
            }
            store.invoke(targetRef, new Damage(source, DamageCause.PHYSICAL, damageAmount));
        }
    }

    private static VelocityConfig createVelocityConfig() {
        VelocityConfig config = new VelocityConfig();
        config.setGroundResistance(0.94f);
        config.setGroundResistanceMax(0.3f);
        config.setAirResistance(0.99f);
        config.setAirResistanceMax(0.98f);
        config.setThreshold(3.0f);
        config.setStyle(VelocityThresholdStyle.Linear);
        return config;
    }

    private static void showCooldownHud(Player player, int slotIndex, long remainingMillis) {
        if (remainingMillis <= 0L) {
            DruidAbilityCooldownHotbarHud.remove(player, POUNCE_ITEM_ID);
            return;
        }
        DruidAbilityCooldownHotbarHud.showOrUpdate(
                player,
                slotIndex,
                POUNCE_ITEM_ID,
                remainingMillis,
                POUNCE_COOLDOWN_MILLIS
        );
    }

    private record PounceContext(World world, Vector3d impulse) {
    }

    private record PendingPounceDamage(int chainId, float damageAmount, long expiresAtMillis) {
    }
}
