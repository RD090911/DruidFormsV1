package me.druid.v1;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.protocol.FormattedMessage;
import me.druid.v1.forms.FormId;
import me.druid.v1.forms.FormRegistry;
import me.druid.v1.forms.FormRuntimeBridge;
import me.druid.v1.forms.FormSkinResolver;
import me.druid.v1.forms.PlayerFormSessionStore;
import me.druid.v1.forms.SkinId;
import me.druid.v1.forms.SkinDefinition;
import me.druid.v1.forms.SkinRegistry;
import me.druid.v1.hud.DruidHyUiAnimalSelectorHud;
import me.druid.v1.hud.DruidHyUiCurrentFormHud;
import me.druid.v1.hud.DruidHyUiFormSkinPrototypeHud;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.lang.reflect.Method;

public class ShapeshiftCommand extends AbstractCommand {
    private final ShapeshiftHandler handler;
    private final RequiredArg<String> formArg;

    private static final Set<String> VALID_FORMS = Set.of(
            "bear",
            "ram",
            "shark",
            "hawk",
            "tiger",
            "rabbit",
            "antelope",
            "bluegill"
    );
    private static final Set<String> VALID_FORM_ALIASES = Set.of(
            "guardian",
            "warden",
            "prowler",
            "stalker",
            "travel",
            "flight",
            "springer",
            "forager",
            "aquatic"
    );
    private static final Set<String> MENU_ALIASES = Set.of("selector", "select", "ui");
    private static final Map<String, CommandIdentityAlias> IDENTITY_ALIASES = Map.of(
            "duck", new CommandIdentityAlias("duck", FormId.FORM_FLIGHT, SkinId.SKIN_DUCK)
    );
    private static final Map<String, FormId> FORM_ALIASES = Map.of(
            "guardian", FormId.FORM_GUARDIAN,
            "warden", FormId.FORM_WARDEN,
            "prowler", FormId.FORM_PROWLER,
            "stalker", FormId.FORM_STALKER,
            "travel", FormId.FORM_TRAVEL,
            "flight", FormId.FORM_FLIGHT,
            "springer", FormId.FORM_SPRINGER,
            "forager", FormId.FORM_FORAGER,
            "aquatic", FormId.FORM_AQUATIC
    );

