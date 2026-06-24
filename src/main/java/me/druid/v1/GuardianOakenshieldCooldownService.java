package me.druid.v1;

import com.hypixel.hytale.server.core.entity.entities.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class GuardianOakenshieldCooldownService {
    static final String OAKENSHIELD_ITEM_ID = "Guardian_Oakenshield";
    static final String PRIMAL_OAKENSHIELD_ITEM_ID = "Primal_Guardian_Oakenshield";
    static final String ELDER_OAKENSHIELD_ITEM_ID = "Elder_Guardian_Oakenshield";
    static final long OAKENSHIELD_COOLDOWN_MILLIS = 25_000L;
    static final long OAKENSHIELD_ACTIVE_MILLIS = 5_000L;

    private static final int OAKENSHIELD_FALLBACK_SLOT_INDEX = 2;
    private static final Map<UUID, Long> COOLDOWN_END_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> ACTIVE_END_BY_PLAYER = new ConcurrentHashMap<>();

    private GuardianOakenshieldCooldownService() {
    }

    static void onTriggered(Player player, int activeHotbarSlot, String itemId) {
        if (player == null || !isOakenshieldItemId(itemId)) {
            return;
        }

        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return;
        }

        long nowMillis = System.currentTimeMillis();
        Long currentEndMillis = COOLDOWN_END_BY_PLAYER.get(playerUuid);
        boolean started = currentEndMillis == null || currentEndMillis <= nowMillis;
        long cooldownEndMillis = started ? nowMillis + OAKENSHIELD_COOLDOWN_MILLIS : currentEndMillis;
        COOLDOWN_END_BY_PLAYER.put(playerUuid, cooldownEndMillis);

        long remainingMillis = cooldownEndMillis - nowMillis;
        int hudSlotIndex = activeHotbarSlot >= 0 ? activeHotbarSlot : OAKENSHIELD_FALLBACK_SLOT_INDEX;
        if (remainingMillis <= 0L) {
            DruidAbilityCooldownHotbarHud.remove(player, itemId);
            return;
        }

        DruidAbilityCooldownHotbarHud.showOrUpdate(
                player,
                hudSlotIndex,
                itemId,
                remainingMillis,
                OAKENSHIELD_COOLDOWN_MILLIS
        );
        if (started) {
            ACTIVE_END_BY_PLAYER.put(playerUuid, nowMillis + OAKENSHIELD_ACTIVE_MILLIS);
            DruidBuffDebuffHud.showOakenshield(player);
        }
    }

    static boolean isActive(Player player) {
        if (player == null) {
            return false;
        }
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return false;
        }
        Long activeEndMillis = ACTIVE_END_BY_PLAYER.get(playerUuid);
        long nowMillis = System.currentTimeMillis();
        if (activeEndMillis == null) {
            return false;
        }
        if (activeEndMillis <= nowMillis) {
            ACTIVE_END_BY_PLAYER.remove(playerUuid, activeEndMillis);
            return false;
        }
        return true;
    }

    static void clearActive(Player player) {
        if (player == null) {
            return;
        }
        UUID playerUuid = player.getUuid();
        if (playerUuid != null) {
            ACTIVE_END_BY_PLAYER.remove(playerUuid);
        }
        DruidBuffDebuffHud.remove(player);
    }

    static void clearActive(UUID playerUuid) {
        if (playerUuid != null) {
            ACTIVE_END_BY_PLAYER.remove(playerUuid);
            DruidBuffDebuffHud.remove(playerUuid);
        }
    }

    static boolean isOakenshieldItemId(String itemId) {
        return PRIMAL_OAKENSHIELD_ITEM_ID.equals(itemId)
                || ELDER_OAKENSHIELD_ITEM_ID.equals(itemId);
    }
}
