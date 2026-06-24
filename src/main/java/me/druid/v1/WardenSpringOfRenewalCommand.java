package me.druid.v1;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.concurrent.CompletableFuture;

public class WardenSpringOfRenewalCommand extends AbstractCommand {
    public WardenSpringOfRenewalCommand() {
        super("wardenspringofrenewal", "Warden Spring of Renewal ability hook", false);
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    public CompletableFuture<Void> execute(CommandContext context) {
        CommandSender sender = context.sender();
        if (sender instanceof PlayerRef playerRef) {
            Player player = DruidPermissions.getOnlinePlayer(playerRef.getUuid());
            if (player != null) {
                WardenSpringOfRenewalAbilityHandler.trigger(player);
            }
        }
        return CompletableFuture.completedFuture(null);
    }
}
