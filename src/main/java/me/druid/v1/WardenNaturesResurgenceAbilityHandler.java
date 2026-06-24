package me.druid.v1;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.vector.Rotation3f;
import me.druid.v1.forms.FormId;
import org.joml.Vector3d;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class WardenNaturesResurgenceAbilityHandler {
    private static final Map<UUID, Long> COOLDOWN_END_BY_OWNER = new ConcurrentHashMap<>();
    private static final long NATURES_RESURGENCE_COOLDOWN_MILLIS = 30_000L;
    private static final double NATURES_RESURGENCE_RADIUS = 5.0d;
    private static final double NATURES_RESURGENCE_RADIUS_SQUARED = NATURES_RESURGENCE_RADIUS * NATURES_RESURGENCE_RADIUS;
    private static final double NATURES_RESURGENCE_INNER_VISUAL_OFFSET = 1.5d;
    private static final double NATURES_RESURGENCE_OUTER_VISUAL_OFFSET = 3.0d;
    private static final double NATURES_RESURGENCE_DIAGONAL_VISUAL_OFFSET = 2.15d;
    private static final double NATURES_RESURGENCE_OUTER_DIAGONAL_VISUAL_OFFSET = 3.0d;
    private static final float NATURES_RESURGENCE_HEAL_AMOUNT = 25.0f;
    private static final String NATURES_RESURGENCE_VISUAL_SYSTEM_ID = "HealthStick_Spin";
    private static final float NATURES_RESURGENCE_VISUAL_SCALE = 4.0f;

    private WardenNaturesResurgenceAbilityHandler() {
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

        Vector3d pulsePosition = getPlayerPosition(player);
        World world = player.getWorld();
        UUID ownerId = player.getUuid();
        if (pulsePosition == null || world == null || ownerId == null) {
            return;
        }

        spawnPulseVisual(player, pulsePosition);
        int targets = applyPulseHeal(world, pulsePosition);
        COOLDOWN_END_BY_OWNER.put(ownerId, System.currentTimeMillis() + NATURES_RESURGENCE_COOLDOWN_MILLIS);
        showCooldownHud(player);
        sendPlayerMessage(player, "Nature's Resurgence washes over nearby allies.");
        System.out.println(String.format(Locale.ROOT,
                "[WardenNaturesResurgence] cast owner=%s targets=%d heal=%.1f radius=%.1f",
                DruidPlayerCompat.getPlayerNameOrUnknown(player),
                targets,
                NATURES_RESURGENCE_HEAL_AMOUNT,
                NATURES_RESURGENCE_RADIUS));
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
        sendPlayerMessage(player, String.format(Locale.ROOT, "Nature's Resurgence recharging: %.1fs", remainingSeconds));
        showCooldownHud(player);
        System.out.println(String.format(Locale.ROOT,
                "[WardenNaturesResurgence] blocked cooldownRemainingMillis=%d cooldownRemainingSeconds=%.1f owner=%s",
                remainingMillis,
                remainingSeconds,
                DruidPlayerCompat.getPlayerNameOrUnknown(player)));
        return false;
    }

    private static int applyPulseHeal(World ownerWorld, Vector3d pulsePosition) {
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

            double dx = candidatePosition.x - pulsePosition.x;
            double dy = candidatePosition.y - pulsePosition.y;
            double dz = candidatePosition.z - pulsePosition.z;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared > NATURES_RESURGENCE_RADIUS_SQUARED) {
                continue;
            }

            boolean healed = applyHeal(candidate);
            if (healed) {
                targets++;
            }
            System.out.println(String.format(Locale.ROOT,
                    "[WardenNaturesResurgence] pulse target=%s healed=%s",
                    DruidPlayerCompat.getPlayerNameOrUnknown(candidate),
                    healed));
        }
        return targets;
    }

    private static boolean applyHeal(Player player) {
        try {
            EntityStatMap stats = EntityStatsModule.get(player);
            if (stats == null) {
                return false;
            }
            stats.addStatValue(DefaultEntityStatTypes.getHealth(), NATURES_RESURGENCE_HEAL_AMOUNT);
            return true;
        } catch (Exception exception) {
            System.out.println("[WardenNaturesResurgence] heal failed reason=" + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static void spawnPulseVisual(Player owner, Vector3d position) {
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

        Vector3d forward = resolveForwardVector(owner);
        Vector3d right = new Vector3d(forward.z, 0.0d, -forward.x);
        int spawned = 0;
        spawned += spawnVisualAt(new Vector3d(position), store) ? 1 : 0;
        spawned += spawnVisualAt(offset(position, forward, NATURES_RESURGENCE_INNER_VISUAL_OFFSET), store) ? 1 : 0;
        spawned += spawnVisualAt(offset(position, forward, -NATURES_RESURGENCE_INNER_VISUAL_OFFSET), store) ? 1 : 0;
        spawned += spawnVisualAt(offset(position, right, NATURES_RESURGENCE_INNER_VISUAL_OFFSET), store) ? 1 : 0;
        spawned += spawnVisualAt(offset(position, right, -NATURES_RESURGENCE_INNER_VISUAL_OFFSET), store) ? 1 : 0;
        spawned += spawnVisualAt(offset(position, forward, NATURES_RESURGENCE_OUTER_VISUAL_OFFSET), store) ? 1 : 0;
        spawned += spawnVisualAt(offset(position, forward, -NATURES_RESURGENCE_OUTER_VISUAL_OFFSET), store) ? 1 : 0;
        spawned += spawnVisualAt(offset(position, right, NATURES_RESURGENCE_OUTER_VISUAL_OFFSET), store) ? 1 : 0;
        spawned += spawnVisualAt(offset(position, right, -NATURES_RESURGENCE_OUTER_VISUAL_OFFSET), store) ? 1 : 0;
        spawned += spawnVisualAt(diagonalOffset(position, forward, right, NATURES_RESURGENCE_DIAGONAL_VISUAL_OFFSET), store) ? 1 : 0;
        spawned += spawnVisualAt(diagonalOffset(position, forward, right, -NATURES_RESURGENCE_DIAGONAL_VISUAL_OFFSET), store) ? 1 : 0;
        spawned += spawnVisualAt(diagonalOffset(position, forward, negate(right), NATURES_RESURGENCE_DIAGONAL_VISUAL_OFFSET), store) ? 1 : 0;
        spawned += spawnVisualAt(diagonalOffset(position, negate(forward), right, NATURES_RESURGENCE_DIAGONAL_VISUAL_OFFSET), store) ? 1 : 0;
        spawned += spawnVisualAt(diagonalOffset(position, forward, right, NATURES_RESURGENCE_OUTER_DIAGONAL_VISUAL_OFFSET), store) ? 1 : 0;
        spawned += spawnVisualAt(diagonalOffset(position, forward, right, -NATURES_RESURGENCE_OUTER_DIAGONAL_VISUAL_OFFSET), store) ? 1 : 0;
        spawned += spawnVisualAt(diagonalOffset(position, forward, negate(right), NATURES_RESURGENCE_OUTER_DIAGONAL_VISUAL_OFFSET), store) ? 1 : 0;
        spawned += spawnVisualAt(diagonalOffset(position, negate(forward), right, NATURES_RESURGENCE_OUTER_DIAGONAL_VISUAL_OFFSET), store) ? 1 : 0;

        System.out.println("[WardenNaturesResurgence] visuals spawned system="
                + NATURES_RESURGENCE_VISUAL_SYSTEM_ID
                + " scale=" + NATURES_RESURGENCE_VISUAL_SCALE
                + " count=" + spawned);
    }

    private static boolean spawnVisualAt(Vector3d position, Store<EntityStore> store) {
        if (position == null || store == null) {
            return false;
        }

        try {
            ParticleUtil.spawnParticleEffect(
                    NATURES_RESURGENCE_VISUAL_SYSTEM_ID,
                    position,
                    0.0f,
                    0.0f,
                    0.0f,
                    NATURES_RESURGENCE_VISUAL_SCALE,
                    0.0f,
                    store);
            return true;
        } catch (Exception exception) {
            System.out.println("[WardenNaturesResurgence] visual failed reason=" + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static Vector3d resolveForwardVector(Player owner) {
        try {
            TransformComponent transform = DruidPlayerCompat.getTransformComponent(owner);
            Rotation3f rotation = transform == null ? null : transform.getRotation();
            if (rotation != null) {
                double yaw = rotation.yaw();
                return new Vector3d(-Math.sin(yaw), 0.0d, -Math.cos(yaw));
            }
        } catch (Exception ignored) {
        }
        return new Vector3d(0.0d, 0.0d, -1.0d);
    }

    private static Vector3d offset(Vector3d origin, Vector3d direction, double distance) {
        return new Vector3d(
                origin.x + direction.x * distance,
                origin.y,
                origin.z + direction.z * distance);
    }

    private static Vector3d diagonalOffset(Vector3d origin, Vector3d firstDirection, Vector3d secondDirection, double distance) {
        Vector3d direction = new Vector3d(
                firstDirection.x + secondDirection.x,
                0.0d,
                firstDirection.z + secondDirection.z);
        if (direction.lengthSquared() > 0.0d) {
            direction.normalize();
        }
        return offset(origin, direction, distance);
    }

    private static Vector3d negate(Vector3d direction) {
        return new Vector3d(-direction.x, -direction.y, -direction.z);
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
        DruidAbilityCooldownHotbarHud.showOrUpdate(player, 0, "Natures_Resurgence", remainingMillis, NATURES_RESURGENCE_COOLDOWN_MILLIS);
    }
}
