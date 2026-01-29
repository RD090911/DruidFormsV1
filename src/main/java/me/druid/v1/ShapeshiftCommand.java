package me.druid.v1;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.protocol.FormattedMessage;

import java.util.concurrent.CompletableFuture;

public class ShapeshiftCommand extends AbstractCommand {
    private final ShapeshiftHandler handler;
    private final RequiredArg<String> formArg;

    public ShapeshiftCommand(ShapeshiftHandler handler) {
        super("shapeshift", "Shapeshift into an animal", false);
        this.handler = handler;

        // This registers the <formName> argument so you can type /shapeshift bear
        this.formArg = this.withRequiredArg("formName", "The animal to turn into", ArgTypes.STRING);
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

        // Thread Safety: Move model changes to the world thread
        player.getWorld().execute(() -> {
            try {
                handler.shapeshift(player, formName);
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