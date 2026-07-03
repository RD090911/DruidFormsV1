package me.druid.v1;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.protocol.VelocityThresholdStyle;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.splitvelocity.VelocityConfig;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.asset.builder.StateMappingHelper;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import me.druid.v1.forms.FormId;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class GuardianTerrifyingRoarAbilityService {
    static final String TERRIFYING_ROAR_ITEM_ID = "Guardian_Terrifying_Roar";
    static final String VERDANT_TERRIFYING_ROAR_ITEM_ID = "Verdant_Guardian_Terrifying_Roar";
    static final String PRIMAL_TERRIFYING_ROAR_ITEM_ID = "Primal_Guardian_Terrifying_Roar";
    static final String ELDER_TERRIFYING_ROAR_ITEM_ID = "Elder_Guardian_Terrifying_Roar";
    static final long TERRIFYING_ROAR_COOLDOWN_MILLIS = 30_000L;
    static final double TERRIFYING_ROAR_RADIUS_BLOCKS = 28.0d;
    static final double TERRIFYING_ROAR_DURATION_SECONDS = 4.0d;

    private static final String LOCKED_TARGET_SLOT = "LockedTarget";
    private static final String FLEE_STATE = "Flee";
    private static final String PANIC_STATE = "Panic";
    private static final String ALERTED_STATE = "Alerted";
    private static final long VERIFY_DELAY_MILLIS = 350L;
    private static final long RESTORE_DELAY_MILLIS = (long) (TERRIFYING_ROAR_DURATION_SECONDS * 1000.0d);
    private static final long FEAR_DISABLE_PULSE_INTERVAL_MILLIS = 500L;
    private static final int FEAR_DISABLE_PULSE_COUNT = 7;
    private static final int TERRIFYING_ROAR_FALLBACK_SLOT_INDEX = 4;
    private static final double VELOCITY_FALLBACK_SPEED = 8.0d;
    private static final boolean DEBUG = Boolean.getBoolean("druid.terrifyingRoar.debug");
    private static final Map<UUID, Long> COOLDOWN_END_BY_PLAYER = new ConcurrentHashMap<>();

    private GuardianTerrifyingRoarAbilityService() {
    }

    static boolean tryStart(Player player, int activeHotbarSlot, String itemId) {
        String cooldownItemId = isTerrifyingRoarItemId(itemId) ? itemId : TERRIFYING_ROAR_ITEM_ID;
        debug("detected player=" + describePlayer(player)
                + " form=" + ShapeshiftHandler.getActiveFormId(player)
                + " item=" + cooldownItemId
                + " slot=" + activeHotbarSlot);

        if (!isEligibleGuardian(player)) {
            System.out.println("[GuardianTerrifyingRoar] blocked reason=invalid-form-or-tier");
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
            return nowMillis + TERRIFYING_ROAR_COOLDOWN_MILLIS;
        });

        long remainingMillis = cooldownEndMillis - nowMillis;
        int hudSlotIndex = activeHotbarSlot >= 0 ? activeHotbarSlot : TERRIFYING_ROAR_FALLBACK_SLOT_INDEX;
        if (!started[0]) {
            debug("cooldown-denied player=" + describePlayer(player)
                    + " remainingMs=" + remainingMillis
                    + " slot=" + hudSlotIndex);
            showCooldownHud(player, hudSlotIndex, cooldownItemId, remainingMillis);
            return false;
        }

        World world = player.getWorld();
        if (world == null || !world.isAlive()) {
            COOLDOWN_END_BY_PLAYER.remove(playerUuid, cooldownEndMillis);
            System.out.println("[GuardianTerrifyingRoar] blocked reason=world-unavailable");
            return false;
        }

        try {
            world.execute(() -> applyFearOnWorldThread(player, world));
        } catch (RuntimeException exception) {
            COOLDOWN_END_BY_PLAYER.remove(playerUuid, cooldownEndMillis);
            System.out.println("[GuardianTerrifyingRoar] blocked reason=world-rejected-task");
            return false;
        }

        debug("activated player=" + describePlayer(player)
                + " cooldownMs=" + TERRIFYING_ROAR_COOLDOWN_MILLIS
                + " radius=" + TERRIFYING_ROAR_RADIUS_BLOCKS
                + " durationSeconds=" + TERRIFYING_ROAR_DURATION_SECONDS);
        showCooldownHud(player, hudSlotIndex, cooldownItemId, remainingMillis);
        return true;
    }

    static boolean isTerrifyingRoarItemId(String itemId) {
        return TERRIFYING_ROAR_ITEM_ID.equals(itemId)
                || VERDANT_TERRIFYING_ROAR_ITEM_ID.equals(itemId)
                || PRIMAL_TERRIFYING_ROAR_ITEM_ID.equals(itemId)
                || ELDER_TERRIFYING_ROAR_ITEM_ID.equals(itemId);
    }

    private static boolean isEligibleGuardian(Player player) {
        return player != null && ShapeshiftHandler.getActiveFormId(player) == FormId.FORM_GUARDIAN;
    }

    private static void applyFearOnWorldThread(Player player, World expectedWorld) {
        if (player == null
                || expectedWorld == null
                || !expectedWorld.isAlive()
                || player.getWorld() != expectedWorld
                || !isEligibleGuardian(player)) {
            return;
        }

        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        EntityStore entityStore = expectedWorld.getEntityStore();
        Store<EntityStore> store = entityStore == null ? null : entityStore.getStore();
        if (store == null) {
            return;
        }

        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        Vector3d playerPosition = playerTransform == null ? null : playerTransform.getPosition();
        if (playerPosition == null) {
            debug("scan-skipped reason=missing-player-position player=" + describePlayer(player));
            return;
        }

        List<Ref<EntityStore>> targetUtilRefs = safeGetAllEntitiesInSphere(playerPosition, store);
        List<NearbyNpc> nearbyNpcs = collectNearbyNpcs(playerRef, playerPosition, store);
        debug("scan player=" + describePlayer(player)
                + " position=" + formatPosition(playerPosition)
                + " radius=" + TERRIFYING_ROAR_RADIUS_BLOCKS
                + " sphereEntities=" + (targetUtilRefs == null ? -1 : targetUtilRefs.size())
                + " nearbyNpcs=" + nearbyNpcs.size());

        int withRole = 0;
        int friendly = 0;
        int nonFriendly = 0;
        int eligible = 0;
        int fleeStateSuccess = 0;
        int panicStateSuccess = 0;
        int alertedFallback = 0;
        int velocityFallback = 0;
        int fearDisableSuccess = 0;
        int affected = 0;
        int friendlySkipped = 0;
        List<FearApplication> applications = new ArrayList<>();
        for (NearbyNpc nearbyNpc : nearbyNpcs) {
            Ref<EntityStore> npcRef = nearbyNpc.ref();
            NPCEntity npc = nearbyNpc.npc();
            Role role = npc.getRole();
            boolean hasRole = role != null;
            if (hasRole) {
                withRole++;
            }

            boolean isFriendly = false;
            boolean friendlyKnown = false;
            if (role != null) {
                try {
                    isFriendly = role.isFriendly(playerRef, store);
                    friendlyKnown = true;
                    if (isFriendly) {
                        friendly++;
                    } else {
                        nonFriendly++;
                    }
                } catch (RuntimeException exception) {
                    debug("friendly-check-failed npc=" + describeNpc(npc)
                            + " ref=" + npcRef
                            + " reason=" + exception.getClass().getSimpleName());
                }
            }

            debug("candidate npc=" + describeNpc(npc)
                    + " ref=" + npcRef
                    + " distance=" + String.format(java.util.Locale.ROOT, "%.2f", nearbyNpc.distance())
                    + " rolePresent=" + hasRole
                    + " friendlyKnown=" + friendlyKnown
                    + " friendly=" + isFriendly);

            if (role == null) {
                continue;
            }
            if (friendlyKnown && isFriendly) {
                friendlySkipped++;
                debug("candidate-skipped reason=friendly npc=" + describeNpc(npc)
                        + " ref=" + npcRef);
                continue;
            }

            eligible++;
            FearAttemptResult result = forceNpcFlee(npcRef, npc, role, playerRef, playerPosition, nearbyNpc.position(), store);
            if (result.fleeStateSucceeded()) {
                fleeStateSuccess++;
            }
            if (result.panicStateSucceeded()) {
                panicStateSuccess++;
            }
            if (result.alertedFallbackSucceeded()) {
                alertedFallback++;
            }
            if (result.velocityFallbackSucceeded()) {
                velocityFallback++;
            }
            if (result.fearDisableSucceeded()) {
                fearDisableSuccess++;
            }
            if (result.affected()) {
                affected++;
                applications.add(result.application());
            }
        }

        debug("summary player=" + describePlayer(player)
                + " sphereEntities=" + (targetUtilRefs == null ? -1 : targetUtilRefs.size())
                + " nearbyNpcs=" + nearbyNpcs.size()
                + " withRole=" + withRole
                + " eligible=" + eligible
                + " nonFriendly=" + nonFriendly
                + " friendly=" + friendly
                + " friendlySkipped=" + friendlySkipped
                + " fleeStateSuccess=" + fleeStateSuccess
                + " panicStateSuccess=" + panicStateSuccess
                + " alertedFallback=" + alertedFallback
                + " trueFleeConfirmed=0"
                + " fearDisableSuccess=" + fearDisableSuccess
                + " velocityFallback=" + velocityFallback
                + " pulseCount=" + FEAR_DISABLE_PULSE_COUNT
                + " affected=" + affected);
        System.out.println("[GuardianTerrifyingRoar] activated player=" + describePlayerName(player)
                + " radius=" + String.format(java.util.Locale.ROOT, "%.1f", TERRIFYING_ROAR_RADIUS_BLOCKS)
                + " nearbyNpcs=" + nearbyNpcs.size()
                + " eligible=" + eligible
                + " fleeStateSuccess=" + fleeStateSuccess
                + " panicStateSuccess=" + panicStateSuccess
                + " trueFleeConfirmed=0"
                + " fearDisableSuccess=" + fearDisableSuccess
                + " velocityFallback=" + velocityFallback
                + " affected=" + affected);

        scheduleImmediateVerification(describePlayerName(player), playerRef, expectedWorld, applications);
        scheduleFearDisablePulses(describePlayerName(player), playerRef, expectedWorld, applications);
        scheduleTargetRestore(describePlayerName(player), playerRef, expectedWorld, applications);
    }

    private static List<Ref<EntityStore>> safeGetAllEntitiesInSphere(
            Vector3d playerPosition,
            Store<EntityStore> store
    ) {
        try {
            return TargetUtil.getAllEntitiesInSphere(
                    playerPosition,
                    TERRIFYING_ROAR_RADIUS_BLOCKS,
                    store
            );
        } catch (RuntimeException exception) {
            debug("sphere-scan-failed reason=" + exception.getClass().getSimpleName());
            return null;
        }
    }

    private static List<NearbyNpc> collectNearbyNpcs(
            Ref<EntityStore> playerRef,
            Vector3d playerPosition,
            Store<EntityStore> store
    ) {
        List<NearbyNpc> nearbyNpcs = new ArrayList<>();
        double radiusSquared = TERRIFYING_ROAR_RADIUS_BLOCKS * TERRIFYING_ROAR_RADIUS_BLOCKS;
        store.forEachChunk(Query.any(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> ignored) -> {
            if (chunk == null || !chunk.getArchetype().contains(NPCEntity.getComponentType())) {
                return;
            }

            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                Ref<EntityStore> npcRef = chunk.getReferenceTo(i);
                if (npcRef == null || !npcRef.isValid() || npcRef.equals(playerRef)) {
                    continue;
                }
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                TransformComponent npcTransform = chunk.getComponent(i, TransformComponent.getComponentType());
                Vector3d npcPosition = npcTransform == null ? null : npcTransform.getPosition();
                if (npc == null || npcPosition == null) {
                    continue;
                }
                double distanceSquared = playerPosition.distanceSquared(npcPosition);
                if (distanceSquared <= radiusSquared) {
                    nearbyNpcs.add(new NearbyNpc(npcRef, npc, new Vector3d(npcPosition), Math.sqrt(distanceSquared)));
                }
            }
        });
        return nearbyNpcs;
    }

    private static FearAttemptResult forceNpcFlee(
            Ref<EntityStore> npcRef,
            NPCEntity npc,
            Role role,
            Ref<EntityStore> playerRef,
            Vector3d playerPosition,
            Vector3d npcPosition,
            Store<EntityStore> store
    ) {
        String stateBefore = describeRoleState(role);
        String targetBefore = describeTargetSlots(role, playerRef);
        MarkedTargetSnapshot previousTarget = snapshotLockedTarget(role, playerRef);
        List<TargetSlotSnapshot> previousTargets = snapshotAllTargets(role);
        boolean targetSet = setLockedTarget(role, playerRef, store);
        boolean instructionsReset = resetInstructions(role, npc);
        boolean fleeStateSet = trySetStateIfSupported(npcRef, npc, role, store, FLEE_STATE);
        boolean panicStateSet = false;
        boolean alertedStateSet = false;
        boolean velocityFallback = false;
        boolean fearDisable;
        boolean targetNeutralized;

        if (!fleeStateSet) {
            panicStateSet = trySetStateIfSupported(npcRef, npc, role, store, PANIC_STATE);
        }
        overrideAttitudeIgnore(role, npc, playerRef);
        targetNeutralized = clearAllMarkedTargetsDuringFear(role) > 0;
        fearDisable = applyFearDisable(npc, role);
        if (!fleeStateSet && !panicStateSet) {
            alertedStateSet = trySetStateIfSupported(npcRef, npc, role, store, ALERTED_STATE);
            if (!fearDisable) {
                velocityFallback = applyVelocityFallback(npc, role, playerPosition, npcPosition);
            }
        }

        requestNewPath(role, npc);
        boolean sensorNotified = notifySensorMatch(role, npc);

        String stateAfter = describeRoleState(role);
        String targetAfter = describeTargetSlots(role, playerRef);
        debug("apply npc=" + describeNpc(npc)
                + " ref=" + npcRef
                + " stateBefore=" + stateBefore
                + " stateAfter=" + stateAfter
                + " targetBefore=" + targetBefore
                + " targetAfter=" + targetAfter
                + " previousTarget=" + previousTarget.describe(playerRef)
                + " targetSet=" + targetSet
                + " reset=" + instructionsReset
                + " fleeState=" + fleeStateSet
                + " panicState=" + panicStateSet
                + " alertedFallback=" + alertedStateSet
                + " velocityFallback=" + velocityFallback
                + " fearDisable=" + fearDisable
                + " targetNeutralized=" + targetNeutralized
                + " sensorNotified=" + sensorNotified);

        boolean affected = fleeStateSet || panicStateSet || fearDisable || velocityFallback || alertedStateSet;
        double initialDistance = playerPosition == null || npcPosition == null ? -1.0d : playerPosition.distance(npcPosition);
        FearApplication application = new FearApplication(
                npcRef,
                previousTarget,
                previousTargets,
                initialDistance,
                velocityFallback
        );
        return new FearAttemptResult(
                fleeStateSet,
                panicStateSet,
                alertedStateSet,
                velocityFallback,
                fearDisable,
                affected,
                application
        );
    }

    private static MarkedTargetSnapshot snapshotLockedTarget(Role role, Ref<EntityStore> playerRef) {
        MarkedEntitySupport support = role == null ? null : role.getMarkedEntitySupport();
        if (support == null) {
            return MarkedTargetSnapshot.missingSlot();
        }
        try {
            int slotIndex = findTargetSlotIndex(support, LOCKED_TARGET_SLOT);
            if (slotIndex < 0) {
                return MarkedTargetSnapshot.missingSlot();
            }
            Ref<EntityStore> previousRef = support.getMarkedEntityRef(slotIndex);
            return new MarkedTargetSnapshot(slotIndex, previousRef, previousRef != null && previousRef.isValid());
        } catch (RuntimeException exception) {
            return MarkedTargetSnapshot.missingSlot();
        }
    }

    private static List<TargetSlotSnapshot> snapshotAllTargets(Role role) {
        MarkedEntitySupport support = role == null ? null : role.getMarkedEntitySupport();
        if (support == null) {
            return List.of();
        }
        List<TargetSlotSnapshot> snapshots = new ArrayList<>();
        try {
            int slotCount = support.getMarkedEntitySlotCount();
            for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
                Ref<EntityStore> targetRef = support.getMarkedEntityRef(slotIndex);
                snapshots.add(new TargetSlotSnapshot(
                        slotIndex,
                        support.getSlotName(slotIndex),
                        targetRef,
                        targetRef != null && targetRef.isValid()
                ));
            }
        } catch (RuntimeException exception) {
            debug("snapshotAllTargets-failed reason=" + exception.getClass().getSimpleName());
        }
        return snapshots;
    }

    private static boolean setLockedTarget(Role role, Ref<EntityStore> playerRef, Store<EntityStore> store) {
        if (role == null || playerRef == null || store == null) {
            return false;
        }
        try {
            role.setMarkedTarget(LOCKED_TARGET_SLOT, playerRef);
            role.getMarkedEntitySupport().setMarkedEntity(LOCKED_TARGET_SLOT, playerRef);
            role.getMarkedEntitySupport().flockSetTarget(LOCKED_TARGET_SLOT, playerRef, store);
            return true;
        } catch (RuntimeException exception) {
            debug("setLockedTarget-failed reason=" + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean resetInstructions(Role role, NPCEntity npc) {
        try {
            role.resetAllInstructions();
            return true;
        } catch (RuntimeException exception) {
            debug("resetInstructions-failed npc=" + describeNpc(npc)
                    + " reason=" + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean trySetStateIfSupported(
            Ref<EntityStore> npcRef,
            NPCEntity npc,
            Role role,
            Store<EntityStore> store,
            String state
    ) {
        if (!hasState(role, state)) {
            debug("setState-skipped npc=" + describeNpc(npc)
                    + " state=" + state
                    + " reason=unsupported");
            return false;
        }
        String before = describeRoleState(role);
        try {
            role.getStateSupport().setState(npcRef, state, null, store);
            role.resetAllInstructions();
            String after = describeRoleState(role);
            boolean accepted = after.equals(state) || after.startsWith(state + ".");
            debug("setState npc=" + describeNpc(npc)
                    + " requested=" + state
                    + " before=" + before
                    + " after=" + after
                    + " accepted=" + accepted);
            return accepted;
        } catch (RuntimeException exception) {
            debug("setState-failed npc=" + describeNpc(npc)
                    + " requested=" + state
                    + " reason=" + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean hasState(Role role, String state) {
        if (role == null || role.getStateSupport() == null || state == null) {
            return false;
        }
        try {
            StateMappingHelper helper = role.getStateSupport().getStateHelper();
            return helper != null && helper.getStateIndex(state) >= 0;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean applyVelocityFallback(
            NPCEntity npc,
            Role role,
            Vector3d playerPosition,
            Vector3d npcPosition
    ) {
        if (role == null || playerPosition == null || npcPosition == null) {
            return false;
        }
        Vector3d away = new Vector3d(npcPosition).sub(playerPosition);
        away.y = 0.0d;
        if (away.lengthSquared() < 0.0001d) {
            away.set(1.0d, 0.0d, 0.0d);
        }
        away.normalize(VELOCITY_FALLBACK_SPEED);
        try {
            role.addVelocity(away, createVelocityConfig());
            debug("velocityFallback npc=" + describeNpc(npc)
                    + " vector=" + formatPosition(away)
                    + " success=true");
            return true;
        } catch (RuntimeException exception) {
            debug("velocityFallback-failed npc=" + describeNpc(npc)
                    + " reason=" + exception.getClass().getSimpleName());
            return false;
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

    private static boolean applyFearDisable(NPCEntity npc, Role role) {
        if (role == null) {
            return false;
        }
        boolean velocityStopped = false;
        boolean instructionsReset = false;
        boolean motionCleared = false;
        try {
            role.setVelocity(new Vector3d(0.0d, 0.0d, 0.0d), createVelocityConfig(), true);
            velocityStopped = true;
        } catch (RuntimeException exception) {
            debug("fearDisable-stopVelocity-failed npc=" + describeNpc(npc)
                    + " reason=" + exception.getClass().getSimpleName());
        }
        try {
            role.resetAllInstructions();
            instructionsReset = true;
        } catch (RuntimeException exception) {
            debug("fearDisable-reset-failed npc=" + describeNpc(npc)
                    + " reason=" + exception.getClass().getSimpleName());
        }
        try {
            if (role.getStateSupport() != null) {
                role.getStateSupport().setClearBodyMotion(true);
                role.getStateSupport().setClearHeadMotion(true);
                motionCleared = true;
            }
        } catch (RuntimeException exception) {
            debug("fearDisable-clearMotion-failed npc=" + describeNpc(npc)
                    + " reason=" + exception.getClass().getSimpleName());
        }
        try {
            if (role.getCombatSupport() != null) {
                role.getCombatSupport().clearAttackOverrides();
            }
        } catch (RuntimeException exception) {
            debug("fearDisable-clearCombat-failed npc=" + describeNpc(npc)
                    + " reason=" + exception.getClass().getSimpleName());
        }
        debug("fearDisable npc=" + describeNpc(npc)
                + " velocityStopped=" + velocityStopped
                + " instructionsReset=" + instructionsReset
                + " motionCleared=" + motionCleared);
        return velocityStopped || instructionsReset || motionCleared;
    }

    private static boolean overrideAttitudeIgnore(Role role, NPCEntity npc, Ref<EntityStore> playerRef) {
        WorldSupport worldSupport = role == null ? null : role.getWorldSupport();
        if (worldSupport == null || playerRef == null) {
            return false;
        }
        try {
            worldSupport.overrideAttitude(playerRef, Attitude.IGNORE, TERRIFYING_ROAR_DURATION_SECONDS);
            worldSupport.tick(0.11f);
            return true;
        } catch (RuntimeException exception) {
            debug("overrideAttitudeIgnore-failed npc=" + describeNpc(npc)
                    + " reason=" + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static int clearAllMarkedTargetsDuringFear(Role role) {
        MarkedEntitySupport support = role == null ? null : role.getMarkedEntitySupport();
        if (support == null) {
            return 0;
        }
        try {
            int cleared = 0;
            int slotCount = support.getMarkedEntitySlotCount();
            for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
                Ref<EntityStore> current = support.getMarkedEntityRef(slotIndex);
                if (current != null && current.isValid()) {
                    support.clearMarkedEntity(slotIndex);
                    cleared++;
                }
            }
            return cleared;
        } catch (RuntimeException exception) {
            debug("clearAllMarkedTargetsDuringFear-failed reason=" + exception.getClass().getSimpleName());
            return 0;
        }
    }

    private static boolean requestNewPath(Role role, NPCEntity npc) {
        WorldSupport worldSupport = role == null ? null : role.getWorldSupport();
        if (worldSupport == null) {
            return false;
        }
        try {
            worldSupport.requestNewPath();
            return true;
        } catch (RuntimeException exception) {
            debug("requestNewPath-failed npc=" + describeNpc(npc)
                    + " reason=" + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean notifySensorMatch(Role role, NPCEntity npc) {
        try {
            role.notifySensorMatch();
            return true;
        } catch (RuntimeException exception) {
            debug("notifySensorMatch-failed npc=" + describeNpc(npc)
                    + " reason=" + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static void scheduleImmediateVerification(
            String playerName,
            Ref<EntityStore> playerRef,
            World world,
            List<FearApplication> applications
    ) {
        if (playerRef == null || world == null || applications == null || applications.isEmpty()) {
            return;
        }
        List<FearApplication> applicationsToVerify = List.copyOf(applications);
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            try {
                world.execute(() -> verifyFearOnWorldThread(
                        playerName,
                        playerRef,
                        world,
                        applicationsToVerify,
                        false
                ));
            } catch (RuntimeException exception) {
                System.out.println("[GuardianTerrifyingRoar] verify failed reason=world-rejected-task player="
                        + safeText(playerName));
            }
        }, VERIFY_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static void scheduleFearDisablePulses(
            String playerName,
            Ref<EntityStore> playerRef,
            World world,
            List<FearApplication> applications
    ) {
        if (playerRef == null || world == null || applications == null || applications.isEmpty()) {
            return;
        }
        List<FearApplication> applicationsToPulse = List.copyOf(applications);
        for (int pulse = 1; pulse <= FEAR_DISABLE_PULSE_COUNT; pulse++) {
            long delayMillis = pulse * FEAR_DISABLE_PULSE_INTERVAL_MILLIS;
            int pulseIndex = pulse;
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                try {
                    world.execute(() -> pulseFearDisableOnWorldThread(
                            playerName,
                            playerRef,
                            world,
                            applicationsToPulse,
                            pulseIndex
                    ));
                } catch (RuntimeException exception) {
                    System.out.println("[GuardianTerrifyingRoar] pulse failed reason=world-rejected-task player="
                            + safeText(playerName)
                            + " pulse=" + pulseIndex);
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private static void pulseFearDisableOnWorldThread(
            String playerName,
            Ref<EntityStore> playerRef,
            World expectedWorld,
            List<FearApplication> applications,
            int pulseIndex
    ) {
        Store<EntityStore> store = getStore(expectedWorld);
        if (store == null || playerRef == null || !playerRef.isValid()) {
            return;
        }

        int disabled = 0;
        int checked = 0;
        for (FearApplication application : applications) {
            if (application == null || application.npcRef() == null || !application.npcRef().isValid()) {
                continue;
            }
            NPCEntity npc = store.getComponent(application.npcRef(), NPCEntity.getComponentType());
            Role role = npc == null ? null : npc.getRole();
            if (role == null) {
                continue;
            }
            checked++;
            overrideAttitudeIgnore(role, npc, playerRef);
            clearAllMarkedTargetsDuringFear(role);
            if (applyFearDisable(npc, role)) {
                disabled++;
            }
        }

        debug("pulse player=" + safeText(playerName)
                + " pulse=" + pulseIndex
                + " checked=" + checked
                + " fearDisableSuccess=" + disabled);
    }

    private static void scheduleTargetRestore(
            String playerName,
            Ref<EntityStore> playerRef,
            World world,
            List<FearApplication> applications
    ) {
        if (playerRef == null || world == null || applications == null || applications.isEmpty()) {
            return;
        }
        List<FearApplication> applicationsToRestore = List.copyOf(applications);
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            try {
                world.execute(() -> {
                    restoreTargetsOnWorldThread(playerName, playerRef, world, applicationsToRestore);
                    verifyFearOnWorldThread(playerName, playerRef, world, applicationsToRestore, true);
                });
            } catch (RuntimeException exception) {
                System.out.println("[GuardianTerrifyingRoar] restore failed reason=world-rejected-task player="
                        + safeText(playerName));
            }
        }, RESTORE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static void restoreTargetsOnWorldThread(
            String playerName,
            Ref<EntityStore> playerRef,
            World expectedWorld,
            List<FearApplication> applications
    ) {
        Store<EntityStore> store = getStore(expectedWorld);
        if (store == null || playerRef == null || !playerRef.isValid()) {
            return;
        }
        for (FearApplication application : applications) {
            if (application == null || application.npcRef() == null || !application.npcRef().isValid()) {
                continue;
            }
            NPCEntity npc = store.getComponent(application.npcRef(), NPCEntity.getComponentType());
            Role role = npc == null ? null : npc.getRole();
            boolean restored = restoreAllTargets(role, application.previousTargets(), playerRef);
            debug("restore npc=" + describeNpc(npc)
                    + " ref=" + application.npcRef()
                    + " restoredOrCleared=" + restored
                    + " previousTarget=" + application.previousTarget().describe(playerRef)
                    + " player=" + safeText(playerName));
        }
    }

    private static void verifyFearOnWorldThread(
            String playerName,
            Ref<EntityStore> playerRef,
            World expectedWorld,
            List<FearApplication> applications,
            boolean afterRestore
    ) {
        Store<EntityStore> store = getStore(expectedWorld);
        if (store == null || playerRef == null || !playerRef.isValid()) {
            return;
        }

        int checked = 0;
        int trueFleeing = 0;
        int fearDisabled = 0;
        int targetClearedOrRestored = 0;
        int movedAway = 0;
        int velocityFallback = 0;
        for (FearApplication application : applications) {
            if (application == null || application.npcRef() == null || !application.npcRef().isValid()) {
                continue;
            }
            NPCEntity npc = store.getComponent(application.npcRef(), NPCEntity.getComponentType());
            Role role = npc == null ? null : npc.getRole();
            if (role == null) {
                continue;
            }
            checked++;
            String state = describeRoleState(role);
            boolean inFleeOrPanic = state.startsWith(FLEE_STATE) || state.startsWith(PANIC_STATE);
            if (inFleeOrPanic) {
                trueFleeing++;
            }
            boolean targetOk = afterRestore
                    ? areTargetsClearedOrRestored(role, application.previousTargets(), playerRef)
                    : areTargetsCleared(role);
            if (targetOk) {
                targetClearedOrRestored++;
            }
            double currentDistance = distanceFromPlayer(store, playerRef, application.npcRef());
            boolean movedFarther = application.initialDistance() >= 0.0d && currentDistance > application.initialDistance() + 0.25d;
            if (movedFarther) {
                movedAway++;
            }
            if (application.velocityFallbackUsed()) {
                velocityFallback++;
            }
            boolean disabled = targetOk && !isExecutingAttack(role) && !isPathFollowing(npc);
            if (disabled) {
                fearDisabled++;
            }
            debug("verify npc=" + describeNpc(npc)
                    + " ref=" + application.npcRef()
                    + " afterRestore=" + afterRestore
                    + " state=" + state
                    + " targetSlots=" + describeTargetSlots(role, playerRef)
                    + " trueFleeing=" + inFleeOrPanic
                    + " fearDisabled=" + disabled
                    + " targetClearedOrRestored=" + targetOk
                    + " initialDistance=" + String.format(java.util.Locale.ROOT, "%.2f", application.initialDistance())
                    + " currentDistance=" + String.format(java.util.Locale.ROOT, "%.2f", currentDistance)
                    + " movedAway=" + movedFarther
                    + " pathFollowing=" + describePathFollowing(npc));
        }
        System.out.println("[GuardianTerrifyingRoar] verify player=" + safeText(playerName)
                + " checked=" + checked
                + " trueFleeing=" + trueFleeing
                + " fearDisabled=" + fearDisabled
                + " velocityFallback=" + velocityFallback
                + " targetClearedOrRestored=" + targetClearedOrRestored
                + " movedAway=" + movedAway);
    }

    private static double distanceFromPlayer(
            Store<EntityStore> store,
            Ref<EntityStore> playerRef,
            Ref<EntityStore> npcRef
    ) {
        if (store == null || playerRef == null || npcRef == null) {
            return -1.0d;
        }
        try {
            TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
            TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
            Vector3d playerPosition = playerTransform == null ? null : playerTransform.getPosition();
            Vector3d npcPosition = npcTransform == null ? null : npcTransform.getPosition();
            if (playerPosition == null || npcPosition == null) {
                return -1.0d;
            }
            return playerPosition.distance(npcPosition);
        } catch (RuntimeException exception) {
            return -1.0d;
        }
    }

    private static Store<EntityStore> getStore(World world) {
        if (world == null || !world.isAlive()) {
            return null;
        }
        EntityStore entityStore = world.getEntityStore();
        return entityStore == null ? null : entityStore.getStore();
    }

    private static boolean restoreAllTargets(
            Role role,
            List<TargetSlotSnapshot> previousTargets,
            Ref<EntityStore> playerRef
    ) {
        MarkedEntitySupport support = role == null ? null : role.getMarkedEntitySupport();
        if (support == null || previousTargets == null) {
            return false;
        }
        boolean changed = false;
        try {
            for (TargetSlotSnapshot previousTarget : previousTargets) {
                if (previousTarget == null || previousTarget.slotIndex() < 0) {
                    continue;
                }
                if (previousTarget.hadValidTarget()
                        && previousTarget.targetRef() != null
                        && previousTarget.targetRef().isValid()
                        && !previousTarget.targetRef().equals(playerRef)) {
                    support.setMarkedEntity(previousTarget.slotIndex(), previousTarget.targetRef());
                } else {
                    support.clearMarkedEntity(previousTarget.slotIndex());
                }
                changed = true;
            }
            role.resetAllInstructions();
            return changed;
        } catch (RuntimeException exception) {
            debug("restoreAllTargets-failed reason=" + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean areTargetsCleared(Role role) {
        MarkedEntitySupport support = role == null ? null : role.getMarkedEntitySupport();
        if (support == null) {
            return false;
        }
        try {
            int slotCount = support.getMarkedEntitySlotCount();
            for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
                Ref<EntityStore> current = support.getMarkedEntityRef(slotIndex);
                if (current != null && current.isValid()) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean areTargetsClearedOrRestored(
            Role role,
            List<TargetSlotSnapshot> previousTargets,
            Ref<EntityStore> playerRef
    ) {
        MarkedEntitySupport support = role == null ? null : role.getMarkedEntitySupport();
        if (support == null || previousTargets == null) {
            return false;
        }
        try {
            for (TargetSlotSnapshot previousTarget : previousTargets) {
                if (previousTarget == null || previousTarget.slotIndex() < 0) {
                    continue;
                }
                Ref<EntityStore> current = support.getMarkedEntityRef(previousTarget.slotIndex());
                if (previousTarget.hadValidTarget()
                        && previousTarget.targetRef() != null
                        && previousTarget.targetRef().isValid()
                        && !previousTarget.targetRef().equals(playerRef)) {
                    if (!previousTarget.targetRef().equals(current)) {
                        return false;
                    }
                } else if (current != null && current.isValid()) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isExecutingAttack(Role role) {
        if (role == null || role.getCombatSupport() == null) {
            return false;
        }
        try {
            return role.getCombatSupport().isExecutingAttack();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isPathFollowing(NPCEntity npc) {
        if (npc == null || npc.getPathManager() == null) {
            return false;
        }
        try {
            return npc.getPathManager().isFollowingPath();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean restoreLockedTarget(
            Role role,
            MarkedTargetSnapshot previousTarget,
            Ref<EntityStore> playerRef
    ) {
        MarkedEntitySupport support = role == null ? null : role.getMarkedEntitySupport();
        if (support == null || previousTarget == null) {
            return false;
        }
        try {
            int slotIndex = previousTarget.slotIndex();
            if (slotIndex < 0) {
                slotIndex = findTargetSlotIndex(support, LOCKED_TARGET_SLOT);
            }
            if (slotIndex < 0) {
                return false;
            }
            if (previousTarget.hadValidTarget()
                    && previousTarget.targetRef() != null
                    && previousTarget.targetRef().isValid()
                    && !previousTarget.targetRef().equals(playerRef)) {
                support.setMarkedEntity(slotIndex, previousTarget.targetRef());
            } else {
                support.clearMarkedEntity(slotIndex);
            }
            role.resetAllInstructions();
            return true;
        } catch (RuntimeException exception) {
            debug("restoreLockedTarget-failed reason=" + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean isLockedTargetClearedOrRestored(
            Role role,
            MarkedTargetSnapshot previousTarget,
            Ref<EntityStore> playerRef
    ) {
        MarkedEntitySupport support = role == null ? null : role.getMarkedEntitySupport();
        if (support == null || previousTarget == null) {
            return false;
        }
        try {
            int slotIndex = previousTarget.slotIndex();
            if (slotIndex < 0) {
                slotIndex = findTargetSlotIndex(support, LOCKED_TARGET_SLOT);
            }
            if (slotIndex < 0) {
                return false;
            }
            Ref<EntityStore> current = support.getMarkedEntityRef(slotIndex);
            if (previousTarget.hadValidTarget()
                    && previousTarget.targetRef() != null
                    && previousTarget.targetRef().isValid()
                    && !previousTarget.targetRef().equals(playerRef)) {
                return previousTarget.targetRef().equals(current);
            }
            return current == null || !playerRef.equals(current);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static int findTargetSlotIndex(MarkedEntitySupport support, String slotName) {
        if (support == null || slotName == null) {
            return -1;
        }
        int slotCount = support.getMarkedEntitySlotCount();
        for (int i = 0; i < slotCount; i++) {
            if (slotName.equals(support.getSlotName(i))) {
                return i;
            }
        }
        return -1;
    }

    private static void showCooldownHud(Player player, int slotIndex, String itemId, long remainingMillis) {
        if (remainingMillis <= 0L) {
            DruidAbilityCooldownHotbarHud.remove(player, itemId);
            return;
        }
        DruidAbilityCooldownHotbarHud.showOrUpdate(
                player,
                slotIndex,
                itemId,
                remainingMillis,
                TERRIFYING_ROAR_COOLDOWN_MILLIS
        );
    }

    private static void debug(String message) {
        if (DEBUG) {
            System.out.println("[GuardianTerrifyingRoar][debug] " + message);
        }
    }

    private static String describePlayer(Player player) {
        if (player == null) {
            return "null";
        }
        UUID uuid = null;
        try {
            uuid = player.getUuid();
        } catch (RuntimeException ignored) {
        }
        return DruidPlayerCompat.getPlayerName(player) + "/" + uuid;
    }

    private static String describePlayerName(Player player) {
        if (player == null) {
            return "unknown";
        }
        return safeText(DruidPlayerCompat.getPlayerName(player));
    }

    private static String describeNpc(NPCEntity npc) {
        if (npc == null) {
            return "null";
        }
        String typeId = null;
        String roleName = null;
        int typeIndex = -1;
        int roleIndex = -1;
        try {
            typeId = npc.getNPCTypeId();
        } catch (RuntimeException ignored) {
        }
        try {
            typeIndex = npc.getNPCTypeIndex();
        } catch (RuntimeException ignored) {
        }
        try {
            roleName = npc.getRoleName();
        } catch (RuntimeException ignored) {
        }
        try {
            roleIndex = npc.getRoleIndex();
        } catch (RuntimeException ignored) {
        }
        return "{typeId=" + safeText(typeId)
                + ",typeIndex=" + typeIndex
                + ",roleName=" + safeText(roleName)
                + ",roleIndex=" + roleIndex + "}";
    }

    private static String describeRoleState(Role role) {
        if (role == null || role.getStateSupport() == null) {
            return "unknown";
        }
        try {
            return safeText(role.getStateSupport().getStateName());
        } catch (RuntimeException exception) {
            return "error-" + exception.getClass().getSimpleName();
        }
    }

    private static String describeTargetSlots(Role role, Ref<EntityStore> playerRef) {
        if (role == null || role.getMarkedEntitySupport() == null) {
            return "unknown";
        }
        StringBuilder builder = new StringBuilder();
        try {
            int slotCount = role.getMarkedEntitySupport().getMarkedEntitySlotCount();
            for (int i = 0; i < slotCount; i++) {
                String slotName = role.getMarkedEntitySupport().getSlotName(i);
                Ref<EntityStore> targetRef = role.getMarkedEntitySupport().getMarkedEntityRef(i);
                if (builder.length() > 0) {
                    builder.append(",");
                }
                builder.append(safeText(slotName))
                        .append("=")
                        .append(targetRef == null ? "null" : targetRef.equals(playerRef) ? "player" : "other");
            }
        } catch (RuntimeException exception) {
            return "error-" + exception.getClass().getSimpleName();
        }
        return builder.length() == 0 ? "none" : builder.toString();
    }

    private static String describePathFollowing(NPCEntity npc) {
        if (npc == null || npc.getPathManager() == null) {
            return "unknown";
        }
        try {
            return Boolean.toString(npc.getPathManager().isFollowingPath());
        } catch (RuntimeException exception) {
            return "error-" + exception.getClass().getSimpleName();
        }
    }

    private static String formatPosition(Vector3d position) {
        if (position == null) {
            return "unknown";
        }
        return String.format(
                java.util.Locale.ROOT,
                "%.2f,%.2f,%.2f",
                position.x,
                position.y,
                position.z
        );
    }

    private static String safeText(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^A-Za-z0-9_.:-]", "-");
    }

    private record NearbyNpc(Ref<EntityStore> ref, NPCEntity npc, Vector3d position, double distance) {
    }

    private record FearAttemptResult(
            boolean fleeStateSucceeded,
            boolean panicStateSucceeded,
            boolean alertedFallbackSucceeded,
            boolean velocityFallbackSucceeded,
            boolean fearDisableSucceeded,
            boolean affected,
            FearApplication application
    ) {
    }

    private record FearApplication(
            Ref<EntityStore> npcRef,
            MarkedTargetSnapshot previousTarget,
            List<TargetSlotSnapshot> previousTargets,
            double initialDistance,
            boolean velocityFallbackUsed
    ) {
    }

    private record TargetSlotSnapshot(
            int slotIndex,
            String slotName,
            Ref<EntityStore> targetRef,
            boolean hadValidTarget
    ) {
    }

    private record MarkedTargetSnapshot(int slotIndex, Ref<EntityStore> targetRef, boolean hadValidTarget) {
        static MarkedTargetSnapshot missingSlot() {
            return new MarkedTargetSnapshot(-1, null, false);
        }

        String describe(Ref<EntityStore> playerRef) {
            if (slotIndex < 0) {
                return "missing-slot";
            }
            if (!hadValidTarget || targetRef == null) {
                return "none";
            }
            return targetRef.equals(playerRef) ? "player" : "other";
        }
    }
}
