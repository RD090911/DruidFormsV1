package me.druid.v1;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.protocol.FormattedMessage;
import me.druid.v1.hud.DruidHyUiAnimalSelectorHud;
import me.druid.v1.hud.DruidHyUiCurrentFormHud;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class ShapeshiftCommand extends AbstractCommand {
    private final ShapeshiftHandler handler;
    private final RequiredArg<String> formArg;

    private static final Set<String> VALID_FORMS = Set.of(
            "bear",
            "ram",
            "duck",
            "shark",
            "hawk",
            "tiger",
            "rabbit",
            "antelope"
    );
    private static final Set<String> MENU_ALIASES = Set.of("menu", "selector", "select", "ui");

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

    private void executeSelectionOnWorldThread(Player player, String formName, String option) {
        String lowerForm = formName.toLowerCase(Locale.ROOT);

        if (lowerForm.equals("hud")) {
            handleHudCommand(player, option);
            return;
        }

        if (MENU_ALIASES.contains(lowerForm)) {
            DruidHyUiAnimalSelectorHud.open(player);
            sendResponse(player, "Animal selector opened. Use /shapeshift <form>.");
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

        if (!VALID_FORMS.contains(lowerForm)) {
            sendResponse(player, "Unknown form '" + formName + "'. Valid: " + String.join(", ", VALID_FORMS) + ", human, menu");
            return;
        }

        boolean success = handler.shapeshift(player, lowerForm);
        if (!success) {
            sendResponse(player, "Hold a Druid Totem (or place it in hotbar slot 1) and try again.");
            return;
        }

        refreshHudVisibility(player);
        DruidPermissions.sendGrantedOnFirstSuccessfulTransform(player);
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
}
