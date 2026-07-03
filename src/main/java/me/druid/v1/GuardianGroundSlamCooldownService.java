package me.druid.v1;

import com.hypixel.hytale.server.core.entity.entities.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class GuardianGroundSlamCooldownService {
    static final String GROUND_SLAM_ITEM_ID = "Guardian_Ground_Slam";
    static final String VERDANT_GROUND_SLAM_ITEM_ID = "Verdant_Guardian_Ground_Slam";
    static final String PRIMAL_GROUND_SLAM_ITEM_ID = "Primal_Guardian_Ground_Slam";
    static final String ELDER_GROUND_SLAM_ITEM_ID = "Elder_Guardian_Ground_Slam";
    static final long GROUND_SLAM_COOLDOWN_MILLIS = 8_500L;

    private static final int GROUND_SLAM_FALLBACK_SLOT_INDEX = 1;
    private static final Map<UUID, Long> COOLDOWN_END_BY_PLAYER = new ConcurrentHashMap<>();

    private GuardianGroundSlamCooldownService() {
    }

    static void onTriggered(Player player, int activeHotbarSlot, String itemId) {
        if (player == null || !isGroundSlamItemId(itemId)) {
            return;
        }

        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return;
        }

        long nowMillis = System.currentTimeMillis();
        long cooldownEndMillis = COOLDOWN_END_BY_PLAYER.compute(playerUuid, (ignored, currentEndMillis) -> {
            if (currentEndMillis != null && currentEndMillis > nowMillis) {
                return currentEndMillis;
            }
            return nowMillis + GROUND_SLAM_COOLDOWN_MILLIS;
        });

        long remainingMillis = cooldownEndMillis - nowMillis;
        int hudSlotIndex = activeHotbarSlot >= 0 ? activeHotbarSlot : GROUND_SLAM_FALLBACK_SLOT_INDEX;
        if (remainingMillis <= 0L) {
            DruidAbilityCooldownHotbarHud.remove(player, itemId);
            return;
        }

        DruidAbilityCooldownHotbarHud.showOrUpdate(
                player,
                hudSlotIndex,
                itemId,
                remainingMillis,
                GROUND_SLAM_COOLDOWN_MILLIS
        );
    }

    static boolean isGroundSlamItemId(String itemId) {
        return GROUND_SLAM_ITEM_ID.equals(itemId)
                || VERDANT_GROUND_SLAM_ITEM_ID.equals(itemId)
                || PRIMAL_GROUND_SLAM_ITEM_ID.equals(itemId)
                || ELDER_GROUND_SLAM_ITEM_ID.equals(itemId);
    }
}
