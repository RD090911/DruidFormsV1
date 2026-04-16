package me.druid.v1.hud;

import au.ellie.hyui.builders.HudBuilder;
import au.ellie.hyui.builders.HyUIHud;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DruidHyUiAnimalSelectorHud {
    private static final ConcurrentHashMap<UUID, HyUIHud> SELECTOR_BY_PLAYER = new ConcurrentHashMap<>();
    private static final List<String> FORM_NAMES = List.of(
            "Bear",
            "Ram",
            "Aquatic",
            "Shark",
            "Hawk",
            "Tiger",
            "Rabbit",
            "Antelope",
            "Human"
    );

    private DruidHyUiAnimalSelectorHud() {
    }

    public static void open(Player player) {
        if (player == null) return;

        PlayerRef playerRef = resolvePlayerRef(player);
        if (playerRef == null || playerRef.getUuid() == null) {
            System.out.println("[DruidHyUI] PlayerRef missing; skipping selector open.");
            return;
        }

        UUID playerUuid = playerRef.getUuid();
        close(playerUuid);

        try {
            HyUIHud selectorHud = HudBuilder.hudForPlayer(playerRef)
                    .fromHtml(buildSelectorHtml())
                    .show();
            SELECTOR_BY_PLAYER.put(playerUuid, selectorHud);
            System.out.println("[DruidHyUI] Selector shown for " + playerUuid);
        } catch (Exception e) {
            System.out.println("[DruidHyUI] Selector open failed: " + e.getMessage());
        }
    }

    public static void close(UUID playerUuid) {
        if (playerUuid == null) return;
        HyUIHud selectorHud = SELECTOR_BY_PLAYER.remove(playerUuid);
        if (selectorHud == null) return;

        try {
            selectorHud.remove();
        } catch (Exception e) {
            System.out.println("[DruidHyUI] Selector close failed: " + e.getMessage());
        }
    }

    private static String buildSelectorHtml() {
        StringBuilder html = new StringBuilder();
        html.append("<div class='panel' style='anchor-left: 18; anchor-top: 52; anchor-width: 380; anchor-height: 370;'>");
        html.append("<div class='container' data-hyui-title='Druid Selector (Legacy)' style='anchor-left: 10; anchor-top: 10; anchor-right: 10; anchor-bottom: 10;'>");
        html.append("<div class='container-contents' style='layout-mode: left;'>");
        html.append("<p>Legacy transitional selector</p>");
        html.append("<p>Primary UI: /shapeshift menu</p>");
        html.append("<div style='anchor-top: 6; layout-mode: left;'>");
        for (String formName : FORM_NAMES) {
            html.append("<p>").append(escapeHtml(formName)).append("</p>");
        }
        html.append("</div>");
        html.append("<div style='anchor-top: 10; layout-mode: left;'>");
        html.append("<p>Use command to transform:</p>");
        html.append("<p>/shapeshift {form}</p>");
        html.append("<p>Example: /shapeshift bear</p>");
        html.append("</div>");
        html.append("</div>");
        html.append("</div>");
        html.append("</div>");
        return html.toString();
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
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
