package me.druid.v1;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.protocol.FormattedMessage;

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

    public ShapeshiftCommand(ShapeshiftHandler handler) {
        super("shapeshift", "Shapeshift into an animal", false);
        this.handler = handler;
        this.formArg = this.withRequiredArg("formName", "The animal to turn into", ArgTypes.STRING);
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
        String formName = (String) context.get(this.formArg);

        if (formName == null || formName.isEmpty()) {
            sendResponse(sender, "Usage: /shapeshift <form>");
            return CompletableFuture.completedFuture(null);
        }

        player.getWorld().execute(() -> {
            try {
                String lowerForm = formName.toLowerCase(Locale.ROOT);

                if (lowerForm.equals("human") || lowerForm.equals("reset") || lowerForm.equals("none")) {
                    handler.restoreHuman(player);
                } else {
                    if (!DruidPermissions.canTransform(player)) {
                        DruidPermissions.sendDenied(player);
                        return;
                    }
                    if (!VALID_FORMS.contains(lowerForm)) {
                        sendResponse(player, "Unknown form '" + formName + "'. Valid: " + String.join(", ", VALID_FORMS));
                        return;
                    }
                    boolean success = handler.shapeshift(player, lowerForm);
                    if (!success) {
                        sendResponse(player, "Hold a Druid Totem (or place it in hotbar slot 1) and try again.");
                    } else {
                        DruidPermissions.sendGrantedOnFirstSuccessfulTransform(player);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(player, "Error shifting: " + e.getMessage());
            }
        });

        return CompletableFuture.completedFuture(null);
    }

    private void sendResponse(CommandSender sender, String text) {
        FormattedMessage component = new FormattedMessage();
        component.rawText = text;
        sender.sendMessage(new Message(component));
    }
}
