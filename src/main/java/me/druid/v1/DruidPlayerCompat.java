package me.druid.v1;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class DruidPlayerCompat {
    private DruidPlayerCompat() {
    }

    public static PlayerRef getPlayerRef(Player player) {
        if (player == null) {
            return null;
        }
        try {
            return player.getPlayerRef();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String getPlayerName(Player player) {
        PlayerRef playerRef = getPlayerRef(player);
        if (playerRef == null) {
            return null;
        }
        try {
            return playerRef.getUsername();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String getPlayerNameOrUnknown(Player player) {
        String username = getPlayerName(player);
        return username == null || username.isBlank() ? "unknown" : username;
    }

    public static TransformComponent getTransformComponent(Player player) {
        if (player == null) {
            return null;
        }
        try {
            Ref<EntityStore> ref = player.getReference();
            if (ref == null || !ref.isValid()) {
                return null;
            }
            Store<EntityStore> store = ref.getStore();
            if (store == null) {
                return null;
            }
            return store.getComponent(ref, TransformComponent.getComponentType());
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void sendMessage(Player player, Message message) {
        if (player == null || message == null) {
            return;
        }
        PlayerRef playerRef = getPlayerRef(player);
        if (playerRef == null) {
            return;
        }
        try {
            playerRef.sendMessage(message);
        } catch (Exception ignored) {
        }
    }

    public static boolean hasPermission(Player player, String permission, boolean defaultValue) {
        if (player == null || permission == null || permission.isEmpty()) {
            return defaultValue;
        }
        PlayerRef playerRef = getPlayerRef(player);
        if (playerRef == null) {
            return defaultValue;
        }
        try {
            return playerRef.hasPermission(permission, defaultValue);
        } catch (Exception ignored) {
        }
        try {
            return playerRef.hasPermission(permission);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }
}
