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
import me.druid.v1.forms.FormRuntimeBridge;

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
        String animalKey = resolveAnimalKeyFromModelIdLower(lower);
        String legacyLabel = resolveLegacyLabelFromModelIdLower(lower);
        if (animalKey == null) return "Human";

        String formLabel = FormRuntimeBridge.resolveFormLabelForAnimal(animalKey);
        if ("duck".equals(animalKey) && formLabel != null && !formLabel.isBlank()) {
            return formLabel + " (Duck)";
        }

        if (legacyLabel != null) return legacyLabel;
        if (formLabel != null && !formLabel.isBlank()) return formLabel;
        return "Human";
    }

    private static String resolveLegacyLabelFromModelIdLower(String lowerModelId) {
        if (lowerModelId == null || lowerModelId.isBlank()) return null;
        if (lowerModelId.contains("bear")) return "Bear";
        if (lowerModelId.contains("ram")) return "Ram";
        if (lowerModelId.contains("duck")) return "Duck";
        if (lowerModelId.contains("shark")) return "Shark";
        if (lowerModelId.contains("hawk")) return "Hawk";
        if (lowerModelId.contains("tiger") || lowerModelId.contains("sabertooth")) return "Tiger";
        if (lowerModelId.contains("rabbit") || lowerModelId.contains("jackalope")) return "Rabbit";
        if (lowerModelId.contains("antelope")) return "Antelope";
        return null;
    }

    private static String resolveAnimalKeyFromModelIdLower(String lowerModelId) {
        if (lowerModelId == null || lowerModelId.isBlank()) return null;
        if (lowerModelId.contains("bear")) return "bear";
        if (lowerModelId.contains("ram")) return "ram";
        if (lowerModelId.contains("duck")) return "duck";
        if (lowerModelId.contains("shark")) return "shark";
        if (lowerModelId.contains("bluegill")) return "bluegill";
        if (lowerModelId.contains("hawk")) return "hawk";
        if (lowerModelId.contains("tiger") || lowerModelId.contains("sabertooth")) return "tiger";
        if (lowerModelId.contains("rabbit") || lowerModelId.contains("jackalope")) return "rabbit";
        if (lowerModelId.contains("antelope")) return "antelope";
        return null;
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
