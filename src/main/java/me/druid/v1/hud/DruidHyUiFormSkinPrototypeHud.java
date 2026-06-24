package me.druid.v1.hud;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.druid.v1.DruidPlayerCompat;
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
    private static final String SELECTED_CLASS_LABEL_ID = "druid-form-menu-selected-class-label";
    private static final String SKINS_HEADER_LABEL_ID = "druid-form-menu-skins-header-label";
    private static final String SELECTED_SKIN_LABEL_ID = "druid-form-menu-selected-skin-label";
    private static final String SKIN_BUTTON_ID_PREFIX = "druid-form-menu-skin-";
    private static final String CLOSE_BUTTON_ID = "druid-form-menu-close";
    private DruidHyUiFormSkinPrototypeHud() {
    }

    public static void open(Player player) {
        if (player == null) return;
        long sessionId = OPEN_SESSION_SEQUENCE.incrementAndGet();
        String playerName = resolvePlayerName(player);

        PlayerRef playerRef = resolvePlayerRef(player);
        Store<EntityStore> store = resolveStore(player);
        if (playerRef == null || playerRef.getUuid() == null || store == null) {
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
        String menuHtml = buildMenuHtml(selectedForm, playerUuid);

        try {
            PageBuilder pageBuilder = PageBuilder.pageForPlayer(playerRef)
                    .withLifetime(CustomPageLifetime.CanDismiss)
                    .fromHtml(menuHtml)
                    .onDismiss((dismissed, byUser) -> PAGE_BY_PLAYER.remove(playerUuid, dismissed));

            bindClassSelectionHandlers(pageBuilder, playerUuid, playerName, sessionId);
            bindSkinSelectionHandlers(pageBuilder, playerUuid, playerName, sessionId);
            bindCloseHandler(pageBuilder, playerUuid, playerName, sessionId);

            HyUIPage page = pageBuilder.open(store);
            PAGE_BY_PLAYER.put(playerUuid, page);
        } catch (Exception e) {
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

    private static String buildMenuHtml(FormId selectedForm, UUID playerUuid) {
        StringBuilder html = new StringBuilder();
        html.append("<div style='anchor-left: 0; anchor-top: 0; anchor-right: 0; anchor-bottom: 0;'>");
        html.append("<div style='anchor-left: 50%; anchor-top: 50%; anchor-width: 980; anchor-height: 740; transform: translate(-50%, -50%);'>");
        html.append("<div style='anchor-left: 0; anchor-top: 0; anchor-width: 980; anchor-height: 740; border-radius: 16; border-width: 2; border-color: #7893a6a8; background-color: #08141fe8;'></div>");
        html.append("<p style='anchor-left: 0; anchor-top: 0; anchor-width: 980; anchor-height: 34; color: #dbe8f2; text-align: center; vertical-align: center; font-size: 22; font-weight: bold;'>DRUID FORM MENU</p>");
        html.append("<p style='anchor-left: 0; anchor-top: 36; anchor-width: 980; anchor-height: 26; color: #b8cad8; text-align: center; vertical-align: center; font-size: 14;'>Select a Form, then choose a Skin</p>");

        html.append("<p style='anchor-left: 16; anchor-top: 76; anchor-width: 260; anchor-height: 24; color: #dbe8f2; text-align: left; vertical-align: center; font-size: 16; font-weight: bold;'>CLASSES</p>");
        int classTop = 108;
        for (FormId formId : FormPresentationRegistry.getOrderedForms()) {
            appendMenuButton(html, resolveClassButtonId(formId), resolveFormLabel(formId), 16, classTop, 260, 42, formId == selectedForm);
            classTop += 48;
        }

        html.append("<p id='").append(SELECTED_CLASS_LABEL_ID).append("' style='anchor-left: 320; anchor-top: 76; anchor-width: 640; anchor-height: 24; color: #dbe8f2; text-align: left; vertical-align: center; font-size: 16; font-weight: bold;'>");
        html.append(selectedForm == null ? "Selected: None" : "Selected: " + escapeHtml(resolveFormLabel(selectedForm)));
        html.append("</p>");
        html.append("<p id='").append(SKINS_HEADER_LABEL_ID).append("' style='anchor-left: 320; anchor-top: 104; anchor-width: 640; anchor-height: 24; color: #b8cad8; text-align: left; vertical-align: center; font-size: 14;'>");
        html.append(selectedForm == null ? "No class selected yet." : escapeHtml(resolveFormLabel(selectedForm)) + " skins:");
        html.append("</p>");
        html.append("<p id='").append(SELECTED_SKIN_LABEL_ID).append("' style='anchor-left: 320; anchor-top: 132; anchor-width: 640; anchor-height: 24; color: #dbe8f2; text-align: left; vertical-align: center; font-size: 14;'>");
        html.append(escapeHtml(resolveSelectedSkinPreferenceLabel(playerUuid, selectedForm)));
        html.append("</p>");

        for (FormId formId : FormPresentationRegistry.getOrderedForms()) {
            boolean activeForm = formId == selectedForm;
            List<SkinId> skins = FormSkinResolver.getAvailableSkinsForForm(formId);
            int skinTop = 170;
            for (int i = 0; i < skins.size(); i++) {
                SkinId skinId = skins.get(i);
                if (!FormSkinResolver.isSkinImplemented(skinId)) {
                    continue;
                }
                appendMenuButton(
                        html,
                        resolveSkinButtonId(formId, i),
                        formatSkinEntry(skinId),
                        320,
                        skinTop,
                        420,
                        40,
                        false,
                        activeForm
                );
                skinTop += 46;
            }
        }

        html.append("<p style='anchor-left: 320; anchor-top: 620; anchor-width: 640; anchor-height: 24; color: #b8cad8; text-align: left; vertical-align: center; font-size: 14;'>Use /shapeshift &lt;class&gt; to transform with the selected skin</p>");
        html.append("<p style='anchor-left: 16; anchor-top: 650; anchor-width: 260; anchor-height: 24; color: #b8cad8; text-align: left; vertical-align: center; font-size: 14;'>Press ESC to Close</p>");
        appendMenuButton(html, CLOSE_BUTTON_ID, "Close", 780, 650, 164, 46, false);
        html.append("</div>");
        html.append("</div>");
        return html.toString();
    }

    private static void appendMenuButton(StringBuilder html, String id, String label, int left, int top, int width, int height, boolean selected) {
        appendMenuButton(html, id, label, left, top, width, height, selected, true);
    }

    private static void appendMenuButton(
            StringBuilder html,
            String id,
            String label,
            int left,
            int top,
            int width,
            int height,
            boolean selected,
            boolean visible
    ) {
        html.append("<button id='")
                .append(escapeHtml(id))
                .append("' class='custom-textbutton' ")
                .append("data-hyui-default-bg='background-color: ")
                .append(selected ? "#2e5b7399" : "#1b3a4c80")
                .append(";' ")
                .append("data-hyui-hovered-bg='background-color: #1b3a4c99;' ")
                .append("data-hyui-pressed-bg='background-color: #1b3a4cb3;' ")
                .append("data-hyui-disabled-bg='background-color: #1b3a4c66;' ")
                .append("data-hyui-default-label-style='color: #dbe8f2; text-align: center; vertical-align: center; font-size: 14; font-weight: bold;' ")
                .append("data-hyui-hovered-label-style='color: #e6f1fb; text-align: center; vertical-align: center; font-size: 14; font-weight: bold;' ")
                .append("data-hyui-pressed-label-style='color: #dbe8f2; text-align: center; vertical-align: center; font-size: 14; font-weight: bold;' ")
                .append("data-hyui-disabled-label-style='color: #9fb2c2; text-align: center; vertical-align: center; font-size: 14; font-weight: bold;' ")
                .append("style='anchor-left: ")
                .append(left)
                .append("; anchor-top: ")
                .append(top)
                .append("; anchor-width: ")
                .append(width)
                .append("; anchor-height: ")
                .append(height)
                .append("; border-radius: 8; border-width: 2; border-color: #c2d2deb8; background-color: ")
                .append(selected ? "#2e5b7399" : "#1b3a4c80")
                .append(visible ? ";" : "; display: none; visibility: hidden;")
                .append(";'>")
                .append("<p>")
                .append(escapeHtml(label))
                .append("</p>")
                .append("</button>");
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

    private static void bindCloseHandler(PageBuilder pageBuilder, UUID playerUuid, String playerName, long sessionId) {
        if (pageBuilder == null || playerUuid == null || playerName == null) {
            return;
        }
        pageBuilder.addEventListener(CLOSE_BUTTON_ID, CustomUIEventBindingType.Activating, (ignored, ctx) -> {
            logRuntime(sessionId, "close-click", "player=" + playerName);
            close(playerUuid);
        });
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

        if (selectedForm == null) {
            context.editById(SELECTED_CLASS_LABEL_ID, LabelBuilder.class, label -> label.withText("Selected: None"));
            context.editById(SKINS_HEADER_LABEL_ID, LabelBuilder.class, label -> label.withText("No class selected yet."));
            context.editById(SELECTED_SKIN_LABEL_ID, LabelBuilder.class,
                    label -> label.withText(resolveSelectedSkinPreferenceLabel(playerUuid, null)));
        } else {
            context.editById(SELECTED_CLASS_LABEL_ID, LabelBuilder.class, label -> label.withText("Selected: " + resolveFormLabel(selectedForm)));
            context.editById(SKINS_HEADER_LABEL_ID, LabelBuilder.class,
                    label -> label.withText(resolveFormLabel(selectedForm) + " skins:"));
            context.editById(SELECTED_SKIN_LABEL_ID, LabelBuilder.class,
                    label -> label.withText(resolveSelectedSkinPreferenceLabel(playerUuid, selectedForm)));
        }

        for (FormId formId : FormPresentationRegistry.getOrderedForms()) {
            boolean activeForm = formId == selectedForm;
            List<SkinId> skins = FormSkinResolver.getAvailableSkinsForForm(formId);
            for (int i = 0; i < skins.size(); i++) {
                if (!FormSkinResolver.isSkinImplemented(skins.get(i))) {
                    continue;
                }
                context.editById(resolveSkinButtonId(formId, i), element -> element.withVisible(activeForm));
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

    private static String resolveSkinButtonId(FormId formId, int index) {
        return SKIN_BUTTON_ID_PREFIX + resolveClassToken(formId) + "-" + index;
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
        String playerName = DruidPlayerCompat.getPlayerName(player);
        if (playerName != null && !playerName.isBlank()) {
            return playerName;
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

    private static String resolveSelectedSkinPreferenceLabel(UUID playerUuid, FormId formId) {
        if (formId == null) {
            return "Skin preference: Select a class";
        }

        SkinId selectedSkin = playerUuid == null ? null : PlayerFormSessionStore.getSelectedSkin(playerUuid, formId);
        if (selectedSkin != null) {
            SkinDefinition definition = SkinRegistry.getDefinition(selectedSkin);
            String name = definition != null ? definition.getDisplayName() : selectedSkin.name();
            return "Skin preference: " + name;
        }

        SkinId defaultSkin = FormSkinResolver.getDefaultSkinForForm(formId);
        if (defaultSkin == null) {
            return "Skin preference: None";
        }
        SkinDefinition definition = SkinRegistry.getDefinition(defaultSkin);
        String name = definition != null ? definition.getDisplayName() : defaultSkin.name();
        return "Skin preference: Default (" + name + ")";
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
