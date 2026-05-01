package me.druid.v1;

import au.ellie.hyui.builders.Alignment;
import au.ellie.hyui.builders.GroupBuilder;
import au.ellie.hyui.builders.HudBuilder;
import au.ellie.hyui.builders.HyUIAnchor;
import au.ellie.hyui.builders.HyUIHud;
import au.ellie.hyui.builders.HyUIPatchStyle;
import au.ellie.hyui.builders.HyUIStyle;
import au.ellie.hyui.builders.ImageBuilder;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.ProgressBarBuilder;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import au.ellie.hyui.types.ProgressBarAlignment;
import au.ellie.hyui.types.ProgressBarDirection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class WardenLifeSeedCooldownHud {
    private static final Map<UUID, HyUIHud> HUD_BY_PLAYER = new ConcurrentHashMap<>();
    private static final String ROOT_ID = "wardenLifeSeedCooldownRoot";
    private static final String LABEL_ID = "wardenLifeSeedCooldownLabel";
    private static final String ICON_ID = "wardenLifeSeedCooldownIcon";
    private static final String PROGRESS_ID = "wardenLifeSeedCooldownProgress";
    private static final int REFRESH_MILLIS = 500;
    private static final int ROOT_LEFT = 24;
    private static final int ROOT_BOTTOM = 24;
    private static final int ROOT_WIDTH = 84;
    private static final int ROOT_HEIGHT = 118;
    private static final int TEXT_HEIGHT = 32;
    private static final int ICON_TOP = 38;
    private static final int ICON_SIZE = 56;
    private static final int ICON_BOX_SIZE = 64;
    private static final int ICON_BOX_TOP = 34;
    private static final float LIFE_SEED_COOLDOWN_TOTAL_MILLIS = 20_000f;
    private static final String LIFE_SEED_ICON_TEXTURE_PATH = "forms/life_seed.png";

    private WardenLifeSeedCooldownHud() {
    }

    public static void showOrUpdate(Player player) {
        if (player == null) {
            return;
        }
        runOnWorldThread(player, () -> showOrUpdateOnWorldThread(player));
    }

    public static void show(Player player) {
        showOrUpdate(player);
    }

    public static void update(Player player) {
        if (player == null) {
            return;
        }
        runOnWorldThread(player, () -> updateOnWorldThread(player));
    }

    public static void remove(Player player) {
        if (player == null) {
            return;
        }
        runOnWorldThread(player, () -> {
            UUID playerUuid = player.getUuid();
            if (playerUuid != null) {
                removeOnWorldThread(playerUuid);
            }
        });
    }

    public static void remove(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }

        Player onlinePlayer = DruidPermissions.getOnlinePlayer(playerUuid);
        if (onlinePlayer != null && onlinePlayer.getWorld() != null) {
            runOnWorldThread(onlinePlayer, () -> removeOnWorldThread(playerUuid));
            return;
        }

        // Keep UI operations on world thread only; if unavailable, just clear stale bookkeeping.
        HUD_BY_PLAYER.remove(playerUuid);
    }

    private static void showOrUpdateOnWorldThread(Player player) {
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return;
        }

        long remainingMillis = WardenLifeSeedAbilityHandler.getLifeSeedCooldownRemainingMillis(player);
        if (remainingMillis <= 0L) {
            removeOnWorldThread(playerUuid);
            return;
        }

        HyUIHud existingHud = HUD_BY_PLAYER.get(playerUuid);
        if (existingHud != null) {
            PlayerRef playerRef = resolvePlayerRef(player);
            if (playerRef != null) {
                updateHudSnapshot(player, playerRef, existingHud, remainingMillis);
            } else {
                updateLabel(existingHud, remainingMillis);
            }
            return;
        }

        PlayerRef playerRef = resolvePlayerRef(player);
        if (playerRef == null) {
            return;
        }

        try {
            HyUIHud hud = createHudBuilder(player, playerRef, remainingMillis).show();
            HUD_BY_PLAYER.put(playerUuid, hud);
            System.out.println("[DruidHyUI] Life Seed cooldown HUD shown for " + playerUuid);
        } catch (Exception e) {
            System.out.println("[DruidHyUI] Life Seed cooldown HUD show failed: " + e.getMessage());
        }
    }

    private static void updateOnWorldThread(Player player) {
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return;
        }

        HyUIHud hud = HUD_BY_PLAYER.get(playerUuid);
        if (hud == null) {
            showOrUpdateOnWorldThread(player);
            return;
        }

        long remainingMillis = WardenLifeSeedAbilityHandler.getLifeSeedCooldownRemainingMillis(player);
        if (remainingMillis <= 0L) {
            removeOnWorldThread(playerUuid);
            return;
        }

        PlayerRef playerRef = resolvePlayerRef(player);
        if (playerRef != null) {
            updateHudSnapshot(player, playerRef, hud, remainingMillis);
        } else {
            updateLabel(hud, remainingMillis);
        }
    }

    private static void refreshOnWorldThread(Player player, PlayerRef playerRef, HyUIHud hud) {
        if (player == null || playerRef == null || hud == null) {
            return;
        }

        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }

        HyUIHud activeHud = HUD_BY_PLAYER.get(playerUuid);
        if (activeHud == null) {
            return;
        }

        long remainingMillis = WardenLifeSeedAbilityHandler.getLifeSeedCooldownRemainingMillis(player);
        if (remainingMillis <= 0L) {
            removeOnWorldThread(playerUuid);
            return;
        }

        String label = formatCooldownLabel(remainingMillis);
        System.out.println("[DruidHyUI] LifeSeed cooldown HUD refresh remainingMillis=" + remainingMillis + " label=" + label);
        updateHudSnapshot(player, playerRef, activeHud, remainingMillis);
    }

    private static void removeOnWorldThread(UUID playerUuid) {
        HyUIHud hud = HUD_BY_PLAYER.remove(playerUuid);
        if (hud == null) {
            return;
        }

        try {
            hud.remove();
            System.out.println("[DruidHyUI] Life Seed cooldown HUD removed for " + playerUuid);
        } catch (Exception e) {
            System.out.println("[DruidHyUI] Life Seed cooldown HUD remove failed: " + e.getMessage());
        }
    }

    private static HudBuilder createHudBuilder(Player player, PlayerRef playerRef, long remainingMillis) {
        HyUIAnchor rootAnchor = new HyUIAnchor()
                .setLeft(ROOT_LEFT)
                .setBottom(ROOT_BOTTOM)
                .setWidth(ROOT_WIDTH)
                .setHeight(ROOT_HEIGHT);

        HyUIStyle labelStyle = new HyUIStyle()
                .setFontSize(20f)
                .setRenderBold(true)
                .setTextColor("#FFFFFFFF")
                .setHorizontalAlignment(Alignment.Center);

        GroupBuilder root = GroupBuilder.group()
                .withRawId(ROOT_ID)
                .withAnchor(rootAnchor)
                .addChild(
                        LabelBuilder.label()
                                .withRawId(LABEL_ID)
                                .withAnchor(new HyUIAnchor().setTop(0).setWidth(ROOT_WIDTH).setHeight(TEXT_HEIGHT))
                                .withStyle(labelStyle)
                                .withText(formatCooldownLabel(remainingMillis))
                )
                .addChild(
                        GroupBuilder.group()
                                .withRawId("wardenLifeSeedCooldownIconBackdrop")
                                .withAnchor(new HyUIAnchor()
                                        .setLeft((ROOT_WIDTH - ICON_BOX_SIZE) / 2)
                                        .setTop(ICON_BOX_TOP)
                                        .setWidth(ICON_BOX_SIZE)
                                        .setHeight(ICON_BOX_SIZE))
                                .withBackground(new HyUIPatchStyle().setColor("#1E1E1E"))
                                .withOutlineColor("#FF67C58A")
                                .withOutlineSize(2.0f)
                )
                .addChild(
                        ProgressBarBuilder.progressBar()
                                .withRawId(PROGRESS_ID)
                                .withAnchor(new HyUIAnchor()
                                        .setLeft((ROOT_WIDTH - ICON_BOX_SIZE) / 2 + 2)
                                        .setTop(ICON_BOX_TOP + 2)
                                        .setWidth(ICON_BOX_SIZE - 4)
                                        .setHeight(ICON_BOX_SIZE - 4))
                                .withAlignment(ProgressBarAlignment.Vertical)
                                .withDirection(ProgressBarDirection.End)
                                .withValue(cooldownProgress(remainingMillis))
                                .withBackground(new HyUIPatchStyle().setColor("#00000000"))
                                .withBar(new HyUIPatchStyle().setColor("#7F1C3E2C"))
                )
                .addChild(
                        ImageBuilder.image()
                                .withRawId(ICON_ID)
                                .withAnchor(new HyUIAnchor().setLeft((ROOT_WIDTH - ICON_SIZE) / 2).setTop(ICON_TOP).setWidth(ICON_SIZE).setHeight(ICON_SIZE))
                                .withImage(LIFE_SEED_ICON_TEXTURE_PATH)
                );

        return HudBuilder.hudForPlayer(playerRef)
                .fromFile("Pages/EllieAU_HyUI_Placeholder.ui")
                .addElement(root)
                .withRefreshRate(REFRESH_MILLIS)
                .onRefresh(hud -> runOnWorldThread(player, () -> refreshOnWorldThread(player, playerRef, hud)));
    }

    private static void updateLabel(HyUIHud hud, long remainingMillis) {
        if (hud == null) {
            return;
        }
        try {
            hud.editById(LABEL_ID, LabelBuilder.class, label -> label.withText(formatCooldownLabel(remainingMillis)));
            hud.updatePage(false);
        } catch (Exception e) {
            System.out.println("[DruidHyUI] Life Seed cooldown HUD label update failed: " + e.getMessage());
        }
    }

    private static void updateHudSnapshot(Player player, PlayerRef playerRef, HyUIHud hud, long remainingMillis) {
        if (player == null || playerRef == null || hud == null) {
            return;
        }
        try {
            createHudBuilder(player, playerRef, remainingMillis).updateExisting(hud);
        } catch (Exception e) {
            updateLabel(hud, remainingMillis);
        }
    }

    private static void runOnWorldThread(Player player, Runnable action) {
        if (player == null || action == null) {
            return;
        }

        World world = player.getWorld();
        if (world == null) {
            return;
        }

        world.execute(action);
    }

    private static String formatCooldownLabel(long remainingMillis) {
        long remainingSeconds = Math.max(0L, (remainingMillis + 999L) / 1000L);
        return String.format(Locale.ROOT, "%ds", remainingSeconds);
    }

    private static float cooldownProgress(long remainingMillis) {
        float progress = Math.max(0L, remainingMillis) / LIFE_SEED_COOLDOWN_TOTAL_MILLIS;
        if (progress < 0f) {
            return 0f;
        }
        if (progress > 1f) {
            return 1f;
        }
        return progress;
    }

    private static PlayerRef resolvePlayerRef(Player player) {
        try {
            Method getPlayerRef = player.getClass().getMethod("getPlayerRef");
            Object value = getPlayerRef.invoke(player);
            if (value instanceof PlayerRef) return (PlayerRef) value;
        } catch (Exception ignored) {
        }

        try {
            Field playerRef = getFieldDeep(player.getClass(), "playerRef");
            if (playerRef != null) {
                playerRef.setAccessible(true);
                Object value = playerRef.get(player);
                if (value instanceof PlayerRef) return (PlayerRef) value;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Field getFieldDeep(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }
}
