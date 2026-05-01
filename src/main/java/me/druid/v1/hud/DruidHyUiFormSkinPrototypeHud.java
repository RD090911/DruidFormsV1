package me.druid.v1.hud;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.ButtonBuilder;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.druid.v1.forms.FormDefinition;
import me.druid.v1.forms.FormId;
import me.druid.v1.forms.FormPresentationDefinition;
import me.druid.v1.forms.FormPresentationRegistry;
import me.druid.v1.forms.FormRegistry;
import me.druid.v1.forms.FormSkinResolver;
import me.druid.v1.forms.PlayerFormSessionStore;
import me.druid.v1.forms.SkinDefinition;
import me.druid.v1.forms.SkinId;
import me.druid.v1.forms.SkinRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class DruidHyUiFormSkinPrototypeHud {
    private static final String RUNTIME_TRACE_PREFIX = "[DruidSkinRuntimeTrace]";
    private static final AtomicLong OPEN_SESSION_SEQUENCE = new AtomicLong(0L);
    private static final ConcurrentHashMap<UUID, HyUIPage> PAGE_BY_PLAYER = new ConcurrentHashMap<>();
    private static final String CLASS_BUTTON_ID_PREFIX = "druid-form-menu-class-";
    private static final int OUTER_MENU_HEIGHT = 840;
    private static final int HELP_FOOTER_HEIGHT = 290;
    private static final String SELECTED_CLASS_LABEL_ID = "druid-form-menu-selected-class-label";
    private static final String SKINS_HEADER_LABEL_ID = "druid-form-menu-skins-header-label";
    private static final String SKIN_BUTTON_ID_PREFIX = "druid-form-menu-skin-";
    private static final String SKIN_GROUP_ID_PREFIX = "druid-form-menu-skin-group-";
    private static final String SKIN_GROUP_NONE_ID = SKIN_GROUP_ID_PREFIX + "none";
    private static final String CLOSE_BUTTON_ID = "druid-form-menu-close";

    private DruidHyUiFormSkinPrototypeHud() {
    }

    public static void open(Player player) {
        if (player == null) return;
        long sessionId = OPEN_SESSION_SEQUENCE.incrementAndGet();
        String playerName = resolvePlayerName(player);
        logRuntime(sessionId, "open-entry", "player=" + playerName);

        PlayerRef playerRef = resolvePlayerRef(player);
        Store<EntityStore> store = resolveStore(player);
        if (playerRef == null || playerRef.getUuid() == null || store == null) {
            logRuntime(sessionId, "open-skip", "reason=missing-playerRef-or-store player=" + playerName);
            System.out.println("[DruidHyUI] Form skin menu open skipped (missing playerRef/store).");
            return;
        }
        open(player, playerRef, store, sessionId, playerName);
    }

    private static void open(Player player, PlayerRef playerRef, Store<EntityStore> store, long sessionId, String playerName) {
        if (player == null || playerRef == null || playerRef.getUuid() == null || store == null) {
            return;
        }

        UUID playerUuid = playerRef.getUuid();
        close(playerUuid);
        FormId selectedForm = PlayerFormSessionStore.getSelectedForm(playerUuid);
        String menuHtml = buildMenuHtml(selectedForm);
        logRuntime(sessionId, "after-buildMenuHtml",
                "player=" + playerName + " selectedForm=" + safeToken(selectedForm));

        try {
            PageBuilder pageBuilder = PageBuilder.pageForPlayer(playerRef)
                    .fromHtml(menuHtml)
                    .onDismiss((dismissed, byUser) -> PAGE_BY_PLAYER.remove(playerUuid, dismissed));

            bindClassSelectionHandlers(pageBuilder, playerUuid, playerName, sessionId);
            logRuntime(sessionId, "after-bindClassSelectionHandlers", "player=" + playerName);
            bindSkinSelectionHandlers(pageBuilder, playerUuid, playerName, sessionId);
            logRuntime(sessionId, "after-bindSkinSelectionHandlers", "player=" + playerName);
            bindCloseHandler(pageBuilder, playerUuid);
            logRuntime(sessionId, "after-bindCloseHandler", "player=" + playerName);

            logRuntime(sessionId, "before-pageBuilder.open", "player=" + playerName);
            HyUIPage page = pageBuilder.open(store);
            PAGE_BY_PLAYER.put(playerUuid, page);
            logRuntime(sessionId, "after-pageBuilder.open", "player=" + playerName);
        } catch (Exception e) {
            logRuntime(sessionId, "open-failed", "player=" + playerName + " error=" + e.getMessage());
            System.out.println("[DruidHyUI] Form skin menu open failed: " + e.getMessage());
        }
    }

    public static void close(UUID playerUuid) {
        if (playerUuid == null) return;
        HyUIPage page = PAGE_BY_PLAYER.remove(playerUuid);
        if (page == null) return;
        try {
            page.close();
        } catch (Exception e) {
            System.out.println("[DruidHyUI] Form skin menu close failed: " + e.getMessage());
        }
    }

    public static void refreshFromSession(UUID playerUuid) {
        if (playerUuid == null) return;
        HyUIPage page = PAGE_BY_PLAYER.get(playerUuid);
        if (page == null) return;
        FormId selectedForm = PlayerFormSessionStore.getSelectedForm(playerUuid);
        updateMenuState(page, selectedForm, playerUuid);
    }

    private static String buildMenuHtml(FormId selectedForm) {
        StringBuilder html = new StringBuilder();
        html.append("<div class='page-overlay'>");
        html.append("<div class='panel' style='anchor-left: 500; anchor-top: 120; anchor-width: 1080; anchor-height: ")
                .append(OUTER_MENU_HEIGHT)
                .append(";'>");

        html.append("<div class='container' data-hyui-title='DRUID FORM MENU' style='anchor-left: 14; anchor-top: 14; anchor-right: 14; anchor-bottom: 300;'>");
        html.append("<div class='container-contents' style='layout-mode: top;'>");
        html.append("<p style='anchor-top: 2;'>Select a Class, then choose a Skin</p>");

        html.append("<div style='anchor-left: 0; anchor-top: 18; anchor-right: 0; anchor-bottom: 0;'>");

        html.append("<div class='container' data-hyui-title='CLASSES' style='anchor-left: 0; anchor-top: 0; anchor-width: 340; anchor-bottom: 0;'>");
        html.append("<div class='container-contents' style='layout-mode: left; padding: (Left:10,Top:8,Right:10,Bottom:8);'>");
        for (FormId formId : FormPresentationRegistry.getOrderedForms()) {
            String classLine = resolveFormLabel(formId);
            html.append("<button id='")
                    .append(escapeHtml(resolveClassButtonId(formId)))
                    .append("' class='")
                    .append(formId == selectedForm ? "secondary-button" : "tertiary-button")
                    .append("' style='anchor-top: 2;'>")
                    .append(escapeHtml(classLine))
                    .append("</button>");
        }
        html.append("</div>");
        html.append("</div>");

        html.append("<div class='container' data-hyui-title='CLASS SKINS' style='anchor-left: 356; anchor-top: 0; anchor-right: 0; anchor-bottom: 0;'>");
        html.append("<div class='container-contents' style='layout-mode: top; padding: (Left:12,Top:8,Right:12,Bottom:8);'>");

        if (selectedForm == null) {
            html.append("<p id='").append(SELECTED_CLASS_LABEL_ID).append("' style='anchor-top: 2;'>Selected: None</p>");
            html.append("<p id='").append(SKINS_HEADER_LABEL_ID).append("' style='anchor-top: 4;'>No class selected yet.</p>");
        } else {
            html.append("<p id='").append(SELECTED_CLASS_LABEL_ID).append("' style='anchor-top: 2;'>Selected: ")
                    .append(escapeHtml(resolveFormLabel(selectedForm)))
                    .append("</p>");
            html.append("<p id='").append(SKINS_HEADER_LABEL_ID).append("' style='anchor-top: 4;'>Available skins:</p>");
        }

        html.append("<div id='").append(SKIN_GROUP_NONE_ID).append("' style='anchor-left: 0; anchor-right: 0; layout-mode: top; ")
                .append(resolveGroupVisibilityStyle(selectedForm == null))
                .append("'>");
        html.append("<div style='anchor-left: 0; anchor-right: 0; layout-mode: top;'>");
        html.append("<p>Use: /shapeshift menu select {class}</p>");
        html.append("</div>");
        html.append("</div>");

        html.append("<div id='druid-form-menu-skins-groups-root' style='anchor-left: 0; anchor-right: 0; layout-mode: top;'>");
        for (FormId formId : FormPresentationRegistry.getOrderedForms()) {
            boolean activeForm = formId == selectedForm;
            html.append("<div id='").append(resolveSkinGroupId(formId)).append("' style='anchor-left: 0; anchor-right: 0; layout-mode: top; ")
                    .append(resolveGroupVisibilityStyle(activeForm))
                    .append("'>");
            html.append("<div style='anchor-left: 0; anchor-right: 0; layout-mode: top;'>");
            List<String> lines = resolveSkinLinesForForm(formId);
            for (int i = 0; i < lines.size(); i++) {
                html.append("<button id='")
                        .append(resolveSkinButtonId(formId, i))
                        .append("' class='tertiary-button' style='anchor-top: 2;'>")
                        .append(escapeHtml(lines.get(i)))
                        .append("</button>");
            }
            html.append("</div>");
            html.append("</div>");
        }
        html.append("</div>");

        html.append("</div>");
        html.append("</div>");
        html.append("</div>");
        html.append("</div>");
        html.append("</div>");

        html.append("<div class='container' data-hyui-title='HELP' style='anchor-left: 14; anchor-right: 14; anchor-bottom: 58; anchor-height: ")
                .append(HELP_FOOTER_HEIGHT)
                .append(";'>");
        html.append("<div class='container-contents' style='layout-mode: left; padding: (Left: 8, Top: 6, Right: 12, Bottom: 12);'>");
        html.append("<p>Transform</p>");
        html.append("<p>/shapeshift {class}</p>");
        html.append("<p>HUD</p>");
        html.append("<p>/shapeshift hud on</p>");
        html.append("<p>/shapeshift hud off</p>");
        html.append("<p>Admin</p>");
        html.append("<p>/druid allow {player}</p>");
        html.append("<p>/druid deny {player}</p>");
        html.append("</div>");
        html.append("</div>");
        html.append("<button id='")
                .append(CLOSE_BUTTON_ID)
                .append("' class='negative-button' style='anchor-left: 28; anchor-bottom: 8; anchor-width: 208; anchor-height: 44; border-radius: 12; border-width: 2; border-color: #9fb2c29a; background-color: #1d3a50c8;'>")
                .append("Cancel")
                .append("</button>");
        html.append("</div>");
        return html.toString();
    }

    private static void bindClassSelectionHandlers(PageBuilder pageBuilder, UUID playerUuid, String playerName, long sessionId) {
        if (pageBuilder == null || playerUuid == null || playerName == null) {
            return;
        }
        for (FormId formId : FormPresentationRegistry.getOrderedForms()) {
            String elementId = resolveClassButtonId(formId);
            bindClassSelectionListener(pageBuilder, elementId, formId, playerUuid, playerName, sessionId);
        }
    }

    private static void bindSkinSelectionHandlers(PageBuilder pageBuilder, UUID playerUuid, String playerName, long sessionId) {
        if (pageBuilder == null || playerUuid == null || playerName == null) {
            return;
        }
        for (FormId formId : FormPresentationRegistry.getOrderedForms()) {
            List<SkinId> skins = FormSkinResolver.getAvailableSkinsForForm(formId);
            for (int i = 0; i < skins.size(); i++) {
                SkinId skinId = skins.get(i);
                if (!FormSkinResolver.isSkinImplemented(skinId)) {
                    continue;
                }
                final int skinIndex = i;
                final FormId targetForm = formId;
                String elementId = resolveSkinButtonId(targetForm, skinIndex);
                bindSkinSelectionListener(pageBuilder, elementId, targetForm, skinIndex, playerUuid, playerName, sessionId);
            }
        }
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

    private static void bindClassSelectionListener(PageBuilder pageBuilder, String elementId, FormId formId, UUID playerUuid, String playerName, long sessionId) {
        if (pageBuilder == null || elementId == null || formId == null || playerUuid == null || playerName == null) {
            return;
        }
        pageBuilder.addEventListener(elementId, CustomUIEventBindingType.Activating, (ignored, ctx) -> {
            logRuntime(sessionId, "class-click",
                    "player=" + playerName + " buttonId=" + elementId + " class=" + safeToken(formId));
            handleClassSelection(playerUuid, formId, elementId, ctx);
        });
    }

    private static void handleClassSelection(UUID playerUuid, FormId formId, String elementId, au.ellie.hyui.events.UIContext ctx) {
        PlayerFormSessionStore.setSelectedForm(playerUuid, formId);
        updateMenuState(ctx, formId, playerUuid);
    }

    private static void bindSkinSelectionListener(PageBuilder pageBuilder, String elementId, FormId targetForm, int skinIndex, UUID playerUuid, String playerName, long sessionId) {
        if (pageBuilder == null || elementId == null || targetForm == null || playerUuid == null || playerName == null) {
            return;
        }
        pageBuilder.addEventListener(elementId, CustomUIEventBindingType.Activating, (ignored, ctx) -> {
            logRuntime(sessionId, "skin-click",
                    "player=" + playerName + " buttonId=" + elementId + " class=" + safeToken(targetForm) + " index=" + skinIndex);
            handleSkinSelection(playerUuid, targetForm, skinIndex, elementId, ctx);
        });
    }

    private static void handleSkinSelection(UUID playerUuid, FormId targetForm, int skinIndex, String elementId, au.ellie.hyui.events.UIContext ctx) {
        List<SkinId> currentSkins = FormSkinResolver.getAvailableSkinsForForm(targetForm);
        if (targetForm == null) {
            acknowledgeInteraction(ctx, "skin-handler-null-form");
            return;
        }
        if (currentSkins == null) {
            acknowledgeInteraction(ctx, "skin-handler-null-skin-list");
            return;
        }
        if (skinIndex < 0 || skinIndex >= currentSkins.size()) {
            acknowledgeInteraction(ctx, "skin-handler-early-return");
            return;
        }
        PlayerFormSessionStore.setSelectedForm(playerUuid, targetForm);
        PlayerFormSessionStore.setSelectedSkin(playerUuid, targetForm, currentSkins.get(skinIndex));
        updateMenuState(ctx, targetForm, playerUuid);
    }

    private static void updateMenuState(au.ellie.hyui.events.UIContext context, FormId selectedForm, UUID playerUuid) {
        if (context == null) {
            return;
        }

        for (FormId formId : FormPresentationRegistry.getOrderedForms()) {
            String classText = resolveFormLabel(formId);
            final String text = classText;
            context.editById(resolveClassButtonId(formId), ButtonBuilder.class,
                    button -> button.withText(text).withDisabled(formId == selectedForm));
        }

        if (selectedForm == null) {
            context.editById(SELECTED_CLASS_LABEL_ID, LabelBuilder.class, label -> label.withText("Selected: None"));
            context.editById(SKINS_HEADER_LABEL_ID, LabelBuilder.class, label -> label.withText("No class selected yet."));
            context.editById(SKIN_GROUP_NONE_ID, element -> element.withVisible(true));
            for (FormId formId : FormPresentationRegistry.getOrderedForms()) {
                context.editById(resolveSkinGroupId(formId), element -> element.withVisible(false));
            }
        } else {
            context.editById(SELECTED_CLASS_LABEL_ID, LabelBuilder.class, label -> label.withText("Selected: " + resolveFormLabel(selectedForm)));
            context.editById(SKINS_HEADER_LABEL_ID, LabelBuilder.class, label -> label.withText("Available skins:"));
            context.editById(SKIN_GROUP_NONE_ID, element -> element.withVisible(false));
            SkinId selectedSkin = playerUuid == null ? null : PlayerFormSessionStore.getSelectedSkin(playerUuid, selectedForm);
            for (FormId formId : FormPresentationRegistry.getOrderedForms()) {
                boolean activeForm = formId == selectedForm;
                context.editById(resolveSkinGroupId(formId), element -> element.withVisible(activeForm));

                List<SkinId> skins = FormSkinResolver.getAvailableSkinsForForm(formId);
                for (int i = 0; i < skins.size(); i++) {
                    SkinId rowSkin = skins.get(i);
                    boolean runtimeBacked = FormSkinResolver.isSkinImplemented(rowSkin);
                    boolean isSelected = activeForm && selectedSkin != null && rowSkin == selectedSkin;
                    String buttonId = resolveSkinButtonId(formId, i);
                    context.editById(buttonId, ButtonBuilder.class,
                            button -> button.withDisabled(!activeForm || !runtimeBacked || isSelected));
                }
            }
        }

        acknowledgeInteraction(context, "updateMenuState");
    }

    private static void acknowledgeInteraction(au.ellie.hyui.events.UIContext context, String source) {
        if (context == null) {
            return;
        }
        try {
            context.updatePage(false);
        } catch (Exception e) {
            System.out.println("[DruidHyUI] Form skin menu update failed (" + source + "): " + e.getMessage());
        }
    }

    private static String resolveClassButtonId(FormId formId) {
        return CLASS_BUTTON_ID_PREFIX + resolveClassToken(formId);
    }

    private static String resolveSkinGroupId(FormId formId) {
        return SKIN_GROUP_ID_PREFIX + resolveClassToken(formId);
    }

    private static String resolveSkinButtonId(FormId formId, int index) {
        return SKIN_BUTTON_ID_PREFIX + resolveClassToken(formId) + "-" + index;
    }

    private static String resolveGroupVisibilityStyle(boolean visible) {
        return visible
                ? "display: block; visibility: shown;"
                : "display: none; visibility: hidden;";
    }

    private static void logRuntime(long sessionId, String stage, String details) {
        System.out.println(RUNTIME_TRACE_PREFIX + " session=" + sessionId + " stage=" + stage + " " + details);
    }

    private static String safeToken(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private static String resolvePlayerName(Player player) {
        if (player == null) {
            return "unknown";
        }
        try {
            String displayName = player.getDisplayName();
            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
        } catch (Exception ignored) {
        }
        try {
            UUID playerUuid = player.getUuid();
            if (playerUuid != null) {
                return playerUuid.toString();
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    private static String resolveClassToken(FormId formId) {
        if (formId == null) {
            return "unknown";
        }
        String token = formId.name();
        if (token.startsWith("FORM_")) {
            token = token.substring(5);
        }
        return token.toLowerCase(Locale.ROOT);
    }

    private static String resolveFormLabel(FormId formId) {
        if (formId == null) {
            return "Unknown";
        }

        FormPresentationDefinition presentation = FormPresentationRegistry.getDefinition(formId);
        if (presentation != null && presentation.getShortLabel() != null && !presentation.getShortLabel().isBlank()) {
            return presentation.getShortLabel();
        }

        FormDefinition definition = FormRegistry.getDefinition(formId);
        if (definition != null && definition.getDisplayName() != null && !definition.getDisplayName().isBlank()) {
            return definition.getDisplayName();
        }
        return formId.name();
    }

    private static String resolveSkinSummary(FormId formId) {
        List<SkinId> skins = FormSkinResolver.getAvailableSkinsForForm(formId);
        if (skins.isEmpty()) {
            return "No skins registered";
        }

        List<String> entries = new ArrayList<>();
        if (skins.size() == 1) {
            entries.add(formatSkinEntry(skins.get(0)));
            return String.join(", ", entries);
        }

        for (SkinId skinId : skins) {
            entries.add(formatSkinEntry(skinId));
        }
        return String.join(", ", entries);
    }

    private static List<String> resolveSkinLinesForForm(FormId formId) {
        List<String> lines = new ArrayList<>();
        if (formId == null) {
            return lines;
        }

        List<SkinId> skins = FormSkinResolver.getAvailableSkinsForForm(formId);
        if (skins.isEmpty()) {
            lines.add("No skins registered");
            return lines;
        }

        for (SkinId skinId : skins) {
            lines.add("- " + formatSkinEntry(skinId));
        }
        return lines;
    }

    private static String formatSkinEntry(SkinId skinId) {
        if (skinId == null) {
            return "Unknown";
        }
        SkinDefinition definition = SkinRegistry.getDefinition(skinId);
        String name = definition != null && definition.getDisplayName() != null && !definition.getDisplayName().isBlank()
                ? definition.getDisplayName()
                : skinId.name();
        if (!FormSkinResolver.isSkinImplemented(skinId)) {
            return name + " (Coming Soon)";
        }
        return name;
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

    @SuppressWarnings("unchecked")
    private static Store<EntityStore> resolveStore(Player player) {
        if (player == null) return null;

        try {
            Method getReference = player.getClass().getMethod("getReference");
            Object reference = getReference.invoke(player);
            if (reference != null) {
                Method getStore = reference.getClass().getMethod("getStore");
                Object store = getStore.invoke(reference);
                if (store instanceof Store<?>) {
                    return (Store<EntityStore>) store;
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
}
