package me.druid.v1;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionSyncData;
import com.hypixel.hytale.protocol.SelectedHitEntity;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class ProwlerBiteCooldownService {
    static final String BITE_ITEM_ID = "Tiger_Bite";
    static final long BITE_COOLDOWN_MILLIS = 10_000L;
    private static final String BITE_SOUND_EVENT_ID = "SFX_Emberwulf_Attack_Bite";
    private static final int BITE_FALLBACK_SLOT_INDEX = 1;
    private static final long BITE_PENDING_TIMEOUT_MILLIS = 1_500L;
    private static final Map<UUID, Long> COOLDOWN_END_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingBiteHit> PENDING_HIT_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, ScheduledFuture<?>> PENDING_HIT_TASK_BY_PLAYER = new ConcurrentHashMap<>();

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
        Long cooldownEndMillis = COOLDOWN_END_BY_PLAYER.get(playerUuid);
        int hudSlotIndex = activeHotbarSlot >= 0 ? activeHotbarSlot : BITE_FALLBACK_SLOT_INDEX;
        if (cooldownEndMillis != null && cooldownEndMillis > nowMillis) {
            long remainingMillis = cooldownEndMillis - nowMillis;
            System.out.println("[ProwlerBiteCooldown] blocked item="
                    + BITE_ITEM_ID + " remainingMs="
                    + remainingMillis + " slot=" + hudSlotIndex);
            showCooldownHud(player, hudSlotIndex, remainingMillis);
            return false;
        }

        COOLDOWN_END_BY_PLAYER.remove(playerUuid, cooldownEndMillis);
        return true;
    }

    static void armPendingHit(UUID playerUuid, int chainId, String biteItemId, int activeHotbarSlot) {
        if (playerUuid == null || chainId < 0 || biteItemId == null || biteItemId.isBlank()) {
            return;
        }
        clearPendingHit(playerUuid);
        int hudSlotIndex = activeHotbarSlot >= 0 ? activeHotbarSlot : BITE_FALLBACK_SLOT_INDEX;
        PENDING_HIT_BY_PLAYER.put(playerUuid, new PendingBiteHit(chainId, biteItemId, hudSlotIndex));
        ScheduledFuture<?> timeoutTask = HytaleServer.SCHEDULED_EXECUTOR.schedule(
                () -> expirePendingHit(playerUuid, chainId),
                BITE_PENDING_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS
        );
        PENDING_HIT_TASK_BY_PLAYER.put(playerUuid, timeoutTask);
    }

    static void handleInteractionUpdate(UUID playerUuid, SyncInteractionChain chain) {
        if (playerUuid == null || chain == null) {
            return;
        }

        PendingBiteHit pending = PENDING_HIT_BY_PLAYER.get(playerUuid);
        if (pending == null || pending.chainId != chain.chainId) {
            return;
        }

        int[] targetNetworkIds = extractTargetNetworkIds(chain.interactionData);
        if (targetNetworkIds.length > 0) {
            clearPendingHit(playerUuid);

            Player player = DruidPermissions.getOnlinePlayer(playerUuid);
            if (player == null) {
                return;
            }

            String effectId = resolvePoisonEffectId(pending.biteItemId);
            if (effectId == null) {
                return;
            }

            World world = player.getWorld();
            if (world == null || !world.isAlive()) {
                return;
            }

            try {
                world.execute(() -> handleConfirmedHitOnWorldThread(player, pending.hudSlotIndex, effectId, targetNetworkIds));
            } catch (RuntimeException ignored) {
            }
            return;
        }

        if (isTerminalState(chain.state)) {
            clearPendingHit(playerUuid);
        }
    }

    static void handleDisconnect(PlayerRef playerRef) {
        UUID playerUuid = playerRef == null ? null : playerRef.getUuid();
        if (playerUuid != null) {
            clearPendingHit(playerUuid);
        }
    }

    static void shutdown() {
        for (ScheduledFuture<?> task : PENDING_HIT_TASK_BY_PLAYER.values()) {
            if (task != null) {
                task.cancel(false);
            }
        }
        PENDING_HIT_TASK_BY_PLAYER.clear();
        PENDING_HIT_BY_PLAYER.clear();
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

    private static void handleConfirmedHitOnWorldThread(Player player, int hudSlotIndex, String effectId, int[] targetNetworkIds) {
        if (player == null || effectId == null || targetNetworkIds == null || targetNetworkIds.length == 0) {
            return;
        }

        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return;
        }

        long nowMillis = System.currentTimeMillis();
        Long existingCooldownEndMillis = COOLDOWN_END_BY_PLAYER.get(playerUuid);
        if (existingCooldownEndMillis != null && existingCooldownEndMillis > nowMillis) {
            showCooldownHud(player, hudSlotIndex, existingCooldownEndMillis - nowMillis);
            return;
        }

        long cooldownEndMillis = nowMillis + BITE_COOLDOWN_MILLIS;
        COOLDOWN_END_BY_PLAYER.put(playerUuid, cooldownEndMillis);
        DruidSoundFeedback.playForPlayer(player, BITE_SOUND_EVENT_ID);
        System.out.println("[ProwlerBiteCooldown] use accepted item="
                + BITE_ITEM_ID + " cooldownMs="
                + BITE_COOLDOWN_MILLIS + " slot=" + hudSlotIndex);
        showCooldownHud(player, hudSlotIndex, BITE_COOLDOWN_MILLIS);
        applyPoisonOnWorldThread(player, effectId, targetNetworkIds);
    }

    private static void applyPoisonOnWorldThread(Player player, String effectId, int[] targetNetworkIds) {
        if (player == null || effectId == null || targetNetworkIds == null || targetNetworkIds.length == 0) {
            return;
        }

        Ref<EntityStore> sourceRef = player.getReference();
        World world = player.getWorld();
        EntityStore entityStore = world == null ? null : world.getEntityStore();
        Store<EntityStore> store = entityStore == null ? null : entityStore.getStore();
        EntityEffect poisonEffect = EntityEffect.getAssetMap().getAsset(effectId);
        if (sourceRef == null || !sourceRef.isValid() || store == null || poisonEffect == null) {
            return;
        }

        for (int targetNetworkId : targetNetworkIds) {
            if (targetNetworkId <= 0) {
                continue;
            }

            Ref<EntityStore> targetRef = entityStore.getRefFromNetworkId(targetNetworkId);
            if (targetRef == null || !targetRef.isValid() || targetRef.equals(sourceRef)) {
                continue;
            }

            EffectControllerComponent effectController = store.getComponent(targetRef, EffectControllerComponent.getComponentType());
            if (effectController == null) {
                continue;
            }

            try {
                effectController.addEffect(targetRef, poisonEffect, store);
            } catch (Exception ignored) {
            }
        }
    }

    private static String resolvePoisonEffectId(String biteItemId) {
        String normalized = DruidAbilityItemIds.normalizeItemId(biteItemId);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        if (normalized.contains("Elder_Tiger_Bite")) {
            return "Poison_T3";
        }
        if (normalized.contains("Primal_Tiger_Bite")) {
            return "Poison_T2";
        }
        if (normalized.contains("Tiger_Bite")) {
            return "Poison_T1";
        }
        return null;
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

    private static void expirePendingHit(UUID playerUuid, int chainId) {
        if (playerUuid == null) {
            return;
        }
        PendingBiteHit pending = PENDING_HIT_BY_PLAYER.get(playerUuid);
        if (pending == null || pending.chainId != chainId) {
            return;
        }
        clearPendingHit(playerUuid);
    }

    private static void clearPendingHit(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        PENDING_HIT_BY_PLAYER.remove(playerUuid);
        ScheduledFuture<?> task = PENDING_HIT_TASK_BY_PLAYER.remove(playerUuid);
        if (task != null) {
            task.cancel(false);
        }
    }

    private record PendingBiteHit(int chainId, String biteItemId, int hudSlotIndex) {
    }
}
