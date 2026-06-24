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
import com.hypixel.hytale.server.core.HytaleServer;
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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class DruidBuffDebuffHud {
    private static final Map<UUID, HyUIHud> HUD_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, TimedBuffState> BUFF_STATE_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, ScheduledFuture<?>> REFRESH_TASK_BY_PLAYER = new ConcurrentHashMap<>();
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
    private static final String LIFE_SEED_ICON_TEXTURE_PATH = "forms/life_seed.png";
    private static final String OAKENSHIELD_ICON_TEXTURE_PATH = "forms/oakenshield.png";
    private static final String OAKENSHIELD_LABEL = "Oakenshield";

    private DruidBuffDebuffHud() {
    }

    public static void showLifeSeed(Player player) {
        showTimedBuff(player, "Life Seed", LIFE_SEED_ICON_TEXTURE_PATH, 10_000L);
    }

    public static void showOakenshield(Player player) {
        showTimedBuff(player, OAKENSHIELD_LABEL, OAKENSHIELD_ICON_TEXTURE_PATH, GuardianOakenshieldCooldownService.OAKENSHIELD_ACTIVE_MILLIS);
    }

    private static void showTimedBuff(Player player, String label, String iconTexturePath, long durationMillis) {
        if (player == null || durationMillis <= 0L) {
            return;
        }
        runOnWorldThread(player, () -> {
            UUID playerUuid = player.getUuid();
            if (playerUuid == null) {
                return;
            }
            BUFF_STATE_BY_PLAYER.put(playerUuid, new TimedBuffState(
                    label,
                    iconTexturePath,
                    System.currentTimeMillis() + durationMillis,
                    durationMillis
            ));
            showOrUpdateOnWorldThread(player);
            PlayerRef playerRef = resolvePlayerRef(player);
            if (playerRef != null) {
                ensureRefreshTask(player, playerRef, playerUuid);
            }
        });
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
        BUFF_STATE_BY_PLAYER.remove(playerUuid);
    }

    static void removeAllCached() {
        HUD_BY_PLAYER.clear();
        BUFF_STATE_BY_PLAYER.clear();
        for (ScheduledFuture<?> task : REFRESH_TASK_BY_PLAYER.values()) {
            if (task != null) {
                task.cancel(false);
            }
        }
        REFRESH_TASK_BY_PLAYER.clear();
    }

    private static void showOrUpdateOnWorldThread(Player player) {
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return;
        }

        TimedBuffState state = currentState(playerUuid);
        long remainingMillis = state == null ? 0L : state.remainingMillis();
        if (remainingMillis <= 0L) {
            removeOnWorldThread(playerUuid);
            return;
        }

        HyUIHud existingHud = HUD_BY_PLAYER.get(playerUuid);
        PlayerRef playerRef = resolvePlayerRef(player);
        if (playerRef == null) {
            return;
        }

        try {
            replaceHud(existingHud, player, playerRef, state, remainingMillis);
            ensureRefreshTask(player, playerRef, playerUuid);
        } catch (Exception e) {
            System.out.println("[DruidHyUI] timed buff HUD show failed: " + e.getMessage());
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

        TimedBuffState state = currentState(playerUuid);
        long remainingMillis = state == null ? 0L : state.remainingMillis();
        if (remainingMillis <= 0L) {
            removeOnWorldThread(playerUuid);
            return;
        }

        PlayerRef playerRef = resolvePlayerRef(player);
        if (playerRef == null) {
            return;
        }
        replaceHud(hud, player, playerRef, state, remainingMillis);
        ensureRefreshTask(player, playerRef, playerUuid);
    }

    private static void refreshOnWorldThread(Player player, PlayerRef playerRef) {
        if (player == null || playerRef == null) {
            return;
        }

        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }

        TimedBuffState state = currentState(playerUuid);
        long remainingMillis = state == null ? 0L : state.remainingMillis();
        if (remainingMillis <= 0L) {
            removeOnWorldThread(playerUuid);
            return;
        }

        HyUIHud activeHud = HUD_BY_PLAYER.get(playerUuid);
        replaceHud(activeHud, player, playerRef, state, remainingMillis);
    }

    private static void removeOnWorldThread(UUID playerUuid) {
        BUFF_STATE_BY_PLAYER.remove(playerUuid);
        cancelRefreshTask(playerUuid);
        HyUIHud hud = HUD_BY_PLAYER.remove(playerUuid);
        if (hud == null) {
            return;
        }

        try {
            hud.remove();
        } catch (Exception e) {
            System.out.println("[DruidHyUI] timed buff HUD remove failed: " + e.getMessage());
        }
    }

    private static HudBuilder createHudBuilder(Player player, PlayerRef playerRef, TimedBuffState state, long remainingMillis) {
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
                                .withValue(cooldownProgress(remainingMillis, state.totalMillis()))
                                .withBackground(new HyUIPatchStyle().setColor("#00000000"))
                                .withBar(new HyUIPatchStyle().setColor("#7F1C3E2C"))
                )
                .addChild(
                        ImageBuilder.image()
                                .withRawId(ICON_ID)
                                .withAnchor(new HyUIAnchor().setLeft((ROOT_WIDTH - ICON_SIZE) / 2).setTop(ICON_TOP).setWidth(ICON_SIZE).setHeight(ICON_SIZE))
                                .withImage(state.iconTexturePath())
                );

        return HudBuilder.hudForPlayer(playerRef)
                .fromFile("Pages/EllieAU_HyUI_Placeholder.ui")
                .addElement(root)
                .withRefreshRate(REFRESH_MILLIS);
    }

    private static void replaceHud(HyUIHud existingHud, Player player, PlayerRef playerRef, TimedBuffState state, long remainingMillis) {
        if (player == null || playerRef == null || state == null) {
            return;
        }
        if (existingHud != null) {
            try {
                existingHud.remove();
            } catch (Exception e) {
                System.out.println("[DruidHyUI] timed buff HUD replace remove failed: " + e.getMessage());
            }
        }
        HyUIHud shown = createHudBuilder(player, playerRef, state, remainingMillis).show();
        HUD_BY_PLAYER.put(playerRef.getUuid(), shown);
    }

    private static void ensureRefreshTask(Player player, PlayerRef playerRef, UUID playerUuid) {
        if (player == null || playerRef == null || playerUuid == null) {
            return;
        }

        ScheduledFuture<?> existingTask = REFRESH_TASK_BY_PLAYER.get(playerUuid);
        if (existingTask != null && !existingTask.isCancelled() && !existingTask.isDone()) {
            return;
        }

        ScheduledFuture<?> task = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() ->
                        runOnWorldThread(player, () -> refreshOnWorldThread(player, playerRef)),
                1L,
                1L,
                TimeUnit.SECONDS);

        ScheduledFuture<?> previous = REFRESH_TASK_BY_PLAYER.put(playerUuid, task);
        if (previous != null && previous != task) {
            previous.cancel(false);
        }
    }

    private static void cancelRefreshTask(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        ScheduledFuture<?> task = REFRESH_TASK_BY_PLAYER.remove(playerUuid);
        if (task != null) {
            task.cancel(false);
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

    private static float cooldownProgress(long remainingMillis, long totalMillis) {
        float progress = totalMillis <= 0L ? 0f : Math.max(0L, remainingMillis) / (float) totalMillis;
        if (progress < 0f) {
            return 0f;
        }
        if (progress > 1f) {
            return 1f;
        }
        return progress;
    }

    private static TimedBuffState currentState(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        TimedBuffState state = BUFF_STATE_BY_PLAYER.get(playerUuid);
        if (state == null) {
            return null;
        }
        if (state.remainingMillis() <= 0L) {
            BUFF_STATE_BY_PLAYER.remove(playerUuid, state);
            return null;
        }
        return state;
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

    private record TimedBuffState(String label, String iconTexturePath, long expiresAtMillis, long totalMillis) {
        private long remainingMillis() {
            return Math.max(0L, expiresAtMillis - System.currentTimeMillis());
        }
    }
}
