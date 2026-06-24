package me.druid.v1;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.druid.v1.forms.FormId;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class ProwlerStealthService {
    static final String STEALTH_ITEM_ID = "Tiger_Stealth";
    static final long STEALTH_COOLDOWN_MILLIS = 12_000L;

    private static final long RECENT_COMBAT_LOCKOUT_MILLIS = 6_000L;
    private static final long WATCHDOG_INTERVAL_MILLIS = 1_000L;
    private static final int STEALTH_FALLBACK_SLOT_INDEX = 3;
    private static final float STEALTH_SPEED_MULTIPLIER = 0.5f;
    private static final String STEALTH_VISUAL_EFFECT_ID = "Druid_Prowler_Stealth_Visual";
    private static final Map<UUID, Long> ACTIVE_STARTED_MILLIS_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> COOLDOWN_END_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> RECENT_COMBAT_MILLIS_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, MovementSettings> MOVEMENT_SNAPSHOT_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Object WATCHDOG_LOCK = new Object();
    private static ScheduledFuture<?> watchdog;

    private ProwlerStealthService() {
    }

    static boolean tryToggle(Player player, int activeHotbarSlot) {
        if (player == null) {
            return false;
        }

        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return false;
        }

        World world = player.getWorld();
        if (world == null || !world.isAlive()) {
            System.out.println("[ProwlerStealth] blocked reason=world-unavailable");
            return false;
        }

        try {
            world.execute(() -> tryToggleOnWorldThread(player, playerUuid, activeHotbarSlot));
        } catch (RuntimeException exception) {
            System.out.println("[ProwlerStealth] blocked reason=world-rejected-task");
            return false;
        }
        return true;
    }

    private static void tryToggleOnWorldThread(Player player, UUID playerUuid, int activeHotbarSlot) {
        int hudSlotIndex = activeHotbarSlot >= 0 ? activeHotbarSlot : STEALTH_FALLBACK_SLOT_INDEX;
        if (isStealthed(player)) {
            breakStealth(player, "toggle-off", hudSlotIndex);
            return;
        }

        if (ShapeshiftHandler.getActiveFormId(player) != FormId.FORM_PROWLER) {
            System.out.println("[ProwlerStealth] blocked reason=not-prowler");
            return;
        }
        if (isDeadOrInvalid(player)) {
            System.out.println("[ProwlerStealth] blocked reason=dead-or-invalid");
            return;
        }

        long nowMillis = System.currentTimeMillis();
        Long cooldownEndMillis = COOLDOWN_END_BY_PLAYER.get(playerUuid);
        if (cooldownEndMillis != null && cooldownEndMillis > nowMillis) {
            long remainingMillis = cooldownEndMillis - nowMillis;
            System.out.println("[ProwlerStealth] blocked reason=cooldown remainingMs="
                    + remainingMillis + " slot=" + hudSlotIndex);
            showCooldownHud(player, hudSlotIndex, remainingMillis);
            return;
        }
        COOLDOWN_END_BY_PLAYER.remove(playerUuid, cooldownEndMillis);

        Long recentCombatMillis = RECENT_COMBAT_MILLIS_BY_PLAYER.get(playerUuid);
        if (recentCombatMillis != null && nowMillis - recentCombatMillis < RECENT_COMBAT_LOCKOUT_MILLIS) {
            System.out.println("[ProwlerStealth] blocked reason=recent-combat remainingMs="
                    + (RECENT_COMBAT_LOCKOUT_MILLIS - (nowMillis - recentCombatMillis)));
            return;
        }

        ACTIVE_STARTED_MILLIS_BY_PLAYER.put(playerUuid, nowMillis);
        if (!activateOnWorldThread(player, playerUuid)) {
            return;
        }
        ensureWatchdogRunning();
        System.out.println("[ProwlerStealth] active slot=" + hudSlotIndex);
    }

    static boolean breakStealth(Player player, String reason) {
        return breakStealth(player, reason, STEALTH_FALLBACK_SLOT_INDEX);
    }

    static void recordCombat(Player player) {
        UUID playerUuid = player == null ? null : player.getUuid();
        if (playerUuid != null) {
            RECENT_COMBAT_MILLIS_BY_PLAYER.put(playerUuid, System.currentTimeMillis());
        }
    }

    static boolean isStealthed(Player player) {
        UUID playerUuid = player == null ? null : player.getUuid();
        return playerUuid != null && ACTIVE_STARTED_MILLIS_BY_PLAYER.containsKey(playerUuid);
    }

    static void cleanupPlayer(Player player, String reason) {
        if (player != null) {
            breakStealth(player, reason);
        }
    }

    static void handleIncomingDamage(Player player) {
        if (player == null || !isStealthed(player)) {
            return;
        }

        World world = player.getWorld();
        if (world == null || !world.isAlive()) {
            recordCombat(player);
            breakStealth(player, "damage");
            return;
        }

        try {
            world.execute(() -> {
                if (!isStealthed(player)) {
                    return;
                }
                recordCombat(player);
                breakStealth(player, "damage");
            });
        } catch (RuntimeException exception) {
            recordCombat(player);
            breakStealth(player, "damage");
        }
    }

    static void handleDisconnect(PlayerRef playerRef) {
        UUID playerUuid = playerRef == null ? null : playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }
        Player player = DruidPermissions.getOnlinePlayer(playerUuid);
        scheduleRestoreMovement(player, playerUuid);
        scheduleRemoveStealthVisual(player);
        ACTIVE_STARTED_MILLIS_BY_PLAYER.remove(playerUuid);
        ProwlerStealthNpcAttitudeService.refreshWorldAttitudeCaches(player, "disconnect");
        COOLDOWN_END_BY_PLAYER.remove(playerUuid);
        RECENT_COMBAT_MILLIS_BY_PLAYER.remove(playerUuid);
        DruidAbilityCooldownHotbarHud.remove(player, STEALTH_ITEM_ID);
        stopWatchdogIfIdle();
    }

    static void shutdown() {
        ArrayList<UUID> activePlayers = new ArrayList<>(ACTIVE_STARTED_MILLIS_BY_PLAYER.keySet());
        for (UUID playerUuid : activePlayers) {
                Player player = DruidPermissions.getOnlinePlayer(playerUuid);
            if (player != null) {
                scheduleRestoreMovement(player, playerUuid);
                scheduleRemoveStealthVisual(player);
            }
        }
        ACTIVE_STARTED_MILLIS_BY_PLAYER.clear();
        COOLDOWN_END_BY_PLAYER.clear();
        RECENT_COMBAT_MILLIS_BY_PLAYER.clear();
        MOVEMENT_SNAPSHOT_BY_PLAYER.clear();
        stopWatchdog();
    }

    private static boolean breakStealth(Player player, String reason, int hudSlotIndex) {
        UUID playerUuid = player == null ? null : player.getUuid();
        if (playerUuid == null || ACTIVE_STARTED_MILLIS_BY_PLAYER.remove(playerUuid) == null) {
            return false;
        }

        long nowMillis = System.currentTimeMillis();
        long cooldownEndMillis = nowMillis + STEALTH_COOLDOWN_MILLIS;
        COOLDOWN_END_BY_PLAYER.put(playerUuid, cooldownEndMillis);

        scheduleRestoreMovement(player, playerUuid);
        scheduleRemoveStealthVisual(player);
        ProwlerStealthNpcAttitudeService.refreshWorldAttitudeCaches(player, reason);

        System.out.println("[ProwlerStealth] ended reason=" + sanitizeReason(reason)
                + " cooldownMs=" + STEALTH_COOLDOWN_MILLIS);
        showCooldownHud(player, hudSlotIndex, STEALTH_COOLDOWN_MILLIS);
        stopWatchdogIfIdle();
        return true;
    }

    private static boolean activateOnWorldThread(Player player, UUID playerUuid) {
        if (player == null
                || playerUuid == null
                || !ACTIVE_STARTED_MILLIS_BY_PLAYER.containsKey(playerUuid)
                || ShapeshiftHandler.getActiveFormId(player) != FormId.FORM_PROWLER
                || isDeadOrInvalid(player)) {
            ACTIVE_STARTED_MILLIS_BY_PLAYER.remove(playerUuid);
            return false;
        }

        MovementManager movementManager = getMovementManager(player);
        MovementSettings settings = movementManager == null ? null : movementManager.getSettings();
        if (settings == null) {
            ACTIVE_STARTED_MILLIS_BY_PLAYER.remove(playerUuid);
            System.out.println("[ProwlerStealth] activation failed reason=movement-unavailable");
            return false;
        }

        MOVEMENT_SNAPSHOT_BY_PLAYER.computeIfAbsent(playerUuid, ignored -> new MovementSettings(settings));
        settings.baseSpeed *= STEALTH_SPEED_MULTIPLIER;
        updateMovement(player, movementManager);
        applyStealthVisual(player);
        ProwlerStealthNpcAttitudeService.refreshWorldAttitudeCaches(player, "activate");
        return true;
    }

    private static void scheduleRestoreMovement(Player player, UUID playerUuid) {
        MovementSettings snapshot = MOVEMENT_SNAPSHOT_BY_PLAYER.remove(playerUuid);
        if (player == null || snapshot == null) {
            return;
        }

        World world = player.getWorld();
        if (world == null || !world.isAlive()) {
            return;
        }

        try {
            world.execute(() -> restoreMovementOnWorldThread(player, snapshot));
        } catch (RuntimeException exception) {
            System.out.println("[ProwlerStealth] movement restore failed reason=world-rejected-task");
        }
    }

    private static void scheduleRemoveStealthVisual(Player player) {
        if (player == null) {
            return;
        }

        World world = player.getWorld();
        if (world == null || !world.isAlive()) {
            return;
        }

        try {
            world.execute(() -> removeStealthVisualOnWorldThread(player));
        } catch (RuntimeException exception) {
            System.out.println("[ProwlerStealth] visual remove failed reason=world-rejected-task");
        }
    }

    private static void restoreMovementOnWorldThread(Player player, MovementSettings snapshot) {
        if (player == null || snapshot == null) {
            return;
        }

        MovementManager movementManager = getMovementManager(player);
        MovementSettings settings = movementManager == null ? null : movementManager.getSettings();
        if (settings == null) {
            return;
        }

        copyMovementSettings(snapshot, settings);
        updateMovement(player, movementManager);
    }

    private static MovementManager getMovementManager(Player player) {
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }

        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            return null;
        }
        return store.getComponent(ref, MovementManager.getComponentType());
    }

    private static boolean isDeadOrInvalid(Player player) {
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        if (ref == null || !ref.isValid()) {
            return true;
        }

        Store<EntityStore> store = ref.getStore();
        return store == null
                || player.getWorld() == null
                || !player.getWorld().isAlive()
                || store.getComponent(ref, DeathComponent.getComponentType()) != null;
    }

    private static void updateMovement(Player player, MovementManager movementManager) {
        PlayerRef playerRef = DruidPlayerCompat.getPlayerRef(player);
        if (playerRef == null || movementManager == null) {
            return;
        }
        try {
            movementManager.update(playerRef.getPacketHandler());
        } catch (Exception exception) {
            System.out.println("[ProwlerStealth] movement update failed reason="
                    + exception.getClass().getSimpleName());
        }
    }

    private static void copyMovementSettings(MovementSettings source, MovementSettings target) {
        if (source == null || target == null) {
            return;
        }
        for (Field field : MovementSettings.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.set(target, field.get(source));
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    private static void showCooldownHud(Player player, int slotIndex, long remainingMillis) {
        if (remainingMillis <= 0L) {
            DruidAbilityCooldownHotbarHud.remove(player, STEALTH_ITEM_ID);
            return;
        }
        DruidAbilityCooldownHotbarHud.showOrUpdate(
                player,
                slotIndex,
                STEALTH_ITEM_ID,
                remainingMillis,
                STEALTH_COOLDOWN_MILLIS
        );
    }

    private static void applyStealthVisual(Player player) {
        applyEffectOnWorldThread(player, STEALTH_VISUAL_EFFECT_ID, "stealth-visual");
    }

    private static void applyEffectOnWorldThread(Player player, String effectId, String context) {
        if (player == null) {
            return;
        }

        EntityEffect effect = EntityEffect.getAssetMap().getAsset(effectId);
        if (effect == null) {
            System.out.println("[ProwlerStealth] effect skipped reason=effect-missing context="
                    + context + " id=" + effectId);
            return;
        }

        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        World world = player.getWorld();
        if (world == null) {
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

        EffectControllerComponent effectController = store.getComponent(playerRef, EffectControllerComponent.getComponentType());
        if (effectController == null) {
            return;
        }

        try {
            if (!effectController.hasEffect(effect)) {
                effectController.addEffect(playerRef, effect, store);
            }
        } catch (Exception exception) {
            System.out.println("[ProwlerStealth] effect apply failed reason="
                    + exception.getClass().getSimpleName() + " context=" + context);
        }
    }

    private static void removeStealthVisualOnWorldThread(Player player) {
        removeEffectOnWorldThread(player, STEALTH_VISUAL_EFFECT_ID, "stealth-visual");
    }

    private static void removeEffectOnWorldThread(Player player, String effectId, String context) {
        if (player == null) {
            return;
        }

        int effectIndex = EntityEffect.getAssetMap().getIndexOrDefault(effectId, -1);
        if (effectIndex < 0) {
            return;
        }

        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        World world = player.getWorld();
        if (world == null) {
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

        EffectControllerComponent effectController = store.getComponent(playerRef, EffectControllerComponent.getComponentType());
        if (effectController == null || !effectController.hasEffect(effectIndex)) {
            return;
        }

        try {
            effectController.removeEffect(playerRef, effectIndex, store);
        } catch (Exception exception) {
            System.out.println("[ProwlerStealth] effect remove failed reason="
                    + exception.getClass().getSimpleName() + " context=" + context);
        }
    }

    private static void ensureWatchdogRunning() {
        synchronized (WATCHDOG_LOCK) {
            if (watchdog != null && !watchdog.isDone()) {
                return;
            }
            watchdog = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
                    ProwlerStealthService::runWatchdog,
                    WATCHDOG_INTERVAL_MILLIS,
                    WATCHDOG_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private static void runWatchdog() {
        for (UUID playerUuid : new ArrayList<>(ACTIVE_STARTED_MILLIS_BY_PLAYER.keySet())) {
            Player player = DruidPermissions.getOnlinePlayer(playerUuid);
            if (player == null) {
                ACTIVE_STARTED_MILLIS_BY_PLAYER.remove(playerUuid);
                MOVEMENT_SNAPSHOT_BY_PLAYER.remove(playerUuid);
                continue;
            }
            World world = player.getWorld();
            if (world == null || !world.isAlive()) {
                ACTIVE_STARTED_MILLIS_BY_PLAYER.remove(playerUuid);
                MOVEMENT_SNAPSHOT_BY_PLAYER.remove(playerUuid);
                continue;
            }
            try {
                world.execute(() -> runWatchdogForPlayer(player));
            } catch (RuntimeException ignored) {
            }
        }
        stopWatchdogIfIdle();
    }

    private static void runWatchdogForPlayer(Player player) {
        if (!isStealthed(player)) {
            return;
        }
        if (isDeadOrInvalid(player)) {
            breakStealth(player, "death-or-invalid");
            return;
        }
        if (ShapeshiftHandler.getActiveFormId(player) != FormId.FORM_PROWLER) {
            breakStealth(player, "left-prowler");
        }
    }

    private static void stopWatchdogIfIdle() {
        if (ACTIVE_STARTED_MILLIS_BY_PLAYER.isEmpty()) {
            stopWatchdog();
        }
    }

    private static void stopWatchdog() {
        synchronized (WATCHDOG_LOCK) {
            if (watchdog != null) {
                watchdog.cancel(false);
                watchdog = null;
            }
        }
    }

    private static String sanitizeReason(String reason) {
        return reason == null || reason.isBlank()
                ? "unknown"
                : reason.replaceAll("[^A-Za-z0-9_-]", "-");
    }

}
