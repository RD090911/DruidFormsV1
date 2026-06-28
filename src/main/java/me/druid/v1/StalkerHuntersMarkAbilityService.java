package me.druid.v1;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionSyncData;
import com.hypixel.hytale.protocol.SelectedHitEntity;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.druid.v1.forms.FormId;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class StalkerHuntersMarkAbilityService {
    static final String HUNTERS_MARK_ITEM_ID = "Stalker_Hunters_Mark";
    static final long HUNTERS_MARK_COOLDOWN_MILLIS = 24_000L;

    private static final int HUNTERS_MARK_FALLBACK_SLOT_INDEX = 4;
    private static final long MARK_DURATION_MILLIS = 25_000L;
    private static final long MARK_SELECTION_TIMEOUT_MILLIS = 1_500L;
    private static final Map<UUID, Long> COOLDOWN_END_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingMarkSelection> PENDING_SELECTION_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, ScheduledFuture<?>> PENDING_SELECTION_TASK_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, ActiveMark> ACTIVE_MARK_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, ScheduledFuture<?>> ACTIVE_MARK_EXPIRY_TASK_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, ScheduledFuture<?>> ACTIVE_MARK_VALIDATION_TASK_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingBiteBonus> PENDING_BITE_BONUS_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, ScheduledFuture<?>> PENDING_BITE_BONUS_TASK_BY_PLAYER = new ConcurrentHashMap<>();
    private static final long MARK_VALIDATION_INTERVAL_MILLIS = 500L;
    private static final long BITE_BONUS_TIMEOUT_MILLIS = 1_500L;

    private StalkerHuntersMarkAbilityService() {
    }

    static boolean tryStart(Player player, int activeHotbarSlot, int chainId) {
        if (player == null || ShapeshiftHandler.getActiveFormId(player) != FormId.FORM_STALKER) {
            System.out.println("[StalkerHuntersMark] blocked reason=invalid-form");
            return false;
        }

        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return false;
        }

        int hudSlotIndex = activeHotbarSlot >= 0 ? activeHotbarSlot : HUNTERS_MARK_FALLBACK_SLOT_INDEX;
        long nowMillis = System.currentTimeMillis();
        Long cooldownEndMillis = COOLDOWN_END_BY_PLAYER.get(playerUuid);
        if (cooldownEndMillis != null && cooldownEndMillis > nowMillis) {
            long remainingMillis = cooldownEndMillis - nowMillis;
            System.out.println("[StalkerHuntersMark] blocked remainingMs="
                    + remainingMillis + " slot=" + hudSlotIndex);
            showCooldownHud(player, hudSlotIndex, remainingMillis);
            return false;
        }

        armPendingSelection(playerUuid, chainId, hudSlotIndex);
        return true;
    }

    static void handleInteractionUpdate(UUID playerUuid, SyncInteractionChain chain) {
        if (playerUuid == null || chain == null) {
            return;
        }
        handlePendingSelection(playerUuid, chain);
        handleMarkedBiteBonus(playerUuid, chain);
    }

    static void armBiteBonusChain(UUID playerUuid, int chainId, String biteItemId) {
        if (playerUuid == null || !isBiteItemId(biteItemId)) {
            return;
        }

        ActiveMark activeMark = ACTIVE_MARK_BY_PLAYER.get(playerUuid);
        if (activeMark == null) {
            return;
        }

        long nowMillis = System.currentTimeMillis();
        if (activeMark.expiresAtMillis < nowMillis) {
            clearActiveMark(playerUuid, "expired", false);
            return;
        }

        float bonusDamage = resolveMarkedBiteBonusDamage(biteItemId);
        if (bonusDamage <= 0.0f) {
            return;
        }

        long expiresAtMillis = nowMillis + BITE_BONUS_TIMEOUT_MILLIS;
        PENDING_BITE_BONUS_BY_PLAYER.put(
                playerUuid,
                new PendingBiteBonus(chainId, biteItemId, bonusDamage, expiresAtMillis)
        );

        ScheduledFuture<?> previousTask = PENDING_BITE_BONUS_TASK_BY_PLAYER.remove(playerUuid);
        if (previousTask != null) {
            previousTask.cancel(false);
        }

        ScheduledFuture<?> clearTask = HytaleServer.SCHEDULED_EXECUTOR.schedule(
                () -> expirePendingBiteBonus(playerUuid, chainId),
                BITE_BONUS_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS
        );
        PENDING_BITE_BONUS_TASK_BY_PLAYER.put(playerUuid, clearTask);
    }

    static boolean isHuntersMarkItemId(String itemId) {
        return HUNTERS_MARK_ITEM_ID.equals(itemId);
    }

    static void cleanupPlayer(Player player, String reason) {
        UUID playerUuid = player == null ? null : player.getUuid();
        if (playerUuid == null) {
            return;
        }
        clearPendingSelection(playerUuid, reason);
        clearPendingBiteBonus(playerUuid, reason);
        clearActiveMark(playerUuid, reason, false);
    }

    static void handleDisconnect(PlayerRef playerRef) {
        UUID playerUuid = playerRef == null ? null : playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }
        clearPendingSelection(playerUuid, "disconnect");
        clearPendingBiteBonus(playerUuid, "disconnect");
        clearActiveMark(playerUuid, "disconnect", false);
    }

    static void shutdown() {
        for (ScheduledFuture<?> task : PENDING_SELECTION_TASK_BY_PLAYER.values()) {
            if (task != null) {
                task.cancel(false);
            }
        }
        PENDING_SELECTION_TASK_BY_PLAYER.clear();
        PENDING_SELECTION_BY_PLAYER.clear();

        for (ScheduledFuture<?> task : ACTIVE_MARK_EXPIRY_TASK_BY_PLAYER.values()) {
            if (task != null) {
                task.cancel(false);
            }
        }
        ACTIVE_MARK_EXPIRY_TASK_BY_PLAYER.clear();
        for (ScheduledFuture<?> task : ACTIVE_MARK_VALIDATION_TASK_BY_PLAYER.values()) {
            if (task != null) {
                task.cancel(false);
            }
        }
        ACTIVE_MARK_VALIDATION_TASK_BY_PLAYER.clear();
        ACTIVE_MARK_BY_PLAYER.clear();
        for (ScheduledFuture<?> task : PENDING_BITE_BONUS_TASK_BY_PLAYER.values()) {
            if (task != null) {
                task.cancel(false);
            }
        }
        PENDING_BITE_BONUS_TASK_BY_PLAYER.clear();
        PENDING_BITE_BONUS_BY_PLAYER.clear();
    }

    private static void handlePendingSelection(UUID playerUuid, SyncInteractionChain chain) {
        PendingMarkSelection pending = PENDING_SELECTION_BY_PLAYER.get(playerUuid);
        if (pending == null) {
            return;
        }

        long nowMillis = System.currentTimeMillis();
        if (pending.expiresAtMillis < nowMillis) {
            clearPendingSelection(playerUuid, "timeout");
            return;
        }

        if (pending.chainId != chain.chainId) {
            return;
        }

        int[] targetNetworkIds = extractTargetNetworkIds(chain.interactionData);
        if (targetNetworkIds.length > 0) {
            Player player = DruidPermissions.getOnlinePlayer(playerUuid);
            if (player == null) {
                clearPendingSelection(playerUuid, "player-unavailable");
                return;
            }

            int hudSlotIndex = pending.hudSlotIndex;
            clearPendingSelection(playerUuid, "consumed");
            applyMarkFromPacket(player, targetNetworkIds[0], hudSlotIndex);
            return;
        }

        if (isTerminalState(chain.state)) {
            clearPendingSelection(playerUuid, "finished-without-target");
            Player player = DruidPermissions.getOnlinePlayer(playerUuid);
            if (player != null) {
                sendPlayerMessage(player, "Hunter's Mark found no target.");
            }
        }
    }

    private static void handleMarkedBiteBonus(UUID playerUuid, SyncInteractionChain chain) {
        PendingBiteBonus pendingBonus = PENDING_BITE_BONUS_BY_PLAYER.get(playerUuid);
        if (pendingBonus == null) {
            return;
        }

        long nowMillis = System.currentTimeMillis();
        if (pendingBonus.expiresAtMillis < nowMillis) {
            clearPendingBiteBonus(playerUuid, "timeout");
            return;
        }

        if (pendingBonus.chainId != chain.chainId) {
            return;
        }

        ActiveMark activeMark = ACTIVE_MARK_BY_PLAYER.get(playerUuid);
        if (activeMark == null) {
            clearPendingBiteBonus(playerUuid, "no-active-mark");
            return;
        }

        if (activeMark.expiresAtMillis < nowMillis) {
            clearPendingBiteBonus(playerUuid, "mark-expired");
            clearActiveMark(playerUuid, "expired", false);
            return;
        }

        int[] targetNetworkIds = extractTargetNetworkIds(chain.interactionData);
        if (targetNetworkIds.length > 0 && isMarkedTarget(activeMark, targetNetworkIds)) {
            Player player = DruidPermissions.getOnlinePlayer(playerUuid);
            if (player == null) {
                clearPendingBiteBonus(playerUuid, "player-unavailable");
                clearActiveMark(playerUuid, "player-unavailable", false);
                return;
            }
            clearPendingBiteBonus(playerUuid, "consumed");
            applyMarkedBonusDamage(
                    player,
                    activeMark.targetNetworkId,
                    pendingBonus.bonusDamage,
                    playerUuid,
                    pendingBonus.biteItemId
            );
            return;
        }

        if (isTerminalState(chain.state)) {
            clearPendingBiteBonus(playerUuid, "finished-without-marked-hit");
        }
    }

    private static void applyMarkFromPacket(Player player, int targetNetworkId, int hudSlotIndex) {
        if (player == null || targetNetworkId <= 0) {
            return;
        }

        World world = player.getWorld();
        if (world == null || !world.isAlive()) {
            return;
        }

        try {
            world.execute(() -> applyMarkOnWorldThread(player, targetNetworkId, hudSlotIndex));
        } catch (RuntimeException exception) {
            System.out.println("[StalkerHuntersMark] apply skipped reason=world-rejected-task");
        }
    }

    private static void applyMarkOnWorldThread(Player player, int targetNetworkId, int hudSlotIndex) {
        if (player == null || ShapeshiftHandler.getActiveFormId(player) != FormId.FORM_STALKER) {
            return;
        }

        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return;
        }

        World world = player.getWorld();
        if (world == null || !world.isAlive()) {
            return;
        }

        Ref<EntityStore> sourceRef = player.getReference();
        if (sourceRef == null || !sourceRef.isValid()) {
            return;
        }

        EntityStore entityStore = world.getEntityStore();
        if (entityStore == null) {
            return;
        }

        Ref<EntityStore> targetRef = entityStore.getRefFromNetworkId(targetNetworkId);
        if (targetRef == null || !targetRef.isValid() || targetRef.equals(sourceRef)) {
            sendPlayerMessage(player, "Hunter's Mark found no valid target.");
            return;
        }

        if (!startCooldownOnSuccess(player, playerUuid, hudSlotIndex)) {
            return;
        }

        clearActiveMark(playerUuid, "replaced", false);

        long expiresAtMillis = System.currentTimeMillis() + MARK_DURATION_MILLIS;
        ACTIVE_MARK_BY_PLAYER.put(playerUuid, new ActiveMark(targetNetworkId, targetRef, world, expiresAtMillis));

        ScheduledFuture<?> expiryTask = HytaleServer.SCHEDULED_EXECUTOR.schedule(
                () -> expireMark(playerUuid, expiresAtMillis),
                MARK_DURATION_MILLIS,
                TimeUnit.MILLISECONDS
        );
        ScheduledFuture<?> previousTask = ACTIVE_MARK_EXPIRY_TASK_BY_PLAYER.put(playerUuid, expiryTask);
        if (previousTask != null) {
            previousTask.cancel(false);
        }

        ScheduledFuture<?> validationTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
                () -> validateActiveMark(playerUuid, expiresAtMillis),
                MARK_VALIDATION_INTERVAL_MILLIS,
                MARK_VALIDATION_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS
        );
        ScheduledFuture<?> previousValidationTask = ACTIVE_MARK_VALIDATION_TASK_BY_PLAYER.put(playerUuid, validationTask);
        if (previousValidationTask != null) {
            previousValidationTask.cancel(false);
        }

        sendPlayerMessage(player, "Hunter's Mark locked onto your prey.");
    }

    private static void applyMarkedBonusDamage(
            Player player,
            int targetNetworkId,
            float damageAmount,
            UUID playerUuid,
            String biteItemId
    ) {
        World world = player == null ? null : player.getWorld();
        if (player == null || world == null || !world.isAlive() || damageAmount <= 0.0f || targetNetworkId <= 0) {
            return;
        }

        try {
            world.execute(() -> applyMarkedBonusDamageOnWorldThread(
                    player,
                    targetNetworkId,
                    damageAmount,
                    playerUuid,
                    biteItemId
            ));
        } catch (RuntimeException exception) {
            System.out.println("[StalkerHuntersMark] bonus skipped reason=world-rejected-task");
        }
    }

    private static void applyMarkedBonusDamageOnWorldThread(
            Player player,
            int targetNetworkId,
            float damageAmount,
            UUID playerUuid,
            String biteItemId
    ) {
        if (player == null
                || damageAmount <= 0.0f
                || targetNetworkId <= 0
                || ShapeshiftHandler.getActiveFormId(player) != FormId.FORM_STALKER) {
            return;
        }

        ActiveMark activeMark = ACTIVE_MARK_BY_PLAYER.get(playerUuid);
        if (activeMark == null) {
            return;
        }

        if (activeMark.expiresAtMillis < System.currentTimeMillis()) {
            clearActiveMark(playerUuid, "expired", false);
            return;
        }

        World world = player.getWorld();
        if (world == null || !world.isAlive() || activeMark.world != world) {
            clearActiveMark(playerUuid, "world-changed", false);
            return;
        }

        Ref<EntityStore> sourceRef = player.getReference();
        if (sourceRef == null || !sourceRef.isValid()) {
            return;
        }

        EntityStore entityStore = world.getEntityStore();
        if (entityStore == null) {
            return;
        }

        Ref<EntityStore> targetRef = entityStore.getRefFromNetworkId(targetNetworkId);
        if (targetRef == null || !targetRef.isValid() || !targetRef.equals(activeMark.targetRef)) {
            clearActiveMark(playerUuid, "target-invalid", false);
            return;
        }

        Store<EntityStore> store = entityStore.getStore();
        if (store == null) {
            return;
        }

        Damage.Source source = new Damage.EntitySource(sourceRef);
        store.invoke(targetRef, new Damage(source, DamageCause.PHYSICAL, damageAmount));
        System.out.println("[StalkerHuntersMark] bonus targetNetworkId="
                + targetNetworkId + " amount=" + damageAmount + " item=" + biteItemId);
    }

    private static boolean isMarkedTarget(ActiveMark activeMark, int[] targetNetworkIds) {
        if (activeMark == null || targetNetworkIds == null || targetNetworkIds.length == 0) {
            return false;
        }
        for (int targetNetworkId : targetNetworkIds) {
            if (targetNetworkId == activeMark.targetNetworkId) {
                return true;
            }
        }
        return false;
    }

    private static void armPendingSelection(UUID playerUuid, int chainId, int hudSlotIndex) {
        if (playerUuid == null) {
            return;
        }

        long expiresAtMillis = System.currentTimeMillis() + MARK_SELECTION_TIMEOUT_MILLIS;
        PENDING_SELECTION_BY_PLAYER.put(playerUuid, new PendingMarkSelection(chainId, expiresAtMillis, hudSlotIndex));

        ScheduledFuture<?> previousTask = PENDING_SELECTION_TASK_BY_PLAYER.remove(playerUuid);
        if (previousTask != null) {
            previousTask.cancel(false);
        }

        ScheduledFuture<?> clearTask = HytaleServer.SCHEDULED_EXECUTOR.schedule(
                () -> expirePendingSelection(playerUuid, chainId),
                MARK_SELECTION_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS
        );
        PENDING_SELECTION_TASK_BY_PLAYER.put(playerUuid, clearTask);
    }

    private static void clearPendingSelection(UUID playerUuid, String reason) {
        if (playerUuid == null) {
            return;
        }
        PENDING_SELECTION_BY_PLAYER.remove(playerUuid);
        ScheduledFuture<?> clearTask = PENDING_SELECTION_TASK_BY_PLAYER.remove(playerUuid);
        if (clearTask != null) {
            clearTask.cancel(false);
        }
    }

    private static void expirePendingSelection(UUID playerUuid, int chainId) {
        if (playerUuid == null) {
            return;
        }
        PendingMarkSelection pending = PENDING_SELECTION_BY_PLAYER.get(playerUuid);
        if (pending == null || pending.chainId != chainId) {
            return;
        }
        clearPendingSelection(playerUuid, "timeout");
    }

    private static void expireMark(UUID playerUuid, long expectedExpiryMillis) {
        if (playerUuid == null) {
            return;
        }
        ActiveMark activeMark = ACTIVE_MARK_BY_PLAYER.get(playerUuid);
        if (activeMark == null || activeMark.expiresAtMillis != expectedExpiryMillis) {
            return;
        }
        clearActiveMark(playerUuid, "expired", true);
    }

    private static void validateActiveMark(UUID playerUuid, long expectedExpiryMillis) {
        if (playerUuid == null) {
            return;
        }
        ActiveMark activeMark = ACTIVE_MARK_BY_PLAYER.get(playerUuid);
        if (activeMark == null || activeMark.expiresAtMillis != expectedExpiryMillis) {
            ScheduledFuture<?> validationTask = ACTIVE_MARK_VALIDATION_TASK_BY_PLAYER.remove(playerUuid);
            if (validationTask != null) {
                validationTask.cancel(false);
            }
            return;
        }
        if (!activeMark.targetRef.isValid() || activeMark.world == null || !activeMark.world.isAlive()) {
            clearActiveMark(playerUuid, "target-invalid", false);
        }
    }

    private static void clearActiveMark(UUID playerUuid, String reason, boolean notifyPlayer) {
        if (playerUuid == null) {
            return;
        }

        ACTIVE_MARK_BY_PLAYER.remove(playerUuid);
        ScheduledFuture<?> expiryTask = ACTIVE_MARK_EXPIRY_TASK_BY_PLAYER.remove(playerUuid);
        if (expiryTask != null) {
            expiryTask.cancel(false);
        }
        ScheduledFuture<?> validationTask = ACTIVE_MARK_VALIDATION_TASK_BY_PLAYER.remove(playerUuid);
        if (validationTask != null) {
            validationTask.cancel(false);
        }
        if (notifyPlayer) {
            Player player = DruidPermissions.getOnlinePlayer(playerUuid);
            if (player != null) {
                sendPlayerMessage(player, "Hunter's Mark faded.");
            }
        }
    }

    private static void clearPendingBiteBonus(UUID playerUuid, String reason) {
        if (playerUuid == null) {
            return;
        }
        PENDING_BITE_BONUS_BY_PLAYER.remove(playerUuid);
        ScheduledFuture<?> clearTask = PENDING_BITE_BONUS_TASK_BY_PLAYER.remove(playerUuid);
        if (clearTask != null) {
            clearTask.cancel(false);
        }
    }

    private static void expirePendingBiteBonus(UUID playerUuid, int chainId) {
        if (playerUuid == null) {
            return;
        }
        PendingBiteBonus pending = PENDING_BITE_BONUS_BY_PLAYER.get(playerUuid);
        if (pending == null || pending.chainId != chainId) {
            return;
        }
        clearPendingBiteBonus(playerUuid, "timeout");
    }

    private static boolean startCooldownOnSuccess(Player player, UUID playerUuid, int hudSlotIndex) {
        if (player == null || playerUuid == null) {
            return false;
        }

        long nowMillis = System.currentTimeMillis();
        boolean[] started = {false};
        long cooldownEndMillis = COOLDOWN_END_BY_PLAYER.compute(playerUuid, (ignored, currentEndMillis) -> {
            if (currentEndMillis != null && currentEndMillis > nowMillis) {
                return currentEndMillis;
            }
            started[0] = true;
            return nowMillis + HUNTERS_MARK_COOLDOWN_MILLIS;
        });

        long remainingMillis = cooldownEndMillis - nowMillis;
        if (!started[0]) {
            showCooldownHud(player, hudSlotIndex, remainingMillis);
            return false;
        }

        showCooldownHud(player, hudSlotIndex, remainingMillis);
        return true;
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

    private static boolean isTerminalState(InteractionState state) {
        return state == InteractionState.Finished
                || state == InteractionState.Failed
                || state == InteractionState.Skip
                || state == InteractionState.ItemChanged;
    }

    private static boolean isBiteItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        String lowerId = itemId.toLowerCase(Locale.ROOT);
        return lowerId.contains("shark_tooth");
    }

    private static float resolveMarkedBiteBonusDamage(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return 0.0f;
        }
        String lowerId = itemId.toLowerCase(Locale.ROOT);
        if (lowerId.contains("elder_shark_tooth")) return 7.25f;
        if (lowerId.contains("primal_shark_tooth")) return 6.5f;
        if (lowerId.contains("verdant_shark_tooth")) return 5.75f;
        return 5.0f;
    }

    private static void showCooldownHud(Player player, int slotIndex, long remainingMillis) {
        if (remainingMillis <= 0L) {
            DruidAbilityCooldownHotbarHud.remove(player, HUNTERS_MARK_ITEM_ID);
            return;
        }
        DruidAbilityCooldownHotbarHud.showOrUpdate(
                player,
                slotIndex,
                HUNTERS_MARK_ITEM_ID,
                remainingMillis,
                HUNTERS_MARK_COOLDOWN_MILLIS
        );
    }

    private static void sendPlayerMessage(Player player, String text) {
        if (player == null || text == null || text.isBlank()) {
            return;
        }
        try {
            Method getPlayerRef = player.getClass().getMethod("getPlayerRef");
            Object playerRef = getPlayerRef.invoke(player);
            Class<?> formattedMsgClass = Class.forName("com.hypixel.hytale.protocol.FormattedMessage");
            Object component = formattedMsgClass.getConstructor().newInstance();
            formattedMsgClass.getField("rawText").set(component, text);

            Class<?> msgClass = Class.forName("com.hypixel.hytale.server.core.Message");
            Object message = msgClass.getConstructor(formattedMsgClass).newInstance(component);

            Method sendMessage = playerRef.getClass().getMethod("sendMessage", msgClass);
            sendMessage.invoke(playerRef, message);
        } catch (Exception ignored) {
        }
    }

    private record PendingMarkSelection(int chainId, long expiresAtMillis, int hudSlotIndex) {
    }

    private record PendingBiteBonus(int chainId, String biteItemId, float bonusDamage, long expiresAtMillis) {
    }

    private record ActiveMark(int targetNetworkId, Ref<EntityStore> targetRef, World world, long expiresAtMillis) {
    }
}
