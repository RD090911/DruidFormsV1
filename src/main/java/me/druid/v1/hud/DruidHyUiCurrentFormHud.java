package me.druid.v1.hud;

import au.ellie.hyui.builders.GroupBuilder;
import au.ellie.hyui.builders.HudBuilder;
import au.ellie.hyui.builders.HyUIAnchor;
import au.ellie.hyui.builders.HyUIHud;
import au.ellie.hyui.builders.HyUIStyle;
import au.ellie.hyui.builders.LabelBuilder;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import me.druid.v1.ShapeshiftHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DruidHyUiCurrentFormHud {
    private static final ConcurrentHashMap<UUID, HyUIHud> HUD_BY_PLAYER = new ConcurrentHashMap<>();

    private DruidHyUiCurrentFormHud() {
    }

    public static void attachOrRefresh(Player player) {
        DruidHyUiPersistentFormHud.attachOrRefresh(player);
    }

    public static void detach(UUID playerUuid) {
        if (playerUuid == null) return;
        HyUIHud hud = HUD_BY_PLAYER.remove(playerUuid);
        if (hud != null) {
            try {
                hud.remove();
                System.out.println("[DruidHyUI] HUD removed for " + playerUuid);
            } catch (Exception e) {
                System.out.println("[DruidHyUI] HUD remove failed: " + e.getMessage());
            }
        }

        DruidHyUiPersistentFormHud.detach(playerUuid);
    }

    private static HudBuilder createHudBuilder(PlayerRef playerRef, String activeFormText) {
        HyUIAnchor rootAnchor = new HyUIAnchor()
                .setLeft(12)
                .setTop(12)
                .setWidth(360)
                .setHeight(28);

        HyUIStyle labelStyle = new HyUIStyle()
                .setFontSize(18f)
                .setRenderBold(true)
                .setTextColor("#FFFFFFFF");

        GroupBuilder root = GroupBuilder.group()
                .withRawId("druidFormHudRoot")
                .withAnchor(rootAnchor)
                .addChild(
                        LabelBuilder.label()
                                .withRawId("druidFormHudLabel")
                                .withAnchor(new HyUIAnchor().setLeft(0).setTop(0).setWidth(360).setHeight(28))
                                .withStyle(labelStyle)
                                .withText(activeFormText)
                );

        return HudBuilder.hudForPlayer(playerRef)
                .fromFile("Pages/EllieAU_HyUI_Placeholder.ui")
                .addElement(root);
    }

    private static String resolveActiveFormText(Player player) {
        String modelId;
        try {
            modelId = ShapeshiftHandler.activeForms.get(player.getDisplayName());
        } catch (Exception ignored) {
            modelId = null;
        }

        return "Active Form: " + displayNameFromModelId(modelId);
    }

    private static String displayNameFromModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) return "Human";
        String lower = modelId.toLowerCase(Locale.ROOT);
        if (lower.contains("bear")) return "Bear";
        if (lower.contains("ram")) return "Ram";
        if (lower.contains("duck")) return "Duck";
        if (lower.contains("shark")) return "Shark";
        if (lower.contains("hawk")) return "Hawk";
        if (lower.contains("tiger") || lower.contains("sabertooth")) return "Tiger";
        if (lower.contains("rabbit") || lower.contains("jackalope")) return "Rabbit";
        if (lower.contains("antelope")) return "Antelope";
        return "Human";
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
