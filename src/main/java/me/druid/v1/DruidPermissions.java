package me.druid.v1;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.entity.entities.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class DruidPermissions {
    static final String USE = "druidforms.use";
    static final String TRANSFORM = "druidforms.transform";
    static final String SHRINE = "druidforms.shrine";
    static final String ADMIN = "druidforms.admin";
    static final String ACCESS_DENIED_MESSAGE = "Gaia has not blessed you with the Druid's gift.";
    static final String ACCESS_GRANTED_MESSAGE = "Gaia has blessed you with the Druid's gift.";

    private static final Set<UUID> grantedTransformAck = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, AccessOverride> accessOverrides = new ConcurrentHashMap<>();
    private static final Map<String, UUID> knownPlayersByName = new ConcurrentHashMap<>();
    private static final Map<UUID, Player> onlinePlayersByUuid = new ConcurrentHashMap<>();
    private static Path storagePath;
    private static volatile boolean loaded;

    private enum AccessOverride {
        ALLOW,
        DENY
    }

    private DruidPermissions() {
    }

    static void initializeStorage(Path dataDirectory) {
        if (dataDirectory == null) return;
        storagePath = dataDirectory.resolve("druid-access.properties");
        ensureLoaded();
    }

    static boolean canTransform(Player player) {
        rememberPlayer(player);
        AccessOverride accessOverride = getOverride(player);
        if (accessOverride == AccessOverride.DENY) return false;
        if (accessOverride == AccessOverride.ALLOW) return true;
        return isAdmin(player) || (hasPermission(player, USE) && hasPermission(player, TRANSFORM));
    }

    static boolean canUseShrine(Player player) {
        rememberPlayer(player);
        AccessOverride accessOverride = getOverride(player);
        if (accessOverride == AccessOverride.DENY) return false;
        if (accessOverride == AccessOverride.ALLOW) return true;
        return isAdmin(player) || hasPermission(player, SHRINE);
    }

    static boolean isDruidShrineBlock(String blockId) {
        if (blockId == null || blockId.isEmpty()) return false;
        String normalized = blockId.toLowerCase(Locale.ROOT);
        int namespaceSeparator = normalized.lastIndexOf(':');
        if (namespaceSeparator >= 0 && namespaceSeparator + 1 < normalized.length()) {
            normalized = normalized.substring(namespaceSeparator + 1);
        }
        return normalized.equals("druid_shrine");
    }

    static void sendDenied(CommandSender sender) {
        FormattedMessage component = new FormattedMessage();
        component.rawText = ACCESS_DENIED_MESSAGE;
        sender.sendMessage(new Message(component));
    }

    static void sendGrantedOnFirstSuccessfulTransform(Player player) {
        if (player == null) return;
        UUID playerId = player.getUuid();
        if (playerId == null) return;
        if (!grantedTransformAck.add(playerId)) return;

        FormattedMessage component = new FormattedMessage();
        component.rawText = ACCESS_GRANTED_MESSAGE;
        player.sendMessage(new Message(component));
    }

    static boolean canUseAdmin(Player player) {
        return isAdmin(player);
    }

    static UUID resolvePlayerInput(String input) {
        if (input == null || input.isEmpty()) return null;
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {
        }
        ensureLoaded();
        return knownPlayersByName.get(input.toLowerCase(Locale.ROOT));
    }

    static void setAllow(UUID uuid) {
        if (uuid == null) return;
        ensureLoaded();
        accessOverrides.put(uuid, AccessOverride.ALLOW);
        save();
    }

    static void setDeny(UUID uuid) {
        if (uuid == null) return;
        ensureLoaded();
        accessOverrides.put(uuid, AccessOverride.DENY);
        save();
    }

    static String getStatus(UUID uuid) {
        if (uuid == null) return "unknown";
        ensureLoaded();
        AccessOverride accessOverride = accessOverrides.get(uuid);
        if (accessOverride == AccessOverride.ALLOW) return "allowed";
        if (accessOverride == AccessOverride.DENY) return "denied";
        return "default";
    }

    static void rememberPlayer(Player player) {
        if (player == null) return;
        try {
            UUID playerId = player.getUuid();
            String lowerName = player.getDisplayName() == null ? null : player.getDisplayName().toLowerCase(Locale.ROOT);
            if (playerId == null || lowerName == null || lowerName.isEmpty()) return;
            ensureLoaded();
            onlinePlayersByUuid.put(playerId, player);
            UUID previous = knownPlayersByName.put(lowerName, playerId);
            if (!playerId.equals(previous)) {
                save();
            }
        } catch (Exception ignored) {
        }
    }

    static Player getOnlinePlayer(UUID uuid) {
        if (uuid == null) return null;
        return onlinePlayersByUuid.get(uuid);
    }

    static void forgetPlayer(UUID uuid) {
        if (uuid == null) return;
        onlinePlayersByUuid.remove(uuid);
    }

    private static boolean isAdmin(Player player) {
        if (player == null) return false;
        try {
            return player.hasPermission(ADMIN, false);
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean hasPermission(Player player, String node) {
        if (player == null || node == null || node.isEmpty()) return true;
        try {
            return player.hasPermission(node, true);
        } catch (Exception ignored) {
        }
        try {
            return player.hasPermission(node);
        } catch (Exception ignored) {
        }
        return true;
    }

    private static AccessOverride getOverride(Player player) {
        if (player == null) return null;
        try {
            UUID playerId = player.getUuid();
            if (playerId == null) return null;
            ensureLoaded();
            return accessOverrides.get(playerId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (storagePath == null || !Files.exists(storagePath)) return;

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(storagePath)) {
            properties.load(input);
        } catch (IOException ignored) {
            return;
        }

        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            if (value == null) continue;
            if (key.startsWith("override.")) {
                String rawUuid = key.substring("override.".length());
                try {
                    UUID uuid = UUID.fromString(rawUuid);
                    String normalized = value.trim().toLowerCase(Locale.ROOT);
                    if ("allow".equals(normalized)) {
                        accessOverrides.put(uuid, AccessOverride.ALLOW);
                    } else if ("deny".equals(normalized)) {
                        accessOverrides.put(uuid, AccessOverride.DENY);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            } else if (key.startsWith("name.")) {
                String name = key.substring("name.".length()).toLowerCase(Locale.ROOT);
                try {
                    knownPlayersByName.put(name, UUID.fromString(value.trim()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private static synchronized void save() {
        if (storagePath == null) return;
        Properties properties = new Properties();
        for (Map.Entry<UUID, AccessOverride> entry : accessOverrides.entrySet()) {
            properties.setProperty("override." + entry.getKey(), entry.getValue() == AccessOverride.ALLOW ? "allow" : "deny");
        }
        for (Map.Entry<String, UUID> entry : knownPlayersByName.entrySet()) {
            properties.setProperty("name." + entry.getKey(), entry.getValue().toString());
        }

        try {
            Files.createDirectories(storagePath.getParent());
            try (OutputStream output = Files.newOutputStream(storagePath)) {
                properties.store(output, "DruidForms access overrides");
            }
        } catch (IOException ignored) {
        }
    }
}