    public ShapeshiftCommand(ShapeshiftHandler handler) {
        super("shapeshift", "Shapeshift into an animal", false);
        this.handler = handler;
        this.formArg = this.withRequiredArg("formName", "The animal to turn into", ArgTypes.STRING);
        this.setAllowsExtraArguments(true);
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    public String getName() {
        return "shapeshift";
    }

    @Override
    public CompletableFuture<Void> execute(CommandContext context) {
        CommandSender sender = context.sender();

        if (!(sender instanceof Player)) {
            sendResponse(sender, "Only players can shapeshift!");
            return CompletableFuture.completedFuture(null);
        }

        Player player = (Player) sender;
        String[] args = extractArgs(context);
        if (args.length == 0) {
            sendHelp(player);
            return CompletableFuture.completedFuture(null);
        }

        String primary = args[0].toLowerCase(Locale.ROOT);
        if (primary.equals("help") || primary.equals("--help") || primary.equals("-h")) {
            sendHelp(player);
            return CompletableFuture.completedFuture(null);
        }

        if (primary.equals("menu")) {
            if (args.length >= 2 && "select".equalsIgnoreCase(args[1])) {
                String classArg = args.length >= 3 ? args[2] : null;
                queueMenuClassSelection(player, classArg);
                return CompletableFuture.completedFuture(null);
            }
            queueSelection(player, "menu", null);
            return CompletableFuture.completedFuture(null);
        }

        if (primary.equals("skin")) {
            String classArg = args.length >= 2 ? args[1] : null;
            String skinArg = args.length >= 3 ? joinArgs(args, 2) : null;
            queueSkinPreferenceSelection(player, classArg, skinArg);
            return CompletableFuture.completedFuture(null);
        }

        if (primary.equals("hud")) {
            String option = args.length >= 2 ? args[1] : null;
            queueSelection(player, "hud", option);
            return CompletableFuture.completedFuture(null);
        }

        String formName = primary;
        if (formName.isBlank()) {
            sendHelp(player);
            return CompletableFuture.completedFuture(null);
        }

        queueSelection(player, formName, null);
        return CompletableFuture.completedFuture(null);
    }

    private void queueSelection(Player player, String formName, String option) {
        if (player == null || formName == null) return;
        player.getWorld().execute(() -> {
            try {
                executeSelectionOnWorldThread(player, formName, option);
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(player, "Error shifting: " + e.getMessage());
            }
        });
    }

    private void queueSkinPreferenceSelection(Player player, String classArg, String skinArg) {
        if (player == null) return;
        player.getWorld().execute(() -> {
            try {
                executeSkinPreferenceSelectionOnWorldThread(player, classArg, skinArg);
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(player, "Error updating skin preference: " + e.getMessage());
            }
        });
    }

    private void queueMenuClassSelection(Player player, String classArg) {
        if (player == null) return;
        player.getWorld().execute(() -> {
            try {
                executeMenuClassSelectionOnWorldThread(player, classArg);
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(player, "Error selecting menu class: " + e.getMessage());
            }
        });
    }

    public static void queueMenuClassSelectionFromUi(Player player, String classArg) {
        if (player == null || classArg == null || classArg.isBlank() || player.getWorld() == null) {
            return;
        }
        player.getWorld().execute(() -> applyMenuClassSelection(player, classArg));
    }

    private void executeSelectionOnWorldThread(Player player, String formName, String option) {
        String lowerForm = formName.toLowerCase(Locale.ROOT);

        if (lowerForm.equals("hud")) {
            handleHudCommand(player, option);
            return;
        }

        if (lowerForm.equals("menu")) {
            DruidHyUiFormSkinPrototypeHud.open(player);
            sendResponse(player, "Form skin menu opened.");
            return;
        }

        if (lowerForm.equals("radial")) {
            openRadialSelector(player);
            return;
        }

        if (MENU_ALIASES.contains(lowerForm)) {
            openRadialSelector(player);
            return;
        }

        if (lowerForm.equals("human") || lowerForm.equals("reset") || lowerForm.equals("none")) {
            handler.restoreHuman(player);
            refreshHudVisibility(player);
            return;
        }

        if (!DruidPermissions.canTransform(player)) {
            DruidPermissions.sendDenied(player);
            return;
        }

        if (!VALID_FORMS.contains(lowerForm) && !VALID_FORM_ALIASES.contains(lowerForm)) {
            sendResponse(player, "Unknown form '" + formName + "'. Valid animals: " + String.join(", ", VALID_FORMS)
                    + ". Valid forms: " + String.join(", ", VALID_FORM_ALIASES) + ", human, menu");
            return;
        }

        ResolvedCommandIdentity resolvedIdentity = resolveCommandIdentity(player, lowerForm);
        if (resolvedIdentity == null || resolvedIdentity.runtimeAnimalKey == null || resolvedIdentity.runtimeAnimalKey.isBlank()) {
            sendResponse(player, "Form '" + formName + "' is not currently available.");
            return;
        }
        String runtimeAnimalKey = resolvedIdentity == null ? lowerForm : resolvedIdentity.runtimeAnimalKey;
        FormId transformedFormId = resolvedIdentity.formId != null
                ? resolvedIdentity.formId
                : FormRuntimeBridge.resolveFormIdForAnimal(runtimeAnimalKey);
        UUID playerUuid = resolveSessionPlayerUuid(player);
        boolean bypassCooldownForHumanRestore = shouldBypassTransformCooldownForHumanRestore(player, transformedFormId, runtimeAnimalKey);
        if (!bypassCooldownForHumanRestore) {
            long remainingCooldownMillis = TransformCooldownService.getRemainingMillis(player);
            if (remainingCooldownMillis > 0L) {
                double remainingCooldownSeconds = remainingCooldownMillis / 1000.0d;
                sendResponse(player, String.format(Locale.ROOT, "Shapeshift recharging: %.1fs", remainingCooldownSeconds));
                System.out.println(String.format(
                        Locale.ROOT,
                        "[Druid] Transform cooldown blocked player=%s remainingMillis=%d remainingSeconds=%.1f",
                        safeDisplayName(player),
                        remainingCooldownMillis,
                        remainingCooldownSeconds
                ));
                return;
            }
        }

        boolean success = handler.shapeshift(player, runtimeAnimalKey, transformedFormId);
        if (!success) {
            sendResponse(player, "Hold a Druid Totem (or place it in hotbar slot 1) and try again.");
            return;
        }

        if (playerUuid != null) {
            SkinId transformedSkinId = resolvedIdentity.skinId != null
                    ? resolvedIdentity.skinId
                    : SkinRegistry.getSkinForAnimal(runtimeAnimalKey);
            if (transformedFormId == null && transformedSkinId != null) {
                transformedFormId = SkinRegistry.getFormForSkin(transformedSkinId);
            }
            if (transformedFormId != null) {
                PlayerFormSessionStore.setSelectedForm(playerUuid, transformedFormId);
                if (transformedSkinId != null) {
                    PlayerFormSessionStore.setSelectedSkin(playerUuid, transformedFormId, transformedSkinId);
                }
            }
            DruidHyUiFormSkinPrototypeHud.refreshFromSession(playerUuid);
        }

        refreshHudVisibility(player);
        FormId activeFormAfterTransform = ShapeshiftHandler.getActiveFormId(player);
        if (activeFormAfterTransform != null) {
            TransformCooldownService.startCooldown(player);
            DruidHyUiClassCooldownOverlayHud.startOrRestart(player);
            System.out.println(String.format(
                    Locale.ROOT,
                    "[Druid] Transform cooldown started player=%s activeForm=%s cooldownEndMillis=%d",
                    safeDisplayName(player),
                    activeFormAfterTransform.name(),
                    System.currentTimeMillis() + TransformCooldownService.getRemainingMillis(player)
            ));
        } else {
            System.out.println(String.format(
                    Locale.ROOT,
                    "[Druid] Transform cooldown not started for human restore player=%s",
                    safeDisplayName(player)
            ));
        }
        DruidPermissions.sendGrantedOnFirstSuccessfulTransform(player);
    }

    private void executeSkinPreferenceSelectionOnWorldThread(Player player, String classArg, String skinArg) {
        if (classArg == null || skinArg == null) {
            DruidHyUiFormSkinPrototypeHud.open(player);
            sendResponse(player, "Usage: /shapeshift skin {class} {skin}");
            return;
        }

        FormId formId = FORM_ALIASES.get(classArg.toLowerCase(Locale.ROOT));
        if (formId == null) {
            sendResponse(player, "Unknown class '" + classArg + "'. Valid classes: " + String.join(", ", VALID_FORM_ALIASES));
            return;
        }

        SkinId skinId = resolveSkinIdForForm(formId, skinArg);
        if (skinId == null) {
            sendResponse(player, "Unknown skin '" + skinArg + "' for " + resolveFormDisplayName(formId)
                    + ". Valid skins: " + String.join(", ", resolveSkinDisplayNames(formId)));
            return;
        }
        if (!FormSkinResolver.isSkinImplemented(skinId)) {
            sendResponse(player, "That skin is not available yet.");
            return;
        }

        UUID playerUuid = resolveSessionPlayerUuid(player);
        boolean applied = PlayerFormSessionStore.setSelectedSkin(playerUuid, formId, skinId);
        if (!applied) {
            sendResponse(player, "Unable to set skin preference for " + resolveFormDisplayName(formId) + ".");
            return;
        }

        sendResponse(player, resolveFormDisplayName(formId) + " skin preference set to "
                + resolveSkinDisplayName(skinId) + ". Use /shapeshift " + classArg.toLowerCase(Locale.ROOT) + ".");
    }

    private void executeMenuClassSelectionOnWorldThread(Player player, String classArg) {
        MenuClassSelectionResult result = applyMenuClassSelection(player, classArg);
        if (result.status == MenuClassSelectionStatus.USAGE) {
            sendResponse(player, "Usage: /shapeshift menu select {class}");
            return;
        }
        if (result.status == MenuClassSelectionStatus.UNKNOWN_CLASS) {
            sendResponse(player, "Unknown class '" + classArg + "'. Valid classes: " + String.join(", ", VALID_FORM_ALIASES));
            return;
        }
        if (result.formId != null) {
            sendResponse(player, "Menu selected class set to " + resolveFormDisplayName(result.formId) + ".");
        }
    }

    private static MenuClassSelectionResult applyMenuClassSelection(Player player, String classArg) {
        if (player == null || classArg == null || classArg.isBlank()) {
            if (player != null) {
                DruidHyUiFormSkinPrototypeHud.open(player);
            }
            return new MenuClassSelectionResult(MenuClassSelectionStatus.USAGE, null);
        }

        FormId formId = FORM_ALIASES.get(classArg.toLowerCase(Locale.ROOT));
        if (formId == null) {
            return new MenuClassSelectionResult(MenuClassSelectionStatus.UNKNOWN_CLASS, null);
        }

        UUID playerUuid = resolveSessionPlayerUuid(player);
        PlayerFormSessionStore.setSelectedForm(playerUuid, formId);
        DruidHyUiFormSkinPrototypeHud.open(player);
        return new MenuClassSelectionResult(MenuClassSelectionStatus.SUCCESS, formId);
    }

    private void handleHudCommand(Player player, String option) {
        String normalized = option == null ? "" : option.toLowerCase(Locale.ROOT);
        if (!normalized.equals("on") && !normalized.equals("off")) {
            sendHelp(player);
            return;
        }

        boolean enabled = normalized.equals("on");
        DruidPermissions.setHudToggleEnabled(player, enabled);
        refreshHudVisibility(player);

        if (enabled && !DruidPermissions.canUseDruidFeatures(player)) {
            sendResponse(player, "Druid HUD toggle is on, but you do not currently have druid access.");
            return;
        }
        sendResponse(player, "Druid HUD " + (enabled ? "enabled." : "disabled."));
    }

    private void openRadialSelector(Player player) {
        if (player == null) {
            return;
        }
        if (!DruidPermissions.canTransform(player)) {
            DruidPermissions.sendDenied(player);
            return;
        }

        int activeSlot = resolveActiveHotbarSlot(player);
        boolean isSlot1Selected = activeSlot == 0 || activeSlot == 1;
        String heldItemId = resolveHeldItemId(player);
        boolean isDruidControlItem = DruidControlItemMatcher.matches(handler, heldItemId);

        if (!isSlot1Selected || !isDruidControlItem) {
            sendResponse(player, "Hold a druid control item in hotbar slot 1 and use secondary interaction.");
            return;
        }

        DruidHyUiAnimalSelectorHud.open(player);
        sendResponse(player, "Class radial opened.");
    }

    private String[] extractArgs(CommandContext context) {
        if (context == null) return new String[0];
        String raw = context.getInputString();
        if (raw == null || raw.isBlank()) return new String[0];

        String[] split = raw.trim().split("\\s+");
        if (split.length == 0) return new String[0];

        if (split[0].startsWith("/")) {
            split[0] = split[0].substring(1);
        }
        if (split.length > 0 && split[0].equalsIgnoreCase(getName())) {
            String[] trimmed = new String[split.length - 1];
            System.arraycopy(split, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return split;
    }

    private void sendHelp(CommandSender sender) {
        sendResponse(sender, "Shapeshift commands:");
        sendResponse(sender, "/shapeshift <formName>");
        sendResponse(sender, "/shapeshift menu");
        sendResponse(sender, "/shapeshift menu select {class}");
        sendResponse(sender, "/shapeshift skin {class} {skin}");
        sendResponse(sender, "/shapeshift hud on");
        sendResponse(sender, "/shapeshift hud off");
        sendResponse(sender, "/shapeshift help");
        sendResponse(sender, "Admin commands (requires druidforms.admin):");
        sendResponse(sender, "/druid allow <player>");
        sendResponse(sender, "/druid deny <player>");
        sendResponse(sender, "/druid status <player>");
    }

    private void refreshHudVisibility(Player player) {
        if (DruidPermissions.shouldShowHud(player)) {
            DruidHyUiCurrentFormHud.attachOrRefresh(player);
            return;
        }
        if (player != null && player.getUuid() != null) {
            DruidHyUiCurrentFormHud.detach(player.getUuid());
        }
    }

    private void sendResponse(CommandSender sender, String text) {
        FormattedMessage component = new FormattedMessage();
        component.rawText = text;
        sender.sendMessage(new Message(component));
    }

    private ResolvedCommandIdentity resolveCommandIdentity(Player player, String commandAnimalKey) {
        if (commandAnimalKey == null || commandAnimalKey.isBlank()) {
            return null;
        }

        String normalized = commandAnimalKey.toLowerCase(Locale.ROOT);
        String runtimeAnimalKey = normalized;
        FormId formId = null;
        SkinId skinId = null;

        FormId formAlias = FORM_ALIASES.get(normalized);
        if (formAlias != null) {
            formId = formAlias;
            UUID playerUuid = resolveSessionPlayerUuid(player);
            SkinId selectedSkin = playerUuid == null ? null : PlayerFormSessionStore.getSelectedSkin(playerUuid, formAlias);
            runtimeAnimalKey = FormSkinResolver.resolvePreferredAnimalKey(formAlias, selectedSkin);
            if (runtimeAnimalKey == null || runtimeAnimalKey.isBlank()) {
                runtimeAnimalKey = FormRuntimeBridge.resolveAnimalKeyForForm(formAlias);
            }
            if (runtimeAnimalKey == null || runtimeAnimalKey.isBlank()) {
                return new ResolvedCommandIdentity(null, formAlias, null);
            }
            skinId = SkinRegistry.getSkinForAnimal(runtimeAnimalKey);
            return new ResolvedCommandIdentity(runtimeAnimalKey, formId, skinId);
        }

        CommandIdentityAlias alias = IDENTITY_ALIASES.get(normalized);
        if (alias != null) {
            runtimeAnimalKey = alias.runtimeAnimalKey;
        }

        formId = FormRuntimeBridge.resolveFormIdForAnimal(runtimeAnimalKey);
        if (formId == null) {
            formId = FormRegistry.getFormForAnimal(runtimeAnimalKey);
        }

        if (formId != null) {
            UUID playerUuid = resolveSessionPlayerUuid(player);
            SkinId selectedSkin = playerUuid == null ? null : PlayerFormSessionStore.getSelectedSkin(playerUuid, formId);
            String preferredAnimalKey = FormSkinResolver.resolvePreferredAnimalKey(formId, selectedSkin);
            if (preferredAnimalKey != null && !preferredAnimalKey.isBlank()) {
                runtimeAnimalKey = preferredAnimalKey;
            }
        }

        skinId = SkinRegistry.getSkinForAnimal(runtimeAnimalKey);

        // Keep full backward compatibility while normalizing command identity semantics.
        if (alias != null) {
            if (formId == null) formId = alias.expectedFormId;
            if (skinId == null) skinId = alias.expectedSkinId;
        }

        return new ResolvedCommandIdentity(runtimeAnimalKey, formId, skinId);
    }

    private static String resolveFormDisplayName(FormId formId) {
        if (formId == null) {
            return "form";
        }
        if (FormRegistry.getDefinition(formId) != null && FormRegistry.getDefinition(formId).getDisplayName() != null) {
            return FormRegistry.getDefinition(formId).getDisplayName();
        }
        return formId.name();
    }

    private SkinId resolveSkinIdForForm(FormId formId, String rawSkinInput) {
        if (formId == null || rawSkinInput == null || rawSkinInput.isBlank()) {
            return null;
        }

        String normalizedInput = normalizeSkinToken(rawSkinInput);
        if (normalizedInput == null || normalizedInput.isBlank()) {
            return null;
        }

        List<SkinId> skins = FormSkinResolver.getAvailableSkinsForForm(formId);
        for (SkinId skinId : skins) {
            for (String candidate : resolveSkinMatchTokens(skinId)) {
                if (normalizedInput.equals(candidate)) {
                    return skinId;
                }
            }
        }
        return null;
    }

    private List<String> resolveSkinDisplayNames(FormId formId) {
        List<String> names = new ArrayList<>();
        if (formId == null) {
            return names;
        }
        for (SkinId skinId : FormSkinResolver.getAvailableSkinsForForm(formId)) {
            names.add(resolveSkinDisplayName(skinId));
        }
        return names;
    }

    private String resolveSkinDisplayName(SkinId skinId) {
        if (skinId == null) {
            return "skin";
        }
        SkinDefinition definition = SkinRegistry.getDefinition(skinId);
        if (definition != null && definition.getDisplayName() != null && !definition.getDisplayName().isBlank()) {
            return definition.getDisplayName();
        }
        return skinId.name();
    }

    private List<String> resolveSkinMatchTokens(SkinId skinId) {
        List<String> tokens = new ArrayList<>();
        if (skinId == null) {
            return tokens;
        }

        SkinDefinition definition = SkinRegistry.getDefinition(skinId);
        if (definition != null) {
            addToken(tokens, definition.getDisplayName());
            addToken(tokens, definition.getBackingAnimal());

            String display = definition.getDisplayName();
            if (display != null && !display.isBlank()) {
                String[] split = display.split("[/(),]");
                for (String part : split) {
                    addToken(tokens, part);
                }
            }
        }

        addToken(tokens, skinId.name().replace("SKIN_", ""));
        return tokens;
    }

    private void addToken(List<String> tokens, String value) {
        String normalized = normalizeSkinToken(value);
        if (normalized == null || normalized.isBlank()) {
            return;
        }
        if (!tokens.contains(normalized)) {
            tokens.add(normalized);
        }
    }

    private String normalizeSkinToken(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        if (normalized.isBlank()) {
            return null;
        }
        return normalized;
    }

    private String joinArgs(String[] args, int startIndex) {
        if (args == null || startIndex >= args.length) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i < args.length; i++) {
            if (args[i] == null || args[i].isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static UUID resolveSessionPlayerUuid(Player player) {
        if (player == null) return null;
        try {
            Method getPlayerRef = player.getClass().getMethod("getPlayerRef");
            Object value = getPlayerRef.invoke(player);
            if (value instanceof PlayerRef playerRef && playerRef.getUuid() != null) {
                return playerRef.getUuid();
            }
        } catch (Exception ignored) {
        }
        try {
            return player.getUuid();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean shouldBypassTransformCooldownForHumanRestore(Player player, FormId transformedFormId, String runtimeAnimalKey) {
        if (player == null) {
            return false;
        }
        FormId activeFormId = ShapeshiftHandler.getActiveFormId(player);
        if (activeFormId == null) {
            return false;
        }

        FormId targetFormId = transformedFormId;
        if (targetFormId == null && runtimeAnimalKey != null && !runtimeAnimalKey.isBlank()) {
            targetFormId = FormRuntimeBridge.resolveFormIdForAnimal(runtimeAnimalKey);
        }
        return targetFormId != null && targetFormId == activeFormId;
    }

    private static String safeDisplayName(Player player) {
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
        return "unknown";
    }

    private int resolveActiveHotbarSlot(Player player) {
        if (player == null) return -1;
        try {
            Object inventory = player.getClass().getMethod("getInventory").invoke(player);
            if (inventory == null) return -1;
            Method getActiveSlot = inventory.getClass().getMethod("getActiveHotbarSlot");
            Object value = getActiveSlot.invoke(inventory);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private String resolveHeldItemId(Player player) {
        if (player == null) return null;
        try {
            Object inventory = player.getClass().getMethod("getInventory").invoke(player);
            if (inventory == null) return null;
            Object held = inventory.getClass().getMethod("getItemInHand").invoke(inventory);
            if (held == null) return null;
            Method getItemId = held.getClass().getMethod("getItemId");
            Object itemId = getItemId.invoke(held);
            return itemId instanceof String ? (String) itemId : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class CommandIdentityAlias {
        private final String runtimeAnimalKey;
        private final FormId expectedFormId;
        private final SkinId expectedSkinId;

        private CommandIdentityAlias(String runtimeAnimalKey, FormId expectedFormId, SkinId expectedSkinId) {
            this.runtimeAnimalKey = runtimeAnimalKey;
            this.expectedFormId = expectedFormId;
            this.expectedSkinId = expectedSkinId;
        }
    }

    private static final class ResolvedCommandIdentity {
        private final String runtimeAnimalKey;
        private final FormId formId;
        private final SkinId skinId;

        private ResolvedCommandIdentity(String runtimeAnimalKey, FormId formId, SkinId skinId) {
            this.runtimeAnimalKey = runtimeAnimalKey;
            this.formId = formId;
            this.skinId = skinId;
        }
    }

    private enum MenuClassSelectionStatus {
        SUCCESS,
        USAGE,
        UNKNOWN_CLASS
    }

    private static final class MenuClassSelectionResult {
        private final MenuClassSelectionStatus status;
        private final FormId formId;

        private MenuClassSelectionResult(MenuClassSelectionStatus status, FormId formId) {
            this.status = status;
            this.formId = formId;
        }
    }
}
