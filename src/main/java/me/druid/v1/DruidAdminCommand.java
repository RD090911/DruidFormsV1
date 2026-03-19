package me.druid.v1;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class DruidAdminCommand extends AbstractCommand {
    private final ShapeshiftHandler shapeshiftHandler;
    private final RequiredArg<String> actionArg;
    private final RequiredArg<String> playerArg;

    public DruidAdminCommand(ShapeshiftHandler shapeshiftHandler) {
        super("druid", "Manage druid access", false);
        this.shapeshiftHandler = shapeshiftHandler;
        this.actionArg = this.withRequiredArg("action", "allow|deny|status", ArgTypes.STRING);
        this.playerArg = this.withRequiredArg("player", "player name or UUID", ArgTypes.STRING);
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    public CompletableFuture<Void> execute(CommandContext context) {
        CommandSender sender = context.sender();
        if (!(sender instanceof Player)) {
            sendResponse(sender, "Only players can use /druid.");
            return CompletableFuture.completedFuture(null);
        }

        Player player = (Player) sender;
        DruidPermissions.rememberPlayer(player);
        if (!DruidPermissions.canUseAdmin(player)) {
            DruidPermissions.sendDenied(player);
            return CompletableFuture.completedFuture(null);
        }

        String action = ((String) context.get(this.actionArg)).toLowerCase(Locale.ROOT);
        String targetInput = (String) context.get(this.playerArg);
        if (targetInput == null || targetInput.isEmpty()) {
            sendResponse(player, "Usage: /druid <allow|deny|status> <player>");
            return CompletableFuture.completedFuture(null);
        }

        UUID targetUuid = DruidPermissions.resolvePlayerInput(targetInput);
        if (targetUuid == null) {
            sendResponse(player, "Unable to resolve player '" + targetInput + "'. Use an online name or UUID.");
            return CompletableFuture.completedFuture(null);
        }

        switch (action) {
            case "allow":
                DruidPermissions.setAllow(targetUuid);
                sendResponse(player, "Druid access set to allowed for " + targetUuid + ".");
                break;
            case "deny":
                DruidPermissions.setDeny(targetUuid);
                Player targetPlayer = DruidPermissions.getOnlinePlayer(targetUuid);
                if (targetPlayer != null && ShapeshiftHandler.activeForms.containsKey(targetPlayer.getDisplayName())) {
                    if (targetPlayer.getWorld() != null) {
                        targetPlayer.getWorld().execute(() -> {
                            shapeshiftHandler.restoreHuman(targetPlayer);
                            sendResponse(targetPlayer, "Gaia has withdrawn the Druid's gift.");
                        });
                    } else {
                        shapeshiftHandler.restoreHuman(targetPlayer);
                        sendResponse(targetPlayer, "Gaia has withdrawn the Druid's gift.");
                    }
                }
                sendResponse(player, "Druid access set to denied for " + targetUuid + ".");
                break;
            case "status":
                sendResponse(player, "Druid access for " + targetUuid + ": " + DruidPermissions.getStatus(targetUuid) + ".");
                break;
            default:
                sendResponse(player, "Usage: /druid <allow|deny|status> <player>");
                break;
        }

        return CompletableFuture.completedFuture(null);
    }

    private void sendResponse(CommandSender sender, String text) {
        FormattedMessage component = new FormattedMessage();
        component.rawText = text;
        sender.sendMessage(new Message(component));
    }
}
