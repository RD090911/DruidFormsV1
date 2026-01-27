package me.druid.v1;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
// We import RequiredArg so we can define the field type
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.protocol.FormattedMessage;

import java.util.concurrent.CompletableFuture;

public class ShapeshiftCommand extends AbstractCommand {
    private final ShapeshiftHandler handler;
    // --- FIX 1: Store the argument reference so we can retrieve it later ---
    private final RequiredArg<String> formArg;

    public ShapeshiftCommand(ShapeshiftHandler handler) {
        super("shapeshift", "Shapeshift into an animal", false);
        this.handler = handler;

        // --- FIX 2: Register & Capture the argument ---
        // We use 'withRequiredArg' to register it, and we save the result to 'this.formArg'.
        // This is the "Key" we will use to unlock the value later.
        this.formArg = this.withRequiredArg("formName", "The animal to turn into", ArgTypes.STRING);
    }

    @Override
    public CompletableFuture<Void> execute(CommandContext context) {
        CommandSender sender = context.sender();

        // 1. Check if Player
        if (!(sender instanceof Player)) {
            sendResponse(sender, "Only players can shapeshift!");
            return CompletableFuture.completedFuture(null);
        }

        Player player = (Player) sender;

        // --- FIX 3: Get the Clean Value ---
        // Instead of reading the raw input string (which caused the error),
        // we ask the context for the value of our specific argument.
        String formName = (String) context.get(this.formArg);

        if (formName == null || formName.isEmpty()) {
            sendResponse(sender, "Usage: /shapeshift <form>");
            return CompletableFuture.completedFuture(null);
        }

        // --- Thread Safety ---
        // Move logic to the main thread to prevent "Assert not in thread" crashes.
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

    // --- Helper for sending messages ---
    private void sendResponse(CommandSender sender, String text) {
        FormattedMessage component = new FormattedMessage();
        component.rawText = text;
        sender.sendMessage(new Message(component));
    }
}