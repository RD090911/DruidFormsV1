package me.druid.v1;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.Message;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;

public class ShapeshiftCommand extends AbstractCommand {
    private final ShapeshiftHandler handler;

    public ShapeshiftCommand(ShapeshiftHandler handler) {
        super("shapeshift", "Shapeshift into an animal", false);
        this.handler = handler;
    }

    @Override
    public CompletableFuture<Void> execute(CommandContext context) {
        // 1. Get Sender
        CommandSender sender = context.sender();

        // 2. Check if Player
        if (!(sender instanceof Player)) {
            sender.sendMessage(Message.raw("Only players can shapeshift!"));
            return CompletableFuture.completedFuture(null);
        }

        Player player = (Player) sender;

        // 3. Get Arguments
        String formName = context.getInputString().trim();

        if (formName.isEmpty()) {
            player.sendMessage(Message.raw("Usage: /shapeshift <form>"));
            return CompletableFuture.completedFuture(null);
        }

        // 4. Trigger Shapeshift
        // FIX: We use getUuid() (confirmed by library) and .toString() (to turn the ID into text)
        ShapeshiftEventData data = new ShapeshiftEventData(player.getUuid().toString(), formName);
        handler.executeShapeshift(data);

        player.sendMessage(Message.raw("Attempting to shapeshift into: " + formName));

        return CompletableFuture.completedFuture(null);
    }
}