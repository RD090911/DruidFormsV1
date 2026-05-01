package me.druid.v1;

import com.hypixel.hytale.server.core.entity.entities.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class TransformCooldownService {
    private static final Map<UUID, Long> TRANSFORM_COOLDOWN_END_BY_PLAYER = new ConcurrentHashMap<>();
    private static final long TRANSFORM_COOLDOWN_MILLIS = 5_000L;

    private TransformCooldownService() {
    }

    static long getRemainingMillis(Player player) {
        UUID playerUuid = resolvePlayerUuid(player);
        if (playerUuid == null) {
            return 0L;
        }
        Long cooldownEndMillis = TRANSFORM_COOLDOWN_END_BY_PLAYER.get(playerUuid);
        if (cooldownEndMillis == null) {
            return 0L;
        }
        long remainingMillis = cooldownEndMillis - System.currentTimeMillis();
        if (remainingMillis <= 0L) {
            TRANSFORM_COOLDOWN_END_BY_PLAYER.remove(playerUuid);
            return 0L;
        }
        return remainingMillis;
    }

    static boolean isOnCooldown(Player player) {
        return getRemainingMillis(player) > 0L;
    }

    static void startCooldown(Player player) {
        UUID playerUuid = resolvePlayerUuid(player);
        if (playerUuid == null) {
            return;
        }
        TRANSFORM_COOLDOWN_END_BY_PLAYER.put(playerUuid, System.currentTimeMillis() + TRANSFORM_COOLDOWN_MILLIS);
    }

    static void clear(Player player) {
        UUID playerUuid = resolvePlayerUuid(player);
        if (playerUuid == null) {
            return;
        }
        TRANSFORM_COOLDOWN_END_BY_PLAYER.remove(playerUuid);
    }

    static void clear(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        TRANSFORM_COOLDOWN_END_BY_PLAYER.remove(playerUuid);
    }

    private static UUID resolvePlayerUuid(Player player) {
        if (player == null) {
            return null;
        }
        try {
            return player.getUuid();
        } catch (Exception ignored) {
            return null;
        }
    }
}
