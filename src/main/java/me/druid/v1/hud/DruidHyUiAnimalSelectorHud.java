package me.druid.v1.hud;

import au.ellie.hyui.builders.GroupBuilder;
import au.ellie.hyui.builders.HudBuilder;
import au.ellie.hyui.builders.HyUIAnchor;
import au.ellie.hyui.builders.HyUIHud;
import au.ellie.hyui.builders.HyUIStyle;
import au.ellie.hyui.builders.LabelBuilder;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DruidHyUiAnimalSelectorHud {
    private static final String BASE_UI_FILE = "Pages/EllieAU_HyUI_Placeholder.ui";
    private static final ConcurrentHashMap<UUID, HyUIHud> SELECTOR_BY_PLAYER = new ConcurrentHashMap<>();
    private static final List<String> FORM_NAMES = List.of(
            "Bear",
            "Ram",
            "Duck",
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
            HudBuilder builder = createHudBuilder(playerRef);
            HyUIHud selectorHud = builder.show();
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

    private static HudBuilder createHudBuilder(PlayerRef playerRef) {
        HyUIAnchor rootAnchor = new HyUIAnchor()
                .setLeft(12)
                .setTop(46)
                .setWidth(260)
                .setHeight(300);

        HyUIStyle titleStyle = new HyUIStyle()
                .setFontSize(16f)
                .setRenderBold(true)
                .setTextColor("#FFFFFFFF");

        HyUIStyle itemStyle = new HyUIStyle()
                .setFontSize(14f)
                .setTextColor("#FFFFFFFF");

        HyUIStyle instructionStyle = new HyUIStyle()
                .setFontSize(13f)
                .setTextColor("#FFDDDDDD");

        GroupBuilder root = GroupBuilder.group()
                .withRawId("druidFormSelectorRoot")
                .withAnchor(rootAnchor)
                .addChild(
                        LabelBuilder.label()
                                .withRawId("druidFormSelectorTitle")
                                .withAnchor(new HyUIAnchor().setLeft(0).setTop(0).setWidth(260).setHeight(24))
                                .withStyle(titleStyle)
                                .withText("Animal Selector")
                );

        int top = 28;
        for (int i = 0; i < FORM_NAMES.size(); i++) {
            String displayName = FORM_NAMES.get(i);
            root.addChild(
                    LabelBuilder.label()
                            .withRawId("druidSelectorForm_" + i)
                            .withAnchor(new HyUIAnchor().setLeft(0).setTop(top).setWidth(260).setHeight(18))
                            .withStyle(itemStyle)
                            .withText("- " + displayName)
            );
            top += 19;
        }

        root.addChild(
                LabelBuilder.label()
                        .withRawId("druidSelectorInstruction1")
                        .withAnchor(new HyUIAnchor().setLeft(0).setTop(top + 8).setWidth(260).setHeight(18))
                        .withStyle(instructionStyle)
                        .withText("Use command to transform:")
        );

        root.addChild(
                LabelBuilder.label()
                        .withRawId("druidSelectorInstruction2")
                        .withAnchor(new HyUIAnchor().setLeft(0).setTop(top + 26).setWidth(260).setHeight(18))
                        .withStyle(instructionStyle)
                        .withText("/shapeshift <form>")
        );

        root.addChild(
                LabelBuilder.label()
                        .withRawId("druidSelectorInstruction3")
                        .withAnchor(new HyUIAnchor().setLeft(0).setTop(top + 44).setWidth(260).setHeight(18))
                        .withStyle(instructionStyle)
                        .withText("Example: /shapeshift bear")
        );

        return HudBuilder.hudForPlayer(playerRef)
                .fromFile(BASE_UI_FILE)
                .addElement(root);
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
