package me.druid.v1;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import me.druid.v1.forms.FormId;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class GuardianChallengingRoarAbilityService {
    static final String CHALLENGING_ROAR_ITEM_ID = "Elder_Guardian_Challenging_Roar";
    static final long CHALLENGING_ROAR_COOLDOWN_MILLIS = 22_000L;
    static final double CHALLENGING_ROAR_RADIUS_BLOCKS = 14.0d;
    static final double CHALLENGING_ROAR_DURATION_SECONDS = 5.0d;

    private static final String LOCKED_TARGET_SLOT = "LockedTarget";
    private static final String COMBAT_STATE = "Combat";
    private static final String COMBAT_ENTRY_SUBSTATE = "Message";
    private static final String COMBAT_DEFAULT_SUBSTATE = "Default";
    private static final String ALERTED_STATE = "Alerted";
    private static final long VERIFY_DELAY_MILLIS = 350L;
    private static final int CHALLENGING_ROAR_FALLBACK_SLOT_INDEX = 3;
    private static final boolean DEBUG = Boolean.getBoolean("druid.challengingRoar.debug");
    private static final Map<UUID, Long> COOLDOWN_END_BY_PLAYER = new ConcurrentHashMap<>();

    private GuardianChallengingRoarAbilityService() {
    }

    static boolean tryStart(Player player, int activeHotbarSlot) {
        debug("detected player=" + describePlayer(player)
                + " form=" + ShapeshiftHandler.getActiveFormId(player)
                + " item=" + CHALLENGING_ROAR_ITEM_ID
                + " slot=" + activeHotbarSlot);

        if (!isEligibleElderGuardian(player)) {
            System.out.println("[GuardianChallengingRoar] blocked reason=invalid-form-or-tier");
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
            return nowMillis + CHALLENGING_ROAR_COOLDOWN_MILLIS;
        });

        long remainingMillis = cooldownEndMillis - nowMillis;
        int hudSlotIndex = activeHotbarSlot >= 0 ? activeHotbarSlot : CHALLENGING_ROAR_FALLBACK_SLOT_INDEX;
        if (!started[0]) {
            debug("cooldown-denied player=" + describePlayer(player)
                    + " remainingMs=" + remainingMillis
                    + " slot=" + hudSlotIndex);
            showCooldownHud(player, hudSlotIndex, remainingMillis);
            return false;
        }

        World world = player.getWorld();
        if (world == null || !world.isAlive()) {
            COOLDOWN_END_BY_PLAYER.remove(playerUuid, cooldownEndMillis);
            System.out.println("[GuardianChallengingRoar] blocked reason=world-unavailable");
            return false;
        }

        try {
            world.execute(() -> applyTauntOnWorldThread(player, world));
        } catch (RuntimeException exception) {
            COOLDOWN_END_BY_PLAYER.remove(playerUuid, cooldownEndMillis);
            System.out.println("[GuardianChallengingRoar] blocked reason=world-rejected-task");
            return false;
        }

        debug("activated player=" + describePlayer(player)
                + " cooldownMs=" + CHALLENGING_ROAR_COOLDOWN_MILLIS
                + " radius=" + CHALLENGING_ROAR_RADIUS_BLOCKS
                + " durationSeconds=" + CHALLENGING_ROAR_DURATION_SECONDS);
        showCooldownHud(player, hudSlotIndex, remainingMillis);
        return true;
    }

    static boolean isChallengingRoarItemId(String itemId) {
        return CHALLENGING_ROAR_ITEM_ID.equals(itemId);
    }

    private static boolean isEligibleElderGuardian(Player player) {
        if (player == null || ShapeshiftHandler.getActiveFormId(player) != FormId.FORM_GUARDIAN) {
            return false;
        }
        try {
            return new ShapeshiftHandler().getTier(player, "guardian") >= 4;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void applyTauntOnWorldThread(Player player, World expectedWorld) {
        if (player == null
                || expectedWorld == null
                || !expectedWorld.isAlive()
                || player.getWorld() != expectedWorld
                || !isEligibleElderGuardian(player)) {
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
                + " radius=" + CHALLENGING_ROAR_RADIUS_BLOCKS
                + " sphereEntities=" + (targetUtilRefs == null ? -1 : targetUtilRefs.size())
                + " nearbyNpcs=" + nearbyNpcs.size());

        int withRole = 0;
        int friendly = 0;
        int nonFriendly = 0;
        int eligible = 0;
        int targetWriteSuccess = 0;
        int targetWriteFailure = 0;
        int stateSuccess = 0;
        int stateFailure = 0;
        int taunted = 0;
        int failures = 0;
        List<Ref<EntityStore>> attemptedNpcRefs = new ArrayList<>();
        for (NearbyNpc nearbyNpc : nearbyNpcs) {
            Ref<EntityStore> targetRef = nearbyNpc.ref();
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
                            + " ref=" + targetRef
                            + " reason=" + exception.getClass().getSimpleName());
                }
            }

            debug("candidate npc=" + describeNpc(npc)
                    + " ref=" + targetRef
                    + " distance=" + String.format(java.util.Locale.ROOT, "%.2f", nearbyNpc.distance())
                    + " rolePresent=" + hasRole
                    + " friendlyKnown=" + friendlyKnown
                    + " friendly=" + isFriendly);

            if (role == null) {
                continue;
            }
            eligible++;
            attemptedNpcRefs.add(targetRef);

            TauntAttemptResult result = forceNpcTarget(targetRef, npc, role, playerRef, store);
            if (result.targetWriteSucceeded()) {
                targetWriteSuccess++;
            } else {
                targetWriteFailure++;
            }
            if (result.stateSucceeded()) {
                stateSuccess++;
            } else {
                stateFailure++;
            }
            if (result.taunted()) {
                taunted++;
            } else {
                failures++;
            }
        }

        debug("summary player=" + describePlayer(player)
                + " sphereEntities=" + (targetUtilRefs == null ? -1 : targetUtilRefs.size())
                + " nearbyNpcs=" + nearbyNpcs.size()
                + " withRole=" + withRole
                + " eligible=" + eligible
                + " nonFriendly=" + nonFriendly
                + " friendly=" + friendly
                + " targetWriteSuccess=" + targetWriteSuccess
                + " targetWriteFailure=" + targetWriteFailure
                + " stateSuccess=" + stateSuccess
                + " stateFailure=" + stateFailure
                + " taunted=" + taunted
                + " failures=" + failures);
        System.out.println("[GuardianChallengingRoar] activated player=" + describePlayerName(player)
                + " radius=" + String.format(java.util.Locale.ROOT, "%.1f", CHALLENGING_ROAR_RADIUS_BLOCKS)
                + " nearbyNpcs=" + nearbyNpcs.size()
                + " eligible=" + eligible
                + " targetWriteSuccess=" + targetWriteSuccess
                + " stateSuccess=" + stateSuccess
                + " taunted=" + taunted);
        if (failures > 0) {
            System.out.println("[GuardianChallengingRoar] taunt partial-failure count=" + failures);
        }
        scheduleDelayedVerification(describePlayerName(player), playerRef, expectedWorld, attemptedNpcRefs);
    }

    private static List<Ref<EntityStore>> safeGetAllEntitiesInSphere(
            Vector3d playerPosition,
            Store<EntityStore> store
    ) {
        try {
            return TargetUtil.getAllEntitiesInSphere(
                    playerPosition,
                    CHALLENGING_ROAR_RADIUS_BLOCKS,
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
        double radiusSquared = CHALLENGING_ROAR_RADIUS_BLOCKS * CHALLENGING_ROAR_RADIUS_BLOCKS;
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
                    nearbyNpcs.add(new NearbyNpc(npcRef, npc, Math.sqrt(distanceSquared)));
                }
            }
        });
        return nearbyNpcs;
    }

    private static TauntAttemptResult forceNpcTarget(
            Ref<EntityStore> npcRef,
            NPCEntity npc,
            Role role,
            Ref<EntityStore> playerRef,
            Store<EntityStore> store
    ) {
        boolean markedTarget = false;
        boolean attitudeOverridden = false;
        boolean pathRequested = false;
        boolean instructionsReset = false;
        boolean combatStateSet = false;
        boolean alertedStateSet = false;
        boolean sensorNotified = false;
        String stateBefore = describeRoleState(role);
        String targetBefore = describeTargetSlots(role, playerRef);
        String attitudeBefore = describeAttitude(role, npcRef, playerRef, store);

        try {
            role.setMarkedTarget(LOCKED_TARGET_SLOT, playerRef);
            role.getMarkedEntitySupport().setMarkedEntity(LOCKED_TARGET_SLOT, playerRef);
            role.getMarkedEntitySupport().flockSetTarget(LOCKED_TARGET_SLOT, playerRef, store);
            markedTarget = true;
        } catch (RuntimeException exception) {
            debug("setMarkedTarget-failed npc=" + describeNpc(npc)
                    + " reason=" + exception.getClass().getSimpleName());
        }

        WorldSupport worldSupport = role.getWorldSupport();
        if (worldSupport != null) {
            try {
                worldSupport.overrideAttitude(playerRef, Attitude.HOSTILE, CHALLENGING_ROAR_DURATION_SECONDS);
                worldSupport.tick(0.11f);
                attitudeOverridden = true;
            } catch (RuntimeException exception) {
                debug("overrideAttitude-failed npc=" + describeNpc(npc)
                        + " reason=" + exception.getClass().getSimpleName());
            }
            try {
                worldSupport.requestNewPath();
                pathRequested = true;
            } catch (RuntimeException exception) {
                debug("requestNewPath-failed npc=" + describeNpc(npc)
                        + " reason=" + exception.getClass().getSimpleName());
            }
        }

        try {
            role.resetAllInstructions();
            instructionsReset = true;
        } catch (RuntimeException exception) {
            debug("resetInstructions-failed npc=" + describeNpc(npc)
                    + " reason=" + exception.getClass().getSimpleName());
        }

        combatStateSet = trySetState(npcRef, npc, role, store, COMBAT_STATE, COMBAT_ENTRY_SUBSTATE)
                || trySetState(npcRef, npc, role, store, COMBAT_STATE, COMBAT_DEFAULT_SUBSTATE)
                || trySetState(npcRef, npc, role, store, COMBAT_STATE, null);
        if (!combatStateSet) {
            alertedStateSet = trySetState(npcRef, npc, role, store, ALERTED_STATE, null);
        }

        try {
            role.notifySensorMatch();
            sensorNotified = true;
        } catch (RuntimeException exception) {
            debug("notifySensorMatch-failed npc=" + describeNpc(npc)
                    + " reason=" + exception.getClass().getSimpleName());
        }

        String stateAfter = describeRoleState(role);
        String targetAfter = describeTargetSlots(role, playerRef);
        String attitudeAfter = describeAttitude(role, npcRef, playerRef, store);
        debug("apply npc=" + describeNpc(npc)
                + " ref=" + npcRef
                + " stateBefore=" + stateBefore
                + " stateAfter=" + stateAfter
                + " targetBefore=" + targetBefore
                + " targetAfter=" + targetAfter
                + " attitudeBefore=" + attitudeBefore
                + " attitudeAfter=" + attitudeAfter
                + " markedTarget=" + markedTarget
                + " attitude=" + attitudeOverridden
                + " path=" + pathRequested
                + " reset=" + instructionsReset
                + " combatState=" + combatStateSet
                + " alertedState=" + alertedStateSet
                + " sensorNotified=" + sensorNotified);
        return new TauntAttemptResult(
                markedTarget,
                attitudeOverridden,
                pathRequested,
                instructionsReset,
                combatStateSet,
                alertedStateSet,
                sensorNotified
        );
    }

    private static boolean trySetState(
            Ref<EntityStore> npcRef,
            NPCEntity npc,
            Role role,
            Store<EntityStore> store,
            String state,
            String subState
    ) {
        String before = describeRoleState(role);
        try {
            role.getStateSupport().setState(npcRef, state, subState, store);
            role.resetAllInstructions();
            String after = describeRoleState(role);
            boolean accepted = after.equals(state)
                    || after.equals(state + "." + subState)
                    || after.startsWith(state + ".");
            debug("setState npc=" + describeNpc(npc)
                    + " requested=" + state + "." + (subState == null ? "<default>" : subState)
                    + " before=" + before
                    + " after=" + after
                    + " accepted=" + accepted);
            return accepted;
        } catch (RuntimeException exception) {
            debug("setState-failed npc=" + describeNpc(npc)
                    + " requested=" + state + "." + (subState == null ? "<default>" : subState)
                    + " reason=" + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static void scheduleDelayedVerification(
            String playerName,
            Ref<EntityStore> playerRef,
            World world,
            List<Ref<EntityStore>> npcRefs
    ) {
        if (playerRef == null || world == null || npcRefs == null || npcRefs.isEmpty()) {
            return;
        }
        List<Ref<EntityStore>> refsToVerify = List.copyOf(npcRefs);
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            try {
                world.execute(() -> verifyTauntOnWorldThread(playerName, playerRef, world, refsToVerify));
            } catch (RuntimeException exception) {
                System.out.println("[GuardianChallengingRoar] verify failed reason=world-rejected-task player="
                        + safeText(playerName));
            }
        }, VERIFY_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static void verifyTauntOnWorldThread(
            String playerName,
            Ref<EntityStore> playerRef,
            World expectedWorld,
            List<Ref<EntityStore>> npcRefs
    ) {
        if (expectedWorld == null || !expectedWorld.isAlive() || playerRef == null || !playerRef.isValid()) {
            return;
        }
        EntityStore entityStore = expectedWorld.getEntityStore();
        Store<EntityStore> store = entityStore == null ? null : entityStore.getStore();
        if (store == null) {
            return;
        }

        int checked = 0;
        int stillTarget = 0;
        int combatOrAlert = 0;
        for (Ref<EntityStore> npcRef : npcRefs) {
            if (npcRef == null || !npcRef.isValid()) {
                continue;
            }
            NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
            Role role = npc == null ? null : npc.getRole();
            if (role == null) {
                continue;
            }
            checked++;
            boolean hasPlayerTarget = hasPlayerAsMarkedTarget(role, playerRef);
            String state = describeRoleState(role);
            boolean inCombatOrAlert = state.startsWith(COMBAT_STATE) || state.startsWith(ALERTED_STATE);
            if (hasPlayerTarget) {
                stillTarget++;
            }
            if (inCombatOrAlert) {
                combatOrAlert++;
            }
            debug("verify npc=" + describeNpc(npc)
                    + " ref=" + npcRef
                    + " state=" + state
                    + " targetSlots=" + describeTargetSlots(role, playerRef)
                    + " attitude=" + describeAttitude(role, npcRef, playerRef, store)
                    + " hasPlayerTarget=" + hasPlayerTarget
                    + " combatOrAlert=" + inCombatOrAlert
                    + " pathFollowing=" + describePathFollowing(npc, npcRef, store));
        }
        System.out.println("[GuardianChallengingRoar] verify player=" + safeText(playerName)
                + " checked=" + checked
                + " stillTarget=" + stillTarget
                + " combatOrAlert=" + combatOrAlert);
    }

    private static void showCooldownHud(Player player, int slotIndex, long remainingMillis) {
        if (remainingMillis <= 0L) {
            DruidAbilityCooldownHotbarHud.remove(player, CHALLENGING_ROAR_ITEM_ID);
            return;
        }
        DruidAbilityCooldownHotbarHud.showOrUpdate(
                player,
                slotIndex,
                CHALLENGING_ROAR_ITEM_ID,
                remainingMillis,
                CHALLENGING_ROAR_COOLDOWN_MILLIS
        );
    }

    private static void debug(String message) {
        if (DEBUG) {
            System.out.println("[GuardianChallengingRoar][debug] " + message);
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
        String playerName = DruidPlayerCompat.getPlayerName(player);
        return safeText(playerName);
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
            String state = role.getStateSupport().getStateName();
            return safeText(state);
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

    private static boolean hasPlayerAsMarkedTarget(Role role, Ref<EntityStore> playerRef) {
        if (role == null || role.getMarkedEntitySupport() == null || playerRef == null) {
            return false;
        }
        try {
            int slotCount = role.getMarkedEntitySupport().getMarkedEntitySlotCount();
            for (int i = 0; i < slotCount; i++) {
                Ref<EntityStore> targetRef = role.getMarkedEntitySupport().getMarkedEntityRef(i);
                if (playerRef.equals(targetRef)) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    private static String describeAttitude(
            Role role,
            Ref<EntityStore> npcRef,
            Ref<EntityStore> playerRef,
            Store<EntityStore> store
    ) {
        if (role == null || role.getWorldSupport() == null || npcRef == null || playerRef == null || store == null) {
            return "unknown";
        }
        try {
            Attitude attitude = role.getWorldSupport().getAttitude(npcRef, playerRef, store);
            Attitude override = role.getWorldSupport().getOverriddenAttitude(playerRef);
            return "current=" + (attitude == null ? "null" : attitude.name())
                    + ";override=" + (override == null ? "null" : override.name());
        } catch (RuntimeException exception) {
            return "error-" + exception.getClass().getSimpleName();
        }
    }

    private static String describePathFollowing(NPCEntity npc, Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npc == null || npcRef == null || store == null || npc.getPathManager() == null) {
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

    private record NearbyNpc(Ref<EntityStore> ref, NPCEntity npc, double distance) {
    }

    private record TauntAttemptResult(
            boolean targetWriteSucceeded,
            boolean attitudeSucceeded,
            boolean pathSucceeded,
            boolean resetSucceeded,
            boolean combatStateSucceeded,
            boolean alertedStateSucceeded,
            boolean sensorNotifySucceeded
    ) {
        boolean stateSucceeded() {
            return combatStateSucceeded || alertedStateSucceeded;
        }

        boolean taunted() {
            return targetWriteSucceeded && (stateSucceeded() || attitudeSucceeded || pathSucceeded);
        }
    }
}
