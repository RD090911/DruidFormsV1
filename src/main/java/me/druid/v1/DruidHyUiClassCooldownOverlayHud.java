package me.druid.v1;

import au.ellie.hyui.builders.GroupBuilder;
import au.ellie.hyui.builders.HudBuilder;
import au.ellie.hyui.builders.HyUIAnchor;
import au.ellie.hyui.builders.HyUIHud;
import au.ellie.hyui.builders.HyUIPatchStyle;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.ProgressBarBuilder;
import au.ellie.hyui.types.ProgressBarAlignment;
import au.ellie.hyui.types.ProgressBarDirection;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class DruidHyUiClassCooldownOverlayHud {
    private static final Map<UUID, HyUIHud> HUD_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> OVERLAY_START_TIME_BY_PLAYER = new ConcurrentHashMap<>();

    private static final int REFRESH_MILLIS = 500;
    private static final long OVERLAY_DURATION_MILLIS = 5_000L;

    // Matches DruidHyUiPersistentFormHud row geometry.
    private static final int ROW_BOTTOM = 154;
    private static final int ROW_WIDTH = 980;
    private static final int ROW_HEIGHT = 30;

    private static final int SLOT_WIDTH = 56;
    private static final int SLOT_HEIGHT = 30;
    private static final int ICON_LAYER_SIZE = 68;
    private static final int ICON_SIZE = 56;
    private static final int ICON_TOP_OFFSET = 0;

    private static final int SLOT_SEPARATOR_WIDTH = 24;

    private static final SlotOverlaySpec ANTELOPE_SLOT = new SlotOverlaySpec("Antelope", -1);
    private static final SlotOverlaySpec HAWK_SLOT = new SlotOverlaySpec("Hawk", -2);
    private static final SlotOverlaySpec BEAR_SLOT = new SlotOverlaySpec("Bear", -1);
    private static final SlotOverlaySpec TIGER_SLOT = new SlotOverlaySpec("Tiger", -1);
    private static final SlotOverlaySpec SHARK_SLOT = new SlotOverlaySpec("Shark", -1);
    private static final SlotOverlaySpec WARDEN_SLOT = new SlotOverlaySpec("Warden", -2);
    private static final SlotOverlaySpec RAM_SLOT = new SlotOverlaySpec("Ram", -2);
    private static final SlotOverlaySpec AQUATIC_SLOT = new SlotOverlaySpec("Aquatic", -1);
    private static final SlotOverlaySpec RABBIT_SLOT = new SlotOverlaySpec("Rabbit", -1);

    private DruidHyUiClassCooldownOverlayHud() {
    }

    public static boolean startOrRestart(Player player) {
        if (player == null || !DruidPermissions.shouldShowHud(player)) {
            return false;
        }
        runOnWorldThread(player, () -> showOrUpdateOnWorldThread(player, true));
        return true;
    }

    public static boolean showOrUpdate(Player player) {
        if (player == null || !DruidPermissions.shouldShowHud(player)) {
            return false;
        }
        runOnWorldThread(player, () -> showOrUpdateOnWorldThread(player, false));
        return true;
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
        HUD_BY_PLAYER.remove(playerUuid);
        OVERLAY_START_TIME_BY_PLAYER.remove(playerUuid);
    }

    private static void showOrUpdateOnWorldThread(Player player, boolean restartTiming) {
        if (player == null) {
            return;
        }

        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return;
        }

        if (!DruidPermissions.shouldShowHud(player)) {
            removeOnWorldThread(playerUuid);
            return;
        }

        if (restartTiming) {
            OVERLAY_START_TIME_BY_PLAYER.put(playerUuid, System.currentTimeMillis());
        }

        long remainingMillis = getOverlayRemainingMillis(playerUuid);
        if (remainingMillis <= 0L) {
            removeOnWorldThread(playerUuid);
            return;
        }

        PlayerRef playerRef = resolvePlayerRef(player);
        if (playerRef == null) {
            return;
        }

        HyUIHud existing = HUD_BY_PLAYER.get(playerUuid);
        try {
            if (existing == null) {
                HyUIHud shown = createHudBuilder(player, playerRef, remainingMillis).show();
                HUD_BY_PLAYER.put(playerUuid, shown);
                return;
            }
            updateHudSnapshot(player, playerRef, existing, remainingMillis);
        } catch (Exception e) {
            System.out.println("[DruidHyUI] Class cooldown overlay update failed: " + e.getMessage());
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
        if (activeHud == null || activeHud != hud) {
            return;
        }

        long remainingMillis = getOverlayRemainingMillis(playerUuid);
        if (remainingMillis <= 0L) {
            removeOnWorldThread(playerUuid);
            return;
        }

        updateHudSnapshot(player, playerRef, activeHud, remainingMillis);
    }

    private static void removeOnWorldThread(UUID playerUuid) {
        OVERLAY_START_TIME_BY_PLAYER.remove(playerUuid);
        HyUIHud hud = HUD_BY_PLAYER.remove(playerUuid);
        if (hud == null) {
            return;
        }
        try {
            hud.remove();
        } catch (Exception e) {
            System.out.println("[DruidHyUI] Class cooldown overlay remove failed: " + e.getMessage());
        }
    }

    private static HudBuilder createHudBuilder(Player player, PlayerRef playerRef, long remainingMillis) {
        float progress = cooldownProgress(remainingMillis);

        HyUIAnchor rootAnchor = new HyUIAnchor()
                .setLeft(0)
                .setRight(0)
                .setBottom(ROW_BOTTOM)
                .setHeight(ROW_HEIGHT);

        GroupBuilder row = GroupBuilder.group()
                .withRawId("druidClassCooldownOverlayRow")
                .withAnchor(new HyUIAnchor().setTop(0).setWidth(ROW_WIDTH).setHeight(ROW_HEIGHT))
                .withLayoutMode("Center")
                .addChild(createSlotOverlay(ANTELOPE_SLOT, progress))
                .addChild(createSeparator("druidClassCooldownOverlaySeparator1"))
                .addChild(createSlotOverlay(HAWK_SLOT, progress))
                .addChild(createSeparator("druidClassCooldownOverlaySeparator2"))
                .addChild(createSlotOverlay(BEAR_SLOT, progress))
                .addChild(createSeparator("druidClassCooldownOverlaySeparator3"))
                .addChild(createSlotOverlay(TIGER_SLOT, progress))
                .addChild(createSeparator("druidClassCooldownOverlaySeparator4"))
                .addChild(createSlotOverlay(SHARK_SLOT, progress))
                .addChild(createSeparator("druidClassCooldownOverlaySeparator5"))
                .addChild(createSlotOverlay(WARDEN_SLOT, progress))
                .addChild(createSeparator("druidClassCooldownOverlaySeparatorWarden"))
                .addChild(createSlotOverlay(RAM_SLOT, progress))
                .addChild(createSeparator("druidClassCooldownOverlaySeparator6"))
                .addChild(createSlotOverlay(AQUATIC_SLOT, progress))
                .addChild(createSeparator("druidClassCooldownOverlaySeparator7"))
                .addChild(createSlotOverlay(RABBIT_SLOT, progress));

        GroupBuilder root = GroupBuilder.group()
                .withRawId("druidClassCooldownOverlayRoot")
                .withAnchor(rootAnchor)
                .withLayoutMode("Center")
                .addChild(row);

        return HudBuilder.hudForPlayer(playerRef)
                .fromFile("Pages/EllieAU_HyUI_Placeholder.ui")
                .addElement(root)
                .withRefreshRate(REFRESH_MILLIS)
                .onRefresh(hud -> runOnWorldThread(player, () -> refreshOnWorldThread(player, playerRef, hud)));
    }

    private static GroupBuilder createSlotOverlay(SlotOverlaySpec spec, float progress) {
        return GroupBuilder.group()
                .withRawId("druidClassCooldown" + spec.name + "Slot")
                .withAnchor(new HyUIAnchor().setWidth(SLOT_WIDTH).setHeight(SLOT_HEIGHT))
                .withLayoutMode("Center")
                .addChild(
                        GroupBuilder.group()
                                .withRawId("druidClassCooldown" + spec.name + "OverlayLayer")
                                .withAnchor(new HyUIAnchor().setWidth(ICON_LAYER_SIZE).setHeight(ICON_LAYER_SIZE))
                                .withLayoutMode("Center")
                                .addChild(
                                        ProgressBarBuilder.progressBar()
                                                .withRawId("druidClassCooldown" + spec.name + "Progress")
                                                .withAnchor(new HyUIAnchor()
                                                        .setLeft(spec.iconLeftOffset)
                                                        .setTop(ICON_TOP_OFFSET)
                                                        .setWidth(ICON_SIZE)
                                                        .setHeight(ICON_SIZE))
                                                .withAlignment(ProgressBarAlignment.Vertical)
                                                .withDirection(ProgressBarDirection.End)
                                                .withValue(progress)
                                                .withBackground(new HyUIPatchStyle().setColor("#00000000"))
                                                .withBar(new HyUIPatchStyle().setColor("#7F1C3E2C"))
                                )
                );
    }

    private static LabelBuilder createSeparator(String id) {
        return LabelBuilder.label()
                .withRawId(id)
                .withAnchor(new HyUIAnchor().setWidth(SLOT_SEPARATOR_WIDTH).setHeight(ROW_HEIGHT))
                .withText("");
    }

    private static void updateHudSnapshot(Player player, PlayerRef playerRef, HyUIHud hud, long remainingMillis) {
        if (player == null || playerRef == null || hud == null) {
            return;
        }
        try {
            createHudBuilder(player, playerRef, remainingMillis).updateExisting(hud);
        } catch (Exception e) {
            System.out.println("[DruidHyUI] Class cooldown overlay snapshot update failed: " + e.getMessage());
        }
    }

    private static long getOverlayRemainingMillis(UUID playerUuid) {
        if (playerUuid == null) {
            return 0L;
        }
        Long overlayStartTime = OVERLAY_START_TIME_BY_PLAYER.get(playerUuid);
        if (overlayStartTime == null) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - overlayStartTime;
        return Math.max(0L, OVERLAY_DURATION_MILLIS - elapsed);
    }

    private static float cooldownProgress(long remainingMillis) {
        float progress = Math.max(0L, remainingMillis) / (float) OVERLAY_DURATION_MILLIS;
        if (progress < 0f) {
            return 0f;
        }
        if (progress > 1f) {
            return 1f;
        }
        return progress;
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

    private static PlayerRef resolvePlayerRef(Player player) {
        try {
            Method getPlayerRef = player.getClass().getMethod("getPlayerRef");
            Object value = getPlayerRef.invoke(player);
            if (value instanceof PlayerRef) {
                return (PlayerRef) value;
            }
        } catch (Exception ignored) {
        }

        try {
            Field playerRef = getFieldDeep(player.getClass(), "playerRef");
            if (playerRef != null) {
                playerRef.setAccessible(true);
                Object value = playerRef.get(player);
                if (value instanceof PlayerRef) {
                    return (PlayerRef) value;
                }
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

    private static final class SlotOverlaySpec {
        private final String name;
        private final int iconLeftOffset;

        private SlotOverlaySpec(String name, int iconLeftOffset) {
            this.name = name;
            this.iconLeftOffset = iconLeftOffset;
        }
    }
}
