package me.druid.v1.hud;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.PageBuilder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.druid.v1.ShapeshiftHandler;
import me.druid.v1.forms.FormId;
import me.druid.v1.forms.FormRuntimeBridge;
import me.druid.v1.forms.PlayerFormSessionStore;
import me.druid.v1.forms.SkinId;
import me.druid.v1.forms.SkinRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DruidHyUiAnimalSelectorHud {
    private static final ConcurrentHashMap<UUID, HyUIPage> PAGE_BY_PLAYER = new ConcurrentHashMap<>();
    private static final String CENTER_BUTTON_ID = "druid-radial-center";
    private static final String CLOSE_BUTTON_ID = "druid-radial-close";
    private static final List<RadialChoice> RADIAL_CHOICES = List.of(
            new RadialChoice("druid-radial-flight", "Flight", "flight", 292, 56),
            new RadialChoice("druid-radial-travel", "Travel", "travel", 520, 132),
            new RadialChoice("druid-radial-springer", "Springer", "springer", 590, 292),
            new RadialChoice("druid-radial-forager", "Forager", "forager", 520, 452),
            new RadialChoice("druid-radial-guardian", "Guardian", "guardian", 292, 528),
            new RadialChoice("druid-radial-warden", "Warden", "warden", 178, 504),
            new RadialChoice("druid-radial-stalker", "Stalker", "stalker", 64, 452),
            new RadialChoice("druid-radial-prowler", "Prowler", "prowler", -6, 292),
            new RadialChoice("druid-radial-aquatic", "Aquatic", "aquatic", 64, 132)
    );

    private DruidHyUiAnimalSelectorHud() {
    }

    public static void open(Player player) {
        if (player == null) return;

        PlayerRef playerRef = resolvePlayerRef(player);
        Store<EntityStore> store = resolveStore(player);
        if (playerRef == null || playerRef.getUuid() == null || store == null) {
            System.out.println("[DruidHyUI] Radial open skipped (missing playerRef/store).");
            return;
        }
        open(player, playerRef, store);
    }

    private static void open(Player player, PlayerRef playerRef, Store<EntityStore> store) {
        if (player == null || playerRef == null || playerRef.getUuid() == null || store == null) {
            return;
        }
        UUID playerUuid = playerRef.getUuid();
        close(playerUuid);

        try {
            PageBuilder pageBuilder = PageBuilder.pageForPlayer(playerRef)
                    .fromHtml(buildSelectorHtml())
                    .onDismiss((dismissed, byUser) -> PAGE_BY_PLAYER.remove(playerUuid, dismissed));

            bindChoiceHandlers(pageBuilder, player, playerUuid);
            bindCenterHandler(pageBuilder, player, playerUuid);
            bindCloseHandler(pageBuilder, playerUuid);

            HyUIPage page = pageBuilder.open(store);
            PAGE_BY_PLAYER.put(playerUuid, page);
        } catch (Exception e) {
            System.out.println("[DruidHyUI] Radial open failed: " + e.getMessage());
        }
    }

    public static void close(UUID playerUuid) {
        if (playerUuid == null) return;
        HyUIPage page = PAGE_BY_PLAYER.remove(playerUuid);
        if (page == null) return;

        try {
            page.close();
        } catch (Exception e) {
            System.out.println("[DruidHyUI] Radial close failed: " + e.getMessage());
        }
    }

    private static String buildSelectorHtml() {
        StringBuilder html = new StringBuilder();
        final int wheelSize = 780;
        final int wheelHalf = wheelSize / 2;
        final int buttonWidth = 140;
        final int buttonHeight = 62;
        final int classButtonWidth = 182;
        final int classButtonHeight = 81;
        final int buttonRadius = 252;
        final int centerSize = 160;
        final int centerInset = (wheelSize - centerSize) / 2;

        html.append("<div style='anchor-left: 0; anchor-top: 0; anchor-right: 0; anchor-bottom: 0;'>");
        html.append("<div style='anchor-left: 0; anchor-top: 0; anchor-right: 0; anchor-bottom: 0; background-color: #00000000;'></div>");
        html.append("<div style='anchor-left: 50%; anchor-top: 50%; anchor-width: ")
                .append(wheelSize)
                .append("; anchor-height: ")
                .append(wheelSize)
                .append("; transform: translate(-50%, -50%);'>");

        for (int i = 0; i < RADIAL_CHOICES.size(); i++) {
            RadialChoice choice = RADIAL_CHOICES.get(i);
            double angleDegrees = -90.0 + (i * (360.0 / RADIAL_CHOICES.size()));
            double angleRadians = Math.toRadians(angleDegrees);
            int anchorLeft = (int) Math.round(wheelHalf - (classButtonWidth / 2.0) + (Math.cos(angleRadians) * buttonRadius));
            int anchorTop = (int) Math.round(wheelHalf - (classButtonHeight / 2.0) + (Math.sin(angleRadians) * buttonRadius));
            if ("aquatic".equals(choice.classAlias) || "travel".equals(choice.classAlias)) {
                anchorTop += 28;
            } else if ("stalker".equals(choice.classAlias) || "forager".equals(choice.classAlias)) {
                anchorTop -= 28;
            }
            if ("guardian".equals(choice.classAlias) || "warden".equals(choice.classAlias)) {
                anchorTop -= 12;
            } else if ("flight".equals(choice.classAlias)) {
                anchorTop -= 18;
            }
            if ("guardian".equals(choice.classAlias)) {
                anchorLeft += 11;
            } else if ("warden".equals(choice.classAlias)) {
                anchorLeft -= 11;
            }
            html.append("<button id='")
                    .append(choice.buttonId)
                    .append("' class='custom-textbutton' ")
                    .append("data-hyui-default-bg='background-color: #1b3a4c80;' ")
                    .append("data-hyui-hovered-bg='background-color: #1b3a4c99;' ")
                    .append("data-hyui-pressed-bg='background-color: #1b3a4cb3;' ")
                    .append("data-hyui-disabled-bg='background-color: #1b3a4c66;' ")
                    .append("data-hyui-default-label-style='color: #dbe8f2; text-align: center; vertical-align: center; font-size: 15; font-weight: bold; text-transform: uppercase;' ")
                    .append("data-hyui-hovered-label-style='color: #e6f1fb; text-align: center; vertical-align: center; font-size: 15; font-weight: bold; text-transform: uppercase;' ")
                    .append("data-hyui-pressed-label-style='color: #dbe8f2; text-align: center; vertical-align: center; font-size: 15; font-weight: bold; text-transform: uppercase;' ")
                    .append("data-hyui-disabled-label-style='color: #9fb2c2; text-align: center; vertical-align: center; font-size: 15; font-weight: bold; text-transform: uppercase;' ")
                    .append("style='anchor-left: ")
                    .append(anchorLeft)
                    .append("; anchor-top: ")
                    .append(anchorTop)
                    .append("; anchor-width: ")
                    .append(classButtonWidth)
                    .append("; anchor-height: ")
                    .append(classButtonHeight)
                    .append("; border-radius: ")
                    .append(classButtonWidth / 2)
                    .append("; border-width: 2; border-color: #c2d2deb8; background-color: #1b3a4c80;'>")
                    .append("<p>")
                    .append(choice.label)
                    .append("</p>")
                    .append("</button>");
        }

        html.append("<button id='")
                .append(CENTER_BUTTON_ID)
                .append("' class='custom-textbutton' ")
                .append("data-hyui-default-bg='background-color: #1b3a4c80;' ")
                .append("data-hyui-hovered-bg='background-color: #1b3a4c99;' ")
                .append("data-hyui-pressed-bg='background-color: #1b3a4cb3;' ")
                .append("data-hyui-disabled-bg='background-color: #1b3a4c66;' ")
                .append("data-hyui-default-label-style='color: #dbe8f2; text-align: center; vertical-align: center; font-size: 16; font-weight: bold;' ")
                .append("data-hyui-hovered-label-style='color: #e6f1fb; text-align: center; vertical-align: center; font-size: 16; font-weight: bold;' ")
                .append("data-hyui-pressed-label-style='color: #dbe8f2; text-align: center; vertical-align: center; font-size: 16; font-weight: bold;' ")
                .append("data-hyui-disabled-label-style='color: #9fb2c2; text-align: center; vertical-align: center; font-size: 16; font-weight: bold;' ")
                .append("style='anchor-left: ")
                .append(centerInset)
                .append("; anchor-top: ")
                .append(centerInset)
                .append("; anchor-width: ")
                .append(centerSize)
                .append("; anchor-height: ")
                .append(centerSize)
                .append("; border-radius: ")
                .append(centerSize / 2)
                .append("; border-width: 3; border-color: #c2d2deb8; background-color: #1b3a4c80;'>")
                .append("<p>SKIN MENU</p>")
                .append("</button>");

        html.append("<button id='")
                .append(CLOSE_BUTTON_ID)
                .append("' class='negative-button' style='anchor-left: 50%; anchor-top: 686; anchor-width: 208; anchor-height: 44; transform: translateX(-50%); border-radius: 12; border-width: 2; border-color: #9fb2c29a; background-color: #1d3a50c8;'>CLOSE</button>");
        html.append("</div>");
        html.append("</div>");
        return html.toString();
    }

    private static void bindChoiceHandlers(PageBuilder pageBuilder, Player player, UUID playerUuid) {
        if (pageBuilder == null || player == null || playerUuid == null) return;
        for (RadialChoice choice : RADIAL_CHOICES) {
            pageBuilder.addEventListener(choice.buttonId, CustomUIEventBindingType.Activating, (ignored, ctx) -> {
                closeFromContext(ctx, playerUuid);
                runShapeshiftCommand(player, choice.classAlias);
            });
        }
    }

    private static void bindCenterHandler(PageBuilder pageBuilder, Player player, UUID playerUuid) {
        if (pageBuilder == null || player == null || playerUuid == null) return;
        pageBuilder.addEventListener(CENTER_BUTTON_ID, CustomUIEventBindingType.Activating, (ignored, ctx) -> {
            runShapeshiftCommand(player, "menu");
        });
    }

    private static void bindCloseHandler(PageBuilder pageBuilder, UUID playerUuid) {
        if (pageBuilder == null || playerUuid == null) return;
        pageBuilder.addEventListener(CLOSE_BUTTON_ID, CustomUIEventBindingType.Activating, (ignored, ctx) -> closeFromContext(ctx, playerUuid));
        pageBuilder.addEventListener(CLOSE_BUTTON_ID, CustomUIEventBindingType.MouseButtonReleased, (ignored, ctx) -> closeFromContext(ctx, playerUuid));
    }

    private static void closeFromContext(au.ellie.hyui.events.UIContext context, UUID playerUuid) {
        if (context != null) {
            try {
                context.getPage().ifPresent(page -> {
                    try {
                        page.close();
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
        }
        close(playerUuid);
    }

    private static void runShapeshiftCommand(Player player, String classAlias) {
        if (player == null || classAlias == null || classAlias.isBlank()) return;
        String command = "shapeshift " + classAlias;
        if (player.getWorld() != null) {
            player.getWorld().execute(() -> CommandManager.get().handleCommand(player, command));
            return;
        }
        CommandManager.get().handleCommand(player, command);
    }

    private static void seedSelectedFormFromActive(Player player, UUID playerUuid) {
        if (player == null || playerUuid == null) return;
        if (PlayerFormSessionStore.getSelectedForm(playerUuid) != null) return;

        String displayName = safeDisplayName(player);
        if (displayName == null || displayName.isBlank()) return;

        String activeAnimal = ShapeshiftHandler.activeForms.get(displayName);
        if (activeAnimal == null || activeAnimal.isBlank()) return;

        FormId formId = resolveFormIdFromRuntimeState(activeAnimal);
        if (formId != null) {
            PlayerFormSessionStore.setSelectedForm(playerUuid, formId);
        }
    }

    private static FormId resolveFormIdFromRuntimeState(String runtimeState) {
        if (runtimeState == null || runtimeState.isBlank()) return null;

        for (String candidate : buildAnimalCandidates(runtimeState)) {
            FormId fromFormRegistry = FormRuntimeBridge.resolveFormIdForAnimal(candidate);
            if (fromFormRegistry != null) {
                return fromFormRegistry;
            }
            SkinId skinId = SkinRegistry.getSkinForAnimal(candidate);
            if (skinId != null) {
                FormId fromSkinRegistry = SkinRegistry.getFormForSkin(skinId);
                if (fromSkinRegistry != null) {
                    return fromSkinRegistry;
                }
            }
        }
        return null;
    }

    private static List<String> buildAnimalCandidates(String runtimeState) {
        String normalized = runtimeState.trim().toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, normalized);

        String withoutPrefix = stripKnownPrefixes(normalized);
        addCandidate(candidates, withoutPrefix);

        int underscore = withoutPrefix.indexOf('_');
        if (underscore > 0) {
            addCandidate(candidates, withoutPrefix.substring(0, underscore));
        }

        return candidates;
    }

    private static String stripKnownPrefixes(String token) {
        if (token == null) return null;
        if (token.startsWith("druid_")) return token.substring("druid_".length());
        if (token.startsWith("model_")) return token.substring("model_".length());
        if (token.startsWith("fish_")) return token.substring("fish_".length());
        return token;
    }

    private static void addCandidate(List<String> candidates, String candidate) {
        if (candidates == null || candidate == null || candidate.isBlank()) return;
        if (!candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }

    private static String safeDisplayName(Player player) {
        if (player == null) return null;
        try {
            return player.getDisplayName();
        } catch (Exception ignored) {
            return null;
        }
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

    @SuppressWarnings("unchecked")
    private static Store<EntityStore> resolveStore(Player player) {
        if (player == null) return null;
        try {
            Method getReference = player.getClass().getMethod("getReference");
            Object reference = getReference.invoke(player);
            if (reference == null) return null;
            Method getStore = reference.getClass().getMethod("getStore");
            Object store = getStore.invoke(reference);
            if (store instanceof Store<?>) {
                return (Store<EntityStore>) store;
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

    private static final class RadialChoice {
        private final String buttonId;
        private final String label;
        private final String classAlias;
        private final int anchorLeft;
        private final int anchorTop;

        private RadialChoice(String buttonId, String label, String classAlias, int anchorLeft, int anchorTop) {
            this.buttonId = buttonId;
            this.label = label;
            this.classAlias = classAlias;
            this.anchorLeft = anchorLeft;
            this.anchorTop = anchorTop;
        }
    }
}
