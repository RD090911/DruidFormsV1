package me.druid.v1;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.entity.entities.Player;

import java.util.concurrent.CompletableFuture;

public class WardenLifeSeedCommand extends AbstractCommand {
    public WardenLifeSeedCommand() {
        super("wardenlifeseed", "Warden Life Seed ability stub hook", false);
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    public CompletableFuture<Void> execute(CommandContext context) {
        CommandSender sender = context.sender();
        if (sender instanceof Player player) {
            WardenLifeSeedAbilityHandler.trigger(player);
        }
        return CompletableFuture.completedFuture(null);
    }
}
