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
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.druid.v1.forms.FormId;
import org.joml.Vector3d;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class WardenSpringOfRenewalAbilityHandler {
    private static final Map<UUID, Long> COOLDOWN_END_BY_OWNER = new ConcurrentHashMap<>();
    private static final Map<UUID, ScheduledFuture<?>> ACTIVE_SPRINGS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> EXPIRES_AT_MILLIS_BY_OWNER = new ConcurrentHashMap<>();
    private static final Map<UUID, Vector3d> SPRING_POSITION_BY_OWNER = new ConcurrentHashMap<>();
    private static final long SPRING_COOLDOWN_MILLIS = 45_000L;
    private static final long SPRING_DURATION_MILLIS = 8_000L;
    private static final long SPRING_TICK_INTERVAL_MILLIS = 1_000L;
    private static final double SPRING_RADIUS = 5.0d;
    private static final double SPRING_RADIUS_SQUARED = SPRING_RADIUS * SPRING_RADIUS;
    private static final float SPRING_BASE_HEAL_AMOUNT = 5.0f;
    private static final float SPRING_VERDANT_HEAL_AMOUNT = 6.0f;
    private static final float SPRING_PRIMAL_HEAL_AMOUNT = 7.0f;
    private static final float SPRING_ELDER_HEAL_AMOUNT = 8.0f;
    private static final String SPRING_HEAL_EFFECT_ID = "Healing_Totem_Heal";
    private static final String SPRING_GROUND_VISUAL_SYSTEM_ID = "Totem_Heal_Simple_Test";

    private WardenSpringOfRenewalAbilityHandler() {
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

        Vector3d springPosition = getPlayerPosition(player);
        if (springPosition == null) {
            System.out.println("[WardenSpringOfRenewal] cast failed reason=position-missing owner="
                    + DruidPlayerCompat.getPlayerNameOrUnknown(player));
            return;
        }

        UUID ownerId = player.getUuid();
        if (ownerId == null) {
            return;
        }

        startOrReplaceSpring(player, ownerId, springPosition);
        COOLDOWN_END_BY_OWNER.put(ownerId, System.currentTimeMillis() + SPRING_COOLDOWN_MILLIS);
        showCooldownHud(player);
        sendPlayerMessage(player, "Spring of Renewal blooms beneath you.");
        System.out.println(String.format(Locale.ROOT,
                "[WardenSpringOfRenewal] cast owner=%s durationMillis=%d radius=%.1f",
                DruidPlayerCompat.getPlayerNameOrUnknown(player),
                SPRING_DURATION_MILLIS,
                SPRING_RADIUS));
    }

    private static boolean canUseAbility(Player player) {
        UUID ownerId = player == null ? null : player.getUuid();
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
        sendPlayerMessage(player, String.format(Locale.ROOT, "Spring of Renewal recharging: %.1fs", remainingSeconds));
        showCooldownHud(player);
        System.out.println(String.format(Locale.ROOT,
                "[WardenSpringOfRenewal] blocked cooldownRemainingMillis=%d cooldownRemainingSeconds=%.1f owner=%s",
                remainingMillis,
                remainingSeconds,
                DruidPlayerCompat.getPlayerNameOrUnknown(player)));
        return false;
    }

    private static void startOrReplaceSpring(Player owner, UUID ownerId, Vector3d springPosition) {
        ScheduledFuture<?> previous = ACTIVE_SPRINGS.remove(ownerId);
        if (previous != null) {
            previous.cancel(false);
        }

        long expiresAtMillis = System.currentTimeMillis() + SPRING_DURATION_MILLIS;
        SPRING_POSITION_BY_OWNER.put(ownerId, new Vector3d(springPosition));
        EXPIRES_AT_MILLIS_BY_OWNER.put(ownerId, expiresAtMillis);
        spawnGroundVisual(owner, springPosition);

        ScheduledFuture<?> scheduled = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
            Player currentOwner = DruidPermissions.getOnlinePlayer(ownerId);
            if (currentOwner == null) {
                endSpring(ownerId, "owner-missing");
                return;
            }

            World world = currentOwner.getWorld();
            if (world == null) {
                endSpring(ownerId, "world-missing");
                return;
            }

            world.execute(() -> runPulse(currentOwner));
        }, 0L, SPRING_TICK_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);

        ACTIVE_SPRINGS.put(ownerId, scheduled);
    }

    private static void runPulse(Player owner) {
        UUID ownerId = owner == null ? null : owner.getUuid();
        if (ownerId == null) {
            return;
        }

        Long expiresAtMillis = EXPIRES_AT_MILLIS_BY_OWNER.get(ownerId);
        if (expiresAtMillis == null || System.currentTimeMillis() >= expiresAtMillis) {
            endSpring(ownerId, "expired");
            return;
        }

        Vector3d springPosition = SPRING_POSITION_BY_OWNER.get(ownerId);
        World ownerWorld = owner.getWorld();
        if (springPosition == null || ownerWorld == null) {
            endSpring(ownerId, "state-missing");
            return;
        }

        EntityEffect healingEffect = EntityEffect.getAssetMap().getAsset(SPRING_HEAL_EFFECT_ID);
        float healAmount = resolveSpringHealAmount(owner);
        List<Player> players = DruidPermissions.getOnlinePlayersSnapshot();
        int targets = 0;
        for (Player candidate : players) {
            if (candidate == null || candidate.getWorld() != ownerWorld) {
                continue;
            }

            Vector3d candidatePosition = getPlayerPosition(candidate);
            if (candidatePosition == null) {
                continue;
            }

            double dx = candidatePosition.x - springPosition.x;
            double dy = candidatePosition.y - springPosition.y;
            double dz = candidatePosition.z - springPosition.z;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared > SPRING_RADIUS_SQUARED) {
                continue;
            }

            boolean healed = applyHeal(candidate, healAmount);
            boolean visualApplied = applyHealVisual(candidate, healingEffect);
            if (healed) {
                targets++;
            }
            System.out.println(String.format(Locale.ROOT,
                    "[WardenSpringOfRenewal] tick target=%s healed=%s visual=%s healAmount=%.1f",
                    DruidPlayerCompat.getPlayerNameOrUnknown(candidate),
                    healed,
                    visualApplied,
                    healAmount));
        }

        System.out.println(String.format(Locale.ROOT, "[WardenSpringOfRenewal] tick targets=%d", targets));
    }

    private static Vector3d getPlayerPosition(Player player) {
        if (player == null) {
            return null;
        }

        TransformComponent transform = DruidPlayerCompat.getTransformComponent(player);
        if (transform == null) {
            return null;
        }

        Vector3d position = transform.getPosition();
        return position == null ? null : new Vector3d(position);
    }

    private static boolean applyHeal(Player player, float healAmount) {
        try {
            EntityStatMap stats = EntityStatsModule.get(player);
            if (stats == null) {
                return false;
            }
            stats.addStatValue(DefaultEntityStatTypes.getHealth(), healAmount);
            return true;
        } catch (Exception exception) {
            System.out.println("[WardenSpringOfRenewal] heal failed reason=" + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static float resolveSpringHealAmount(Player owner) {
        String lowerHeldItemId = getHeldItemIdLower(owner);
        if (lowerHeldItemId == null) {
            return SPRING_BASE_HEAL_AMOUNT;
        }
        if (lowerHeldItemId.contains("elder_spring_of_renewal")) {
            return SPRING_ELDER_HEAL_AMOUNT;
        }
        if (lowerHeldItemId.contains("primal_spring_of_renewal")) {
            return SPRING_PRIMAL_HEAL_AMOUNT;
        }
        if (lowerHeldItemId.contains("verdant_spring_of_renewal")) {
            return SPRING_VERDANT_HEAL_AMOUNT;
        }
        if (lowerHeldItemId.contains("spring_of_renewal")) {
            return SPRING_BASE_HEAL_AMOUNT;
        }
        return SPRING_BASE_HEAL_AMOUNT;
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

    private static boolean applyHealVisual(Player player, EntityEffect healingEffect) {
        if (player == null || healingEffect == null) {
            return false;
        }

        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }

        World world = player.getWorld();
        if (world == null) {
            return false;
        }

        EntityStore entityStore = world.getEntityStore();
        if (entityStore == null) {
            return false;
        }

        Store<EntityStore> store = entityStore.getStore();
        if (store == null) {
            return false;
        }

        EffectControllerComponent effectController = store.getComponent(playerRef, EffectControllerComponent.getComponentType());
        if (effectController == null) {
            return false;
        }

        try {
            return effectController.addEffect(playerRef, healingEffect, store);
        } catch (Exception exception) {
            System.out.println("[WardenSpringOfRenewal] visual failed reason=" + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static void spawnGroundVisual(Player owner, Vector3d position) {
        if (owner == null || position == null) {
            return;
        }

        World world = owner.getWorld();
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

        try {
            ParticleUtil.spawnParticleEffect(SPRING_GROUND_VISUAL_SYSTEM_ID, new Vector3d(position), store);
            System.out.println("[WardenSpringOfRenewal] ground visual spawned system=" + SPRING_GROUND_VISUAL_SYSTEM_ID);
        } catch (Exception exception) {
            System.out.println("[WardenSpringOfRenewal] ground visual failed reason=" + exception.getClass().getSimpleName());
        }
    }

    private static void endSpring(UUID ownerId, String reason) {
        if (ownerId == null) {
            return;
        }

        ScheduledFuture<?> scheduled = ACTIVE_SPRINGS.remove(ownerId);
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        SPRING_POSITION_BY_OWNER.remove(ownerId);
        EXPIRES_AT_MILLIS_BY_OWNER.remove(ownerId);

        Player owner = DruidPermissions.getOnlinePlayer(ownerId);
        String ownerLabel = owner == null ? ownerId.toString() : DruidPlayerCompat.getPlayerNameOrUnknown(owner);
        System.out.println("[WardenSpringOfRenewal] ended owner=" + ownerLabel + " reason=" + reason);
    }

    private static void sendPlayerMessage(Player player, String text) {
        if (player == null || text == null) return;
        FormattedMessage component = new FormattedMessage();
        component.rawText = text;
        DruidPlayerCompat.sendMessage(player, new Message(component));
    }

    private static void showCooldownHud(Player player) {
        if (player == null) {
            return;
        }

        UUID ownerId = player.getUuid();
        Long cooldownEndMillis = ownerId == null ? null : COOLDOWN_END_BY_OWNER.get(ownerId);
        long remainingMillis = cooldownEndMillis == null ? 0L : cooldownEndMillis - System.currentTimeMillis();
        if (remainingMillis <= 0L) {
            DruidAbilityCooldownHotbarHud.remove(player);
            return;
        }
        DruidAbilityCooldownHotbarHud.showOrUpdate(player, 0, "Spring_Of_Renewal", remainingMillis, SPRING_COOLDOWN_MILLIS);
    }
}
