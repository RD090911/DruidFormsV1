package me.druid.v1;

import au.ellie.hyui.builders.Alignment;
import au.ellie.hyui.builders.GroupBuilder;
import au.ellie.hyui.builders.HudBuilder;
import au.ellie.hyui.builders.HyUIAnchor;
import au.ellie.hyui.builders.HyUIHud;
import au.ellie.hyui.builders.HyUIPatchStyle;
import au.ellie.hyui.builders.HyUIStyle;
import au.ellie.hyui.builders.LabelBuilder;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class DruidAbilityCooldownHotbarHud {
    private static final Map<UUID, Map<String, HyUIHud>> HUD_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, CooldownState>> STATE_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, Integer>> DISPLAY_SLOT_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, ScheduledFuture<?>> REFRESH_TASK_BY_PLAYER = new ConcurrentHashMap<>();

    private static final int REFRESH_MILLIS = 500;
    private static final int ROOT_BOTTOM = 24;
    private static final int ROOT_HEIGHT = 28;
    private static final int HOTBAR_ROW_WIDTH = 504;
    private static final int HOTBAR_SLOT_COUNT = 9;
    private static final int HOTBAR_SLOT_WIDTH = 56;
    private static final int HOTBAR_SLOT_PITCH = 78;
    private static final int LABEL_WIDTH = 52;
    private static final int LABEL_HEIGHT = 26;
    private static final int LABEL_SLOT_X_OFFSET = (HOTBAR_SLOT_WIDTH - LABEL_WIDTH) / 2;
    private static final int LABEL_VISUAL_X_NUDGE = -86;
    private static final int LABEL_ROW_Y_OFFSET = 30;
    private static final String LABEL_BACKGROUND_COLOR = "#A0000000";
    private static final String LABEL_OUTLINE_COLOR = "#CC67C58A";

    private DruidAbilityCooldownHotbarHud() {
    }

    static void showOrUpdate(Player player, int slotIndex, String itemId, long remainingMillis, long totalMillis) {
        if (player == null || remainingMillis <= 0L || totalMillis <= 0L) {
            remove(player, itemId);
            return;
        }
        runOnWorldThread(player, () -> showOrUpdateOnWorldThread(player, slotIndex, itemId, remainingMillis, totalMillis));
    }

    static void remove(Player player) {
        if (player == null) {
            return;
        }
        runOnWorldThread(player, () -> {
            UUID playerUuid = player.getUuid();
            if (playerUuid != null) {
                removeExpiredOnWorldThread(playerUuid);
            }
        });
    }

    static void remove(Player player, String itemId) {
        if (player == null) {
            return;
        }
        if (itemId == null || itemId.isBlank()) {
            remove(player);
            return;
        }
        runOnWorldThread(player, () -> {
            UUID playerUuid = player.getUuid();
            if (playerUuid != null) {
                removeCooldownOnWorldThread(playerUuid, cooldownKey(itemId));
            }
        });
    }

    private static void showOrUpdateOnWorldThread(
            Player player,
            int slotIndex,
            String itemId,
            long remainingMillis,
            long totalMillis
    ) {
        UUID playerUuid = player.getUuid();
        PlayerRef playerRef = DruidPlayerCompat.getPlayerRef(player);
        if (playerUuid == null || playerRef == null) {
            return;
        }
        String abilityKey = cooldownKey(itemId);

        if (!DruidPermissions.shouldShowHud(player)) {
            removeAllOnWorldThread(playerUuid);
            return;
        }

        long cooldownEndMillis = System.currentTimeMillis() + remainingMillis;
        int resolvedSlotIndex = resolveInitialSlotIndex(player, slotIndex, itemId);
        CooldownState state = new CooldownState(resolvedSlotIndex, itemId, cooldownEndMillis, totalMillis);
        stateMapFor(playerUuid).put(abilityKey, state);

        Map<String, HyUIHud> playerHuds = hudMapFor(playerUuid);
        HyUIHud existingHud = playerHuds.get(abilityKey);
        try {
            int displaySlot = resolveDisplaySlotIndex(player, state);
            if (existingHud == null) {
                HyUIHud shown = createHudBuilder(player, playerRef, abilityKey, state, displaySlot).show();
                playerHuds.put(abilityKey, shown);
                displaySlotMapFor(playerUuid).put(abilityKey, displaySlot);
                ensureRefreshTask(player, playerRef, playerUuid);
                return;
            }
            if (hasDisplaySlotChanged(playerUuid, abilityKey, displaySlot)) {
                replaceHud(existingHud, player, playerRef, abilityKey, state, displaySlot);
                ensureRefreshTask(player, playerRef, playerUuid);
                return;
            }
            replaceHud(existingHud, player, playerRef, abilityKey, state, displaySlot);
            ensureRefreshTask(player, playerRef, playerUuid);
        } catch (Exception exception) {
            System.out.println("[DruidAbilityCooldownHud] update failed reason=" + exception.getClass().getSimpleName());
        }
    }

    private static void refreshOnWorldThread(Player player, PlayerRef playerRef, String abilityKey) {
        if (player == null || playerRef == null || abilityKey == null || abilityKey.isBlank()) {
            return;
        }

        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }
        Map<String, CooldownState> playerStates = STATE_BY_PLAYER.get(playerUuid);
        CooldownState state = playerStates == null ? null : playerStates.get(abilityKey);
        if (state == null || state.remainingMillis() <= 0L) {
            removeCooldownOnWorldThread(playerUuid, abilityKey);
            return;
        }

        try {
            int itemSlot = findHotbarSlotForItem(player, state.itemId);
            if (itemSlot < 0) {
                hideCooldownHudOnWorldThread(playerUuid, abilityKey);
                return;
            }
            Map<String, HyUIHud> playerHuds = HUD_BY_PLAYER.get(playerUuid);
            HyUIHud hud = playerHuds == null ? null : playerHuds.get(abilityKey);
            replaceHud(hud, player, playerRef, abilityKey, state, itemSlot);
        } catch (Exception exception) {
            System.out.println("[DruidAbilityCooldownHud] refresh failed reason=" + exception.getClass().getSimpleName());
        }
    }

    private static void refreshAllOnWorldThread(Player player, PlayerRef playerRef) {
        if (player == null || playerRef == null) {
            return;
        }
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }
        Map<String, CooldownState> playerStates = STATE_BY_PLAYER.get(playerUuid);
        if (playerStates == null || playerStates.isEmpty()) {
            cancelRefreshTask(playerUuid);
            return;
        }
        for (String abilityKey : Map.copyOf(playerStates).keySet()) {
            refreshOnWorldThread(player, playerRef, abilityKey);
        }
        if (isPlayerCooldownEmpty(playerUuid)) {
            cancelRefreshTask(playerUuid);
        }
    }

    private static void removeAllOnWorldThread(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }

        Map<String, HyUIHud> playerHuds = HUD_BY_PLAYER.remove(playerUuid);
        STATE_BY_PLAYER.remove(playerUuid);
        DISPLAY_SLOT_BY_PLAYER.remove(playerUuid);
        cancelRefreshTask(playerUuid);
        if (playerHuds == null) {
            return;
        }

        for (HyUIHud hud : playerHuds.values()) {
            removeHud(hud);
        }
    }

    private static void removeExpiredOnWorldThread(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }

        Map<String, CooldownState> playerStates = STATE_BY_PLAYER.get(playerUuid);
        if (playerStates == null || playerStates.isEmpty()) {
            cleanupPlayerCooldownMaps(playerUuid);
            return;
        }

        for (Map.Entry<String, CooldownState> entry : Map.copyOf(playerStates).entrySet()) {
            CooldownState state = entry.getValue();
            if (state == null || state.remainingMillis() <= 0L) {
                removeCooldownOnWorldThread(playerUuid, entry.getKey());
            }
        }
    }

    private static void removeCooldownOnWorldThread(UUID playerUuid, String abilityKey) {
        if (playerUuid == null || abilityKey == null || abilityKey.isBlank()) {
            return;
        }

        Map<String, CooldownState> playerStates = STATE_BY_PLAYER.get(playerUuid);
        if (playerStates != null) {
            playerStates.remove(abilityKey);
        }
        Map<String, Integer> playerSlots = DISPLAY_SLOT_BY_PLAYER.get(playerUuid);
        if (playerSlots != null) {
            playerSlots.remove(abilityKey);
        }
        Map<String, HyUIHud> playerHuds = HUD_BY_PLAYER.get(playerUuid);
        HyUIHud hud = playerHuds == null ? null : playerHuds.remove(abilityKey);
        removeHud(hud);
        cleanupPlayerCooldownMaps(playerUuid);
    }

    private static void hideCooldownHudOnWorldThread(UUID playerUuid, String abilityKey) {
        if (playerUuid == null || abilityKey == null || abilityKey.isBlank()) {
            return;
        }

        Map<String, Integer> playerSlots = DISPLAY_SLOT_BY_PLAYER.get(playerUuid);
        if (playerSlots != null) {
            playerSlots.remove(abilityKey);
        }
        Map<String, HyUIHud> playerHuds = HUD_BY_PLAYER.get(playerUuid);
        HyUIHud hud = playerHuds == null ? null : playerHuds.get(abilityKey);
        removeHud(hud);
    }

    private static HudBuilder createHudBuilder(Player player, PlayerRef playerRef, String abilityKey, CooldownState state, int activeSlot) {
        String rawIdSuffix = rawIdSuffix(abilityKey);
        HyUIAnchor rootAnchor = new HyUIAnchor()
                .setLeft(0)
                .setRight(0)
                .setBottom(ROOT_BOTTOM)
                .setHeight(ROOT_HEIGHT);

        GroupBuilder row = GroupBuilder.group()
                .withRawId("druidAbilityCooldownHotbarRow" + rawIdSuffix)
                .withAnchor(new HyUIAnchor().setTop(0).setWidth(HOTBAR_ROW_WIDTH).setHeight(ROOT_HEIGHT));

        row.addChild(createCooldownLabelBackdrop(state, activeSlot, rawIdSuffix));

        GroupBuilder root = GroupBuilder.group()
                .withRawId("druidAbilityCooldownHotbarRoot" + rawIdSuffix)
                .withAnchor(rootAnchor)
                .withLayoutMode("Center")
                .addChild(row);

        return HudBuilder.hudForPlayer(playerRef)
                .fromFile("Pages/EllieAU_HyUI_Placeholder.ui")
                .addElement(root)
                .withRefreshRate(REFRESH_MILLIS)
                .onRefresh(hud -> runOnWorldThread(player, () -> refreshOnWorldThread(player, playerRef, abilityKey)));
    }

    private static GroupBuilder createCooldownLabelBackdrop(CooldownState state, int activeSlot, String rawIdSuffix) {
        int labelLeft = clampSlotIndex(activeSlot) * HOTBAR_SLOT_PITCH + LABEL_SLOT_X_OFFSET + LABEL_VISUAL_X_NUDGE;
        return GroupBuilder.group()
                .withRawId("druidAbilityCooldownHotbarLabelBackdrop" + rawIdSuffix)
                .withAnchor(new HyUIAnchor()
                        .setLeft(labelLeft)
                        .setTop(LABEL_ROW_Y_OFFSET)
                        .setWidth(LABEL_WIDTH)
                        .setHeight(LABEL_HEIGHT))
                .withBackground(new HyUIPatchStyle().setColor(LABEL_BACKGROUND_COLOR))
                .withOutlineColor(LABEL_OUTLINE_COLOR)
                .withOutlineSize(1.0f)
                .addChild(createCooldownLabel(state, rawIdSuffix));
    }

    private static boolean hasDisplaySlotChanged(UUID playerUuid, String abilityKey, int displaySlot) {
        Map<String, Integer> playerSlots = DISPLAY_SLOT_BY_PLAYER.get(playerUuid);
        Integer previousSlot = playerSlots == null ? null : playerSlots.get(abilityKey);
        return previousSlot != null && previousSlot != displaySlot;
    }

    private static void replaceHud(HyUIHud existingHud, Player player, PlayerRef playerRef, String abilityKey, CooldownState state, int displaySlot) {
        if (existingHud != null) {
            existingHud.remove();
        }
        HyUIHud shown = createHudBuilder(player, playerRef, abilityKey, state, displaySlot).show();
        UUID playerUuid = playerRef.getUuid();
        hudMapFor(playerUuid).put(abilityKey, shown);
        displaySlotMapFor(playerUuid).put(abilityKey, displaySlot);
    }

    private static void ensureRefreshTask(Player player, PlayerRef playerRef, UUID playerUuid) {
        if (player == null || playerRef == null || playerUuid == null) {
            return;
        }

        ScheduledFuture<?> existingTask = REFRESH_TASK_BY_PLAYER.get(playerUuid);
        if (existingTask != null && !existingTask.isCancelled() && !existingTask.isDone()) {
            return;
        }

        ScheduledFuture<?> task = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
            Map<String, CooldownState> playerStates = STATE_BY_PLAYER.get(playerUuid);
            if (playerStates == null || playerStates.isEmpty()) {
                cancelRefreshTask(playerUuid);
                return;
            }
            runOnWorldThread(player, () -> refreshAllOnWorldThread(player, playerRef));
        }, 1L, 1L, TimeUnit.SECONDS);

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

    private static Map<String, HyUIHud> hudMapFor(UUID playerUuid) {
        return HUD_BY_PLAYER.computeIfAbsent(playerUuid, ignored -> new ConcurrentHashMap<>());
    }

    private static Map<String, CooldownState> stateMapFor(UUID playerUuid) {
        return STATE_BY_PLAYER.computeIfAbsent(playerUuid, ignored -> new ConcurrentHashMap<>());
    }

    private static Map<String, Integer> displaySlotMapFor(UUID playerUuid) {
        return DISPLAY_SLOT_BY_PLAYER.computeIfAbsent(playerUuid, ignored -> new ConcurrentHashMap<>());
    }

    private static void removeHud(HyUIHud hud) {
        if (hud == null) {
            return;
        }

        try {
            hud.remove();
        } catch (Exception exception) {
            System.out.println("[DruidAbilityCooldownHud] remove failed reason=" + exception.getClass().getSimpleName());
        }
    }

    private static void cleanupPlayerCooldownMaps(UUID playerUuid) {
        if (playerUuid == null || !isPlayerCooldownEmpty(playerUuid)) {
            return;
        }
        HUD_BY_PLAYER.remove(playerUuid);
        STATE_BY_PLAYER.remove(playerUuid);
        DISPLAY_SLOT_BY_PLAYER.remove(playerUuid);
        cancelRefreshTask(playerUuid);
    }

    private static boolean isPlayerCooldownEmpty(UUID playerUuid) {
        Map<String, HyUIHud> playerHuds = HUD_BY_PLAYER.get(playerUuid);
        Map<String, CooldownState> playerStates = STATE_BY_PLAYER.get(playerUuid);
        return (playerHuds == null || playerHuds.isEmpty()) && (playerStates == null || playerStates.isEmpty());
    }

    private static String cooldownKey(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "unknown";
        }
        String canonicalKey = DruidAbilityItemIds.canonicalCooldownItemId(itemId);
        return canonicalKey == null || canonicalKey.isBlank() ? itemId : canonicalKey;
    }

    private static String rawIdSuffix(String abilityKey) {
        String normalized = cooldownKey(abilityKey).replaceAll("[^A-Za-z0-9]", "");
        return normalized.isBlank() ? "Unknown" : normalized;
    }

    private static LabelBuilder createCooldownLabel(CooldownState state, String rawIdSuffix) {
        HyUIStyle labelStyle = new HyUIStyle()
                .setFontSize(18f)
                .setRenderBold(true)
                .setTextColor("#FFFFFFFF")
                .setHorizontalAlignment(Alignment.Center);

        return LabelBuilder.label()
                .withRawId("druidAbilityCooldownHotbarLabel" + rawIdSuffix)
                .withAnchor(new HyUIAnchor()
                        .setTop(0)
                        .setWidth(LABEL_WIDTH)
                        .setHeight(LABEL_HEIGHT))
                .withStyle(labelStyle)
                .withText(formatCooldownLabel(state.remainingMillis()));
    }

    private static int clampSlotIndex(int slotIndex) {
        if (slotIndex < 0) {
            return 0;
        }
        if (slotIndex >= HOTBAR_SLOT_COUNT) {
            return HOTBAR_SLOT_COUNT - 1;
        }
        return slotIndex;
    }

    private static int resolveInitialSlotIndex(Player player, int fallbackSlotIndex, String itemId) {
        int itemSlot = findHotbarSlotForItem(player, itemId);
        if (itemSlot >= 0) {
            return itemSlot;
        }

        int activeSlot = resolveActiveHotbarSlot(player);
        if (activeSlot >= 0) {
            return clampSlotIndex(activeSlot);
        }

        return clampSlotIndex(fallbackSlotIndex);
    }

    private static int resolveDisplaySlotIndex(Player player, CooldownState state) {
        int itemSlot = findHotbarSlotForItem(player, state.itemId);
        if (itemSlot >= 0) {
            return itemSlot;
        }
        return clampSlotIndex(state.slotIndex);
    }

    private static int findHotbarSlotForItem(Player player, String itemId) {
        if (player == null || itemId == null || itemId.isBlank()) {
            return -1;
        }

        try {
            Object inventory = player.getClass().getMethod("getInventory").invoke(player);
            if (inventory == null) {
                return -1;
            }

            Object hotbar = inventory.getClass().getMethod("getHotbar").invoke(inventory);
            if (hotbar == null) {
                return -1;
            }

            Method getStack = findGetStackMethod(hotbar);
            if (getStack == null) {
                return -1;
            }

            for (int slot = 0; slot < HOTBAR_SLOT_COUNT; slot++) {
                String slotItemId = getItemIdFromStack(invokeGetStack(hotbar, getStack, slot));
                if (matchesCooldownItem(slotItemId, itemId)) {
                    return slot;
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static int resolveActiveHotbarSlot(Player player) {
        if (player == null) {
            return -1;
        }

        try {
            Object inventory = player.getClass().getMethod("getInventory").invoke(player);
            if (inventory == null) {
                return -1;
            }

            Object value = inventory.getClass().getMethod("getActiveHotbarSlot").invoke(inventory);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static boolean matchesCooldownItem(String slotItemId, String cooldownItemId) {
        return DruidAbilityItemIds.matchesCanonicalCooldownItem(slotItemId, cooldownItemId);
    }

    private static Method findGetStackMethod(Object hotbar) {
        if (hotbar == null) {
            return null;
        }

        Class<?> clazz = hotbar.getClass();
        try {
            return clazz.getMethod("getItemStackForSlot", int.class);
        } catch (Exception ignored) {
        }
        try {
            return clazz.getMethod("getItemStackForSlot", short.class);
        } catch (Exception ignored) {
        }

        for (Method method : clazz.getMethods()) {
            if (method.getParameterCount() == 1 && method.getName().toLowerCase(Locale.ROOT).contains("getitemstack")) {
                return method;
            }
        }
        return null;
    }

    private static Object invokeGetStack(Object container, Method getter, int slot) {
        if (container == null || getter == null || slot < 0) {
            return null;
        }

        try {
            Class<?> paramType = getter.getParameterTypes()[0];
            if (paramType == int.class || paramType == Integer.class) {
                return getter.invoke(container, slot);
            }
            if (paramType == short.class || paramType == Short.class) {
                return getter.invoke(container, (short) slot);
            }
            if (paramType == byte.class || paramType == Byte.class) {
                return getter.invoke(container, (byte) slot);
            }
            return getter.invoke(container, slot);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String getItemIdFromStack(Object itemStack) {
        if (itemStack == null) {
            return null;
        }

        try {
            Method getItemId = itemStack.getClass().getMethod("getItemId");
            Object itemId = getItemId.invoke(itemStack);
            return itemId instanceof String ? (String) itemId : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String formatCooldownLabel(long remainingMillis) {
        long remainingSeconds = Math.max(0L, (remainingMillis + 999L) / 1000L);
        return String.format(Locale.ROOT, "%ds", remainingSeconds);
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

    private static final class CooldownState {
        private final int slotIndex;
        private final String itemId;
        private final long cooldownEndMillis;
        private final long totalMillis;

        private CooldownState(int slotIndex, String itemId, long cooldownEndMillis, long totalMillis) {
            this.slotIndex = slotIndex;
            this.itemId = itemId;
            this.cooldownEndMillis = cooldownEndMillis;
            this.totalMillis = totalMillis;
        }

        private long remainingMillis() {
            return Math.max(0L, cooldownEndMillis - System.currentTimeMillis());
        }
    }
}
