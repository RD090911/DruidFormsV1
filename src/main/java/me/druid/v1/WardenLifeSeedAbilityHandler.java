package me.druid.v1;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import me.druid.v1.forms.FormId;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class WardenLifeSeedAbilityHandler {
    private static final Map<UUID, Long> COOLDOWN_END_BY_OWNER = new ConcurrentHashMap<>();
    private static final Map<UUID, ScheduledFuture<?>> ACTIVE_SEEDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> EXPIRES_AT_MILLIS_BY_OWNER = new ConcurrentHashMap<>();
    private static final Map<UUID, Vector3f> SEED_POSITION_BY_OWNER = new ConcurrentHashMap<>();
    private static final long LIFE_SEED_COOLDOWN_MILLIS = 20_000L;
    private static final long HOT_DURATION_MILLIS = 10_000L;
    private static final long HOT_TICK_INTERVAL_MILLIS = 500L;
    private static final double LIFE_SEED_SCAN_RADIUS = 5.0d;
    private static final double LIFE_SEED_SCAN_RADIUS_SQUARED = LIFE_SEED_SCAN_RADIUS * LIFE_SEED_SCAN_RADIUS;
    private static final String HEALING_TOTEM_EFFECT_ID = "Healing_Totem_Heal";
    private static final String LIFE_SEED_GROUND_VISUAL_SYSTEM_ID = "Totem_Heal_Simple_Test";
    private static final float FALLBACK_HEAL_AMOUNT = 1.0f;
    private static final float VERDANT_HEAL_AMOUNT = 1.25f;
    private static final float PRIMAL_HEAL_AMOUNT = 1.5f;
    private static final float ELDER_HEAL_AMOUNT = 2.0f;

    private WardenLifeSeedAbilityHandler() {
    }

    public static long getLifeSeedCooldownRemainingMillis(Player player) {
        if (player == null) {
            return 0L;
        }

        UUID ownerId = player.getUuid();
        if (ownerId == null) {
            return 0L;
        }

        Long end = COOLDOWN_END_BY_OWNER.get(ownerId);
        if (end == null) {
            return 0L;
        }

        long remaining = end - System.currentTimeMillis();
        return Math.max(0L, remaining);
    }

    static void trigger(Player player) {
        if (player == null) {
            return;
        }

        if (player.getWorld() != null) {
            player.getWorld().execute(() -> triggerOnWorldThread(player));
            return;
        }

        triggerOnWorldThread(player);
    }

    private static void triggerOnWorldThread(Player player) {
        if (player == null) {
            return;
        }

        FormId activeFormId = ShapeshiftHandler.getActiveFormId(player);
        if (activeFormId != FormId.FORM_WARDEN) {
            return;
        }

        if (!canUseAbility(player)) {
            return;
        }

        System.out.println("Placing Warden Life Seed at computed position");
        if (executeAbility(player)) {
            System.out.println("Warden Life Seed placement confirmed");
        }
    }

    private static boolean canUseAbility(Player player) {
        if (player == null) {
            return false;
        }

        UUID ownerId = player.getUuid();
        if (ownerId == null) {
            return false;
        }

        long nowMillis = System.currentTimeMillis();
        Long cooldownEndMillis = COOLDOWN_END_BY_OWNER.get(ownerId);
        if (cooldownEndMillis == null) {
            return true;
        }

        if (nowMillis >= cooldownEndMillis) {
            COOLDOWN_END_BY_OWNER.remove(ownerId);
            return true;
        }

        long remainingMillis = cooldownEndMillis - nowMillis;
        double remainingSeconds = remainingMillis / 1000.0d;
        sendPlayerMessage(player, String.format(Locale.ROOT, "Life Seed recharging: %.1fs", remainingSeconds));
        showCooldownHud(player);
        System.out.println(String.format(
                Locale.ROOT,
                "LifeSeed cast blocked cooldownRemainingMillis=%d cooldownRemainingSeconds=%.1f owner=%s",
                remainingMillis,
                remainingSeconds,
                safeDisplayName(player)
        ));
        return false;
    }

    private static boolean executeAbility(Player player) {
        Vector3f seedPosition = placeTotemPlaceholder(player);
        boolean placed = seedPosition != null;
        if (placed && seedPosition != null) {
            scanPotentialTargets(player);
            placed = startOrReplaceHotTask(player, seedPosition);
            if (placed) {
                UUID ownerId = player.getUuid();
                if (ownerId != null) {
                    COOLDOWN_END_BY_OWNER.put(ownerId, System.currentTimeMillis() + LIFE_SEED_COOLDOWN_MILLIS);
                    showCooldownHud(player);
                }
            }
        }
        System.out.println("Warden Life Seed ability triggered");
        sendPlayerMessage(player, "Life Seed pulses with healing energy.");
        return placed;
    }

    private static Vector3f placeTotemPlaceholder(Player player) {
        if (player == null) {
            return null;
        }

        TransformComponent transform = player.getTransformComponent();
        if (transform == null) {
            return null;
        }

        Vector3d playerPosition = transform.getPosition();
        if (playerPosition == null) {
            return null;
        }

        Vector3d spawnPosition = new Vector3d(playerPosition);
        Vector3f seedPosition = new Vector3f((float) spawnPosition.getX(), (float) spawnPosition.getY(), (float) spawnPosition.getZ());

        System.out.println(String.format(
                Locale.ROOT,
                "Warden Life Seed placeholder placed at [%.2f,%.2f,%.2f]",
                spawnPosition.getX(),
                spawnPosition.getY(),
                spawnPosition.getZ()
        ));
        return seedPosition;
    }

    public static Optional<Vector3d> getActiveTotemPosition(Player player) {
        if (player == null || player.getUuid() == null) {
            return Optional.empty();
        }

        UUID playerId = player.getUuid();
        Vector3f active = SEED_POSITION_BY_OWNER.get(playerId);
        if (active == null) {
            return Optional.empty();
        }

        Long expiresAtMillis = EXPIRES_AT_MILLIS_BY_OWNER.get(playerId);
        if (expiresAtMillis == null) {
            endSeed(playerId, "missing expiry");
            return Optional.empty();
        }

        if (cleanupExpiredTotem(playerId, System.currentTimeMillis())) {
            return Optional.empty();
        }

        return Optional.of(new Vector3d(active.getX(), active.getY(), active.getZ()));
    }

    private static boolean cleanupExpiredTotem(UUID playerId, long nowMillis) {
        if (playerId == null) {
            return false;
        }

        Long expiresAtMillis = EXPIRES_AT_MILLIS_BY_OWNER.get(playerId);
        if (expiresAtMillis == null || nowMillis < expiresAtMillis) {
            return false;
        }

        endSeed(playerId, "expired");
        return true;
    }

    private static boolean startOrReplaceHotTask(Player owner, Vector3f seedPosition) {
        if (owner == null || seedPosition == null) {
            return false;
        }
        UUID ownerId = owner.getUuid();
        if (ownerId == null) {
            return false;
        }

        ScheduledFuture<?> previous = ACTIVE_SEEDS.remove(ownerId);
        if (previous != null) {
            previous.cancel(false);
            System.out.println("LifeSeed existing task canceled for " + safeDisplayName(owner));
        }

        long expiresAtMillis = System.currentTimeMillis() + HOT_DURATION_MILLIS;
        SEED_POSITION_BY_OWNER.put(ownerId, new Vector3f(seedPosition));
        EXPIRES_AT_MILLIS_BY_OWNER.put(ownerId, expiresAtMillis);
        spawnLifeSeedGroundVisual(owner, SEED_POSITION_BY_OWNER.get(ownerId));

        System.out.println(String.format(
                Locale.ROOT,
                "Warden Life Seed expires at [%d]",
                expiresAtMillis
        ));
        System.out.println(String.format(
                Locale.ROOT,
                "Warden Life Seed active position = [%.2f,%.2f,%.2f]",
                seedPosition.getX(),
                seedPosition.getY(),
                seedPosition.getZ()
        ));

        ScheduledFuture<?> scheduled = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
            Player currentOwner = DruidPermissions.getOnlinePlayer(ownerId);
            if (currentOwner == null) {
                endSeed(ownerId, "owner missing");
                return;
            }

            World ownerWorld = currentOwner.getWorld();
            if (ownerWorld == null) {
                endSeed(ownerId, "owner world missing");
                return;
            }

            ownerWorld.execute(() -> runPulse(currentOwner));
        }, 0L, HOT_TICK_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);

        ACTIVE_SEEDS.put(ownerId, scheduled);
        System.out.println("LifeSeed scheduler started for " + safeDisplayName(owner));
        return true;
    }

    private static void spawnLifeSeedGroundVisual(Player owner, Vector3f seedPosition) {
        if (owner == null) {
            System.out.println("LifeSeed ground visual skipped reason=owner-null");
            return;
        }
        if (seedPosition == null) {
            System.out.println("LifeSeed ground visual skipped reason=position-null");
            return;
        }

        World world = owner.getWorld();
        if (world == null) {
            System.out.println("LifeSeed ground visual skipped reason=owner-world-null");
            return;
        }

        EntityStore entityStore = world.getEntityStore();
        if (entityStore == null) {
            System.out.println("LifeSeed ground visual skipped reason=entity-store-null");
            return;
        }

        Store<EntityStore> store = entityStore.getStore();
        if (store == null) {
            System.out.println("LifeSeed ground visual skipped reason=store-null");
            return;
        }

        Vector3d visualPosition = new Vector3d(seedPosition.getX(), seedPosition.getY(), seedPosition.getZ());
        try {
            ParticleUtil.spawnParticleEffect(LIFE_SEED_GROUND_VISUAL_SYSTEM_ID, visualPosition, store);
            System.out.println("LifeSeed ground visual spawned system=Totem_Heal_Simple_Test position=" + visualPosition);
        } catch (Exception exception) {
            System.out.println("LifeSeed ground visual skipped reason=spawn-failed:" + exception.getClass().getSimpleName());
        }
    }

    private static void runPulse(Player owner) {
        if (owner == null) {
            return;
        }

        UUID ownerId = owner.getUuid();
        if (ownerId == null) {
            return;
        }

        Long expiresAtMillis = EXPIRES_AT_MILLIS_BY_OWNER.get(ownerId);
        if (expiresAtMillis == null || System.currentTimeMillis() >= expiresAtMillis) {
            endSeed(ownerId, "expired");
            return;
        }

        Vector3f seedPositionRaw = SEED_POSITION_BY_OWNER.get(ownerId);
        if (seedPositionRaw == null) {
            endSeed(ownerId, "missing position");
            return;
        }

        World ownerWorld = owner.getWorld();
        if (ownerWorld == null) {
            endSeed(ownerId, "owner world missing");
            return;
        }

        Vector3d seedPosition = new Vector3d(seedPositionRaw.getX(), seedPositionRaw.getY(), seedPositionRaw.getZ());
        List<Player> players = DruidPermissions.getOnlinePlayersSnapshot();
        EntityEffect healingEffect = EntityEffect.getAssetMap().getAsset(HEALING_TOTEM_EFFECT_ID);
        float fallbackHealAmount = resolveLifeSeedHealAmount(owner);

        int targets = 0;
        for (Player candidate : players) {
            if (candidate == null) {
                continue;
            }
            if (candidate.getWorld() != ownerWorld) {
                continue;
            }

            TransformComponent candidateTransform = candidate.getTransformComponent();
            if (candidateTransform == null) {
                continue;
            }

            Vector3d candidatePosition = candidateTransform.getPosition();
            if (candidatePosition == null) {
                continue;
            }

            double dx = candidatePosition.getX() - seedPosition.getX();
            double dy = candidatePosition.getY() - seedPosition.getY();
            double dz = candidatePosition.getZ() - seedPosition.getZ();
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared > LIFE_SEED_SCAN_RADIUS_SQUARED) {
                continue;
            }

            targets++;
            String targetName = safeDisplayName(candidate);

            boolean visualApplied = tryApplyHealingEffect(candidate, healingEffect);
            boolean directHealApplied = tryApplyFallbackHeal(candidate, fallbackHealAmount);
            System.out.println(String.format(
                    Locale.ROOT,
                    "LifeSeed heal results for %s visualApplied=%s directHealApplied=%s fallbackHeal=%.2f",
                    targetName,
                    visualApplied,
                    directHealApplied,
                    fallbackHealAmount
            ));
        }

        System.out.println(String.format(Locale.ROOT, "LifeSeed tick: targets=%d", targets));
    }

    private static void scanPotentialTargets(Player player) {
        Optional<Vector3d> activePosition = getActiveTotemPosition(player);
        if (activePosition.isEmpty()) {
            System.out.println("Warden Life Seed scan skipped: no active seed position");
            System.out.println("Warden Life Seed scan complete: targets=0");
            return;
        }

        List<Player> players = DruidPermissions.getOnlinePlayersSnapshot();
        if (players.isEmpty()) {
            System.out.println("Warden Life Seed scan skipped: no remembered online players");
            System.out.println("Warden Life Seed scan complete: targets=0");
            return;
        }

        Vector3d seedPosition = activePosition.get();
        int targets = 0;
        for (Player candidate : players) {
            if (candidate == null) {
                continue;
            }
            if (player != null && player.getWorld() != null && candidate.getWorld() != player.getWorld()) {
                continue;
            }

            TransformComponent candidateTransform = candidate.getTransformComponent();
            if (candidateTransform == null) {
                continue;
            }

            Vector3d candidatePosition = candidateTransform.getPosition();
            if (candidatePosition == null) {
                continue;
            }

            double dx = candidatePosition.getX() - seedPosition.getX();
            double dy = candidatePosition.getY() - seedPosition.getY();
            double dz = candidatePosition.getZ() - seedPosition.getZ();
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared > LIFE_SEED_SCAN_RADIUS_SQUARED) {
                continue;
            }

            double distance = Math.sqrt(distanceSquared);
            System.out.println(String.format(
                    Locale.ROOT,
                    "Warden Life Seed would heal %s uuid=%s distance=%.2f",
                    safeDisplayName(candidate),
                    safeUuid(candidate),
                    distance
            ));
            targets++;
        }

        System.out.println(String.format(
                Locale.ROOT,
                "Warden Life Seed scan complete: targets=%d",
                targets
        ));
    }

    private static boolean tryApplyHealingEffect(Player target, EntityEffect healingEffect) {
        if (target == null || healingEffect == null) {
            return false;
        }

        Ref<EntityStore> targetRef = target.getReference();
        if (targetRef == null || !targetRef.isValid()) {
            return false;
        }

        World targetWorld = target.getWorld();
        if (targetWorld == null) {
            return false;
        }

        EntityStore entityStore = targetWorld.getEntityStore();
        if (entityStore == null) {
            return false;
        }

        Store<EntityStore> store = entityStore.getStore();
        if (store == null) {
            return false;
        }

        EffectControllerComponent effectController = store.getComponent(targetRef, EffectControllerComponent.getComponentType());
        if (effectController == null) {
            return false;
        }

        try {
            return effectController.addEffect(targetRef, healingEffect, store);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean tryApplyFallbackHeal(Player target, float healAmount) {
        if (target == null) {
            return false;
        }
        try {
            EntityStatMap stats = EntityStatsModule.get(target);
            if (stats == null) {
                return false;
            }
            stats.addStatValue(DefaultEntityStatTypes.getHealth(), healAmount);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static float resolveLifeSeedHealAmount(Player owner) {
        String lowerHeldItemId = getHeldItemIdLower(owner);
        if (lowerHeldItemId == null) {
            return FALLBACK_HEAL_AMOUNT;
        }
        if (lowerHeldItemId.contains("elder_life_seed")) {
            return ELDER_HEAL_AMOUNT;
        }
        if (lowerHeldItemId.contains("primal_life_seed")) {
            return PRIMAL_HEAL_AMOUNT;
        }
        if (lowerHeldItemId.contains("verdant_life_seed")) {
            return VERDANT_HEAL_AMOUNT;
        }
        if (lowerHeldItemId.contains("life_seed")) {
            return FALLBACK_HEAL_AMOUNT;
        }
        return FALLBACK_HEAL_AMOUNT;
    }

    private static String getHeldItemIdLower(Player owner) {
        if (owner == null) {
            return null;
        }
        try {
            Object inventory = owner.getClass().getMethod("getInventory").invoke(owner);
            if (inventory == null) {
                return null;
            }
            Object held = inventory.getClass().getMethod("getItemInHand").invoke(inventory);
            if (held == null) {
                return null;
            }
            Object itemId = held.getClass().getMethod("getItemId").invoke(held);
            if (!(itemId instanceof String)) {
                return null;
            }
            return ((String) itemId).toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void endSeed(UUID ownerId, String reason) {
        if (ownerId == null) {
            return;
        }

        ScheduledFuture<?> scheduled = ACTIVE_SEEDS.remove(ownerId);
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        SEED_POSITION_BY_OWNER.remove(ownerId);
        EXPIRES_AT_MILLIS_BY_OWNER.remove(ownerId);

        Player owner = DruidPermissions.getOnlinePlayer(ownerId);
        String ownerLabel = owner != null ? safeDisplayName(owner) : ownerId.toString();
        System.out.println("LifeSeed ended for " + ownerLabel + " reason=" + reason);
    }

    private static String safeDisplayName(Player player) {
        if (player == null) {
            return "unknown";
        }
        try {
            String displayName = player.getDisplayName();
            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    private static UUID safeUuid(Player player) {
        if (player == null) {
            return null;
        }
        try {
            return player.getUuid();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void sendPlayerMessage(Player player, String text) {
        if (player == null || text == null) return;
        FormattedMessage component = new FormattedMessage();
        component.rawText = text;
        player.sendMessage(new Message(component));
    }

    private static void showCooldownHud(Player player) {
        if (player == null) {
            return;
        }

        WardenLifeSeedCooldownHud.showOrUpdate(player);
    }

}
