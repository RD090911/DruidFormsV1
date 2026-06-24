package me.druid.v1;

import com.hypixel.hytale.server.core.entity.entities.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ProwlerBiteCooldownService {
    static final String BITE_ITEM_ID = "Tiger_Bite";
    static final long BITE_COOLDOWN_MILLIS = 10_000L;
    private static final int BITE_FALLBACK_SLOT_INDEX = 1;
    private static final Map<UUID, Long> COOLDOWN_END_BY_PLAYER = new ConcurrentHashMap<>();

    private ProwlerBiteCooldownService() {
    }

    static boolean tryStart(Player player, int activeHotbarSlot) {
        if (player == null) {
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
            return nowMillis + BITE_COOLDOWN_MILLIS;
        });

        long remainingMillis = cooldownEndMillis - nowMillis;
        int hudSlotIndex = activeHotbarSlot >= 0 ? activeHotbarSlot : BITE_FALLBACK_SLOT_INDEX;
        if (started[0]) {
            System.out.println("[ProwlerBiteCooldown] use accepted item="
                    + BITE_ITEM_ID + " cooldownMs="
                    + BITE_COOLDOWN_MILLIS + " slot=" + hudSlotIndex);
        } else {
            System.out.println("[ProwlerBiteCooldown] blocked item="
                    + BITE_ITEM_ID + " remainingMs="
                    + remainingMillis + " slot=" + hudSlotIndex);
        }
        showCooldownHud(player, hudSlotIndex, remainingMillis);
        return started[0];
    }

    private static void showCooldownHud(Player player, int slotIndex, long remainingMillis) {
        if (remainingMillis <= 0L) {
            DruidAbilityCooldownHotbarHud.remove(player, BITE_ITEM_ID);
            return;
        }
        System.out.println("[ProwlerBiteCooldown] hud show slot="
                + slotIndex + " remainingMs=" + remainingMillis);
        DruidAbilityCooldownHotbarHud.showOrUpdate(
                player,
                slotIndex,
                BITE_ITEM_ID,
                remainingMillis,
                BITE_COOLDOWN_MILLIS
        );
    }
}
