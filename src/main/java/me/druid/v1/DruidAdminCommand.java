package me.druid.v1;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

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
        if (!(sender instanceof PlayerRef playerRef)) {
            sendResponse(sender, "Only players can use /druid.");
            return CompletableFuture.completedFuture(null);
        }

        Player player = DruidPermissions.getOnlinePlayer(playerRef.getUuid());
        DruidPermissions.rememberPlayer(player);
        if (!DruidPermissions.canUseAdmin(sender)) {
            DruidPermissions.sendDenied(sender);
            return CompletableFuture.completedFuture(null);
        }

        String action = ((String) context.get(this.actionArg)).toLowerCase(Locale.ROOT);
        String targetInput = (String) context.get(this.playerArg);
        if (targetInput == null || targetInput.isEmpty()) {
            sendResponse(sender, "Usage: /druid <allow|deny|status> <player>");
            return CompletableFuture.completedFuture(null);
        }

        UUID targetUuid = DruidPermissions.resolvePlayerInput(targetInput);
        if (targetUuid == null) {
            sendResponse(sender, "Unable to resolve player '" + targetInput + "'. Use an online name or UUID.");
            return CompletableFuture.completedFuture(null);
        }

        switch (action) {
            case "allow":
                boolean newlyAllowed = !"allowed".equals(DruidPermissions.getStatus(targetUuid));
                DruidPermissions.setAllow(targetUuid);
                Player allowedPlayer = DruidPermissions.getOnlinePlayer(targetUuid);
                if (newlyAllowed && allowedPlayer != null) {
                    sendResponse(allowedPlayer, DruidPermissions.ACCESS_GRANTED_MESSAGE);
                }
                sendResponse(sender, "Druid access set to allowed for " + targetUuid + ". They can enable the HUD with /shapeshift hud on.");
                break;
            case "deny":
                DruidPermissions.setDeny(targetUuid);
                Player targetPlayer = DruidPermissions.getOnlinePlayer(targetUuid);
                if (targetPlayer != null && ShapeshiftHandler.activeForms.containsKey(DruidPlayerCompat.getPlayerName(targetPlayer))) {
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
                sendResponse(sender, "Druid access set to denied for " + targetUuid + ".");
                break;
            case "status":
                sendResponse(sender, "Druid access for " + targetUuid + ": " + DruidPermissions.getStatus(targetUuid) + ".");
                break;
            default:
                sendResponse(sender, "Usage: /druid <allow|deny|status> <player>");
                break;
        }

        return CompletableFuture.completedFuture(null);
    }

    private void sendResponse(CommandSender sender, String text) {
        FormattedMessage component = new FormattedMessage();
        component.rawText = text;
        sender.sendMessage(new Message(component));
    }

    private void sendResponse(Player player, String text) {
        if (player == null) return;
        PlayerRef playerRef = DruidPlayerCompat.getPlayerRef(player);
        if (playerRef == null) return;
        sendResponse(playerRef, text);
    }
}
