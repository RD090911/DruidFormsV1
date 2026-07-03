package me.druid.v1;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.DynamicLight;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.druid.v1.forms.FormId;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class RootlightSpiritLightService {
    private static final byte LIGHT_RADIUS = 2;
    private static final byte LIGHT_RED = (byte) 0x6f;
    private static final byte LIGHT_GREEN = (byte) 0x8f;
    private static final byte LIGHT_BLUE = (byte) 0x45;
    private static final long WATCHDOG_INTERVAL_MILLIS = 2_000L;
    private static final String ROOTLIGHT_ACTIVATE_SOUND_EVENT_ID = "SFX_Spirit_Root_Alerted";

    private static final Set<UUID> ACTIVE_PLAYERS = ConcurrentHashMap.newKeySet();
    private static final Object WATCHDOG_LOCK = new Object();
    private static ScheduledFuture<?> watchdog;

    private RootlightSpiritLightService() {
    }

    static void toggle(Player player) {
        if (player == null) {
            return;
        }

        World world = player.getWorld();
        if (world == null) {
            return;
        }

        world.execute(() -> toggleOnWorldThread(player));
    }

    static void handleDisconnect(PlayerRef playerRef) {
        if (playerRef == null) {
            return;
        }

        UUID playerId = playerRef.getUuid();
        if (playerId == null || !ACTIVE_PLAYERS.remove(playerId)) {
            return;
        }

        removeLight(playerRef.getReference());
        stopWatchdogIfIdle();
    }

    static void shutdown() {
        ArrayList<UUID> activePlayerIds = new ArrayList<>(ACTIVE_PLAYERS);
        ACTIVE_PLAYERS.clear();
        stopWatchdog();

        for (UUID playerId : activePlayerIds) {
            Player player = DruidPermissions.getOnlinePlayer(playerId);
            if (player != null) {
                removeLight(player.getReference());
            }
        }
    }

    private static void toggleOnWorldThread(Player player) {
        UUID playerId = player.getUuid();
        if (playerId == null) {
            return;
        }

        if (ShapeshiftHandler.getActiveFormId(player) != FormId.FORM_WARDEN) {
            if (ACTIVE_PLAYERS.remove(playerId)) {
                removeLightOnWorldThread(player);
                stopWatchdogIfIdle();
            }
            sendPlayerMessage(player, "Rootlight Spirit requires Warden form.");
            return;
        }

        if (isDead(player)) {
            if (ACTIVE_PLAYERS.remove(playerId)) {
                removeLightOnWorldThread(player);
                stopWatchdogIfIdle();
            }
            return;
        }

        if (ACTIVE_PLAYERS.remove(playerId)) {
            removeLightOnWorldThread(player);
            stopWatchdogIfIdle();
            sendPlayerMessage(player, "Rootlight Spirit fades.");
            System.out.println("[RootlightSpirit] disabled owner="
                    + DruidPlayerCompat.getPlayerNameOrUnknown(player));
            return;
        }

        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        Store<EntityStore> store = playerRef.getStore();
        if (store == null) {
            return;
        }

        ColorLight colorLight = new ColorLight(LIGHT_RADIUS, LIGHT_RED, LIGHT_GREEN, LIGHT_BLUE);
        store.putComponent(playerRef, DynamicLight.getComponentType(), new DynamicLight(colorLight));
        ACTIVE_PLAYERS.add(playerId);
        ensureWatchdogRunning();
        DruidSoundFeedback.playAtPlayer(player, ROOTLIGHT_ACTIVATE_SOUND_EVENT_ID);
        sendPlayerMessage(player, "Rootlight Spirit glows around you.");
        System.out.println("[RootlightSpirit] enabled owner="
                + DruidPlayerCompat.getPlayerNameOrUnknown(player)
                + " radius=" + Byte.toUnsignedInt(LIGHT_RADIUS)
                + " color=#6f8f45");
    }

    private static void runWatchdog() {
        if (ACTIVE_PLAYERS.isEmpty()) {
            stopWatchdogIfIdle();
            return;
        }

        for (UUID playerId : new ArrayList<>(ACTIVE_PLAYERS)) {
            Player player = DruidPermissions.getOnlinePlayer(playerId);
            if (player == null) {
                ACTIVE_PLAYERS.remove(playerId);
                continue;
            }

            World world = player.getWorld();
            if (world == null) {
                ACTIVE_PLAYERS.remove(playerId);
                removeLight(player.getReference());
                continue;
            }

            world.execute(() -> validateOnWorldThread(playerId, player));
        }

        stopWatchdogIfIdle();
    }

    private static void validateOnWorldThread(UUID playerId, Player player) {
        if (!ACTIVE_PLAYERS.contains(playerId)) {
            return;
        }

        Ref<EntityStore> playerRef = player.getReference();
        boolean invalid = playerRef == null
                || !playerRef.isValid()
                || ShapeshiftHandler.getActiveFormId(player) != FormId.FORM_WARDEN
                || isDead(player);
        if (!invalid) {
            return;
        }

        ACTIVE_PLAYERS.remove(playerId);
        removeLightOnWorldThread(player);
        stopWatchdogIfIdle();
        System.out.println("[RootlightSpirit] watchdog cleanup owner="
                + DruidPlayerCompat.getPlayerNameOrUnknown(player));
    }

    private static boolean isDead(Player player) {
        Ref<EntityStore> playerRef = player == null ? null : player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return true;
        }

        Store<EntityStore> store = playerRef.getStore();
        return store == null
                || store.getComponent(playerRef, DeathComponent.getComponentType()) != null;
    }

    private static void removeLightOnWorldThread(Player player) {
        if (player != null) {
            removeLightOnWorldThread(player.getReference());
        }
    }

    private static void removeLight(Ref<EntityStore> playerRef) {
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        Store<EntityStore> store = playerRef.getStore();
        if (store == null) {
            return;
        }

        EntityStore entityStore = store.getExternalData();
        World world = entityStore == null ? null : entityStore.getWorld();
        if (store.isInThread()) {
            removeLightOnWorldThread(playerRef);
            return;
        }

        if (world == null) {
            return;
        }

        world.execute(() -> removeLightOnWorldThread(playerRef));
    }

    private static void removeLightOnWorldThread(Ref<EntityStore> playerRef) {
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        Store<EntityStore> store = playerRef.getStore();
        if (store != null) {
            store.removeComponentIfExists(playerRef, DynamicLight.getComponentType());
        }
    }

    private static void ensureWatchdogRunning() {
        synchronized (WATCHDOG_LOCK) {
            if (watchdog != null && !watchdog.isDone()) {
                return;
            }
            watchdog = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
                    RootlightSpiritLightService::runWatchdog,
                    WATCHDOG_INTERVAL_MILLIS,
                    WATCHDOG_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private static void stopWatchdogIfIdle() {
        if (!ACTIVE_PLAYERS.isEmpty()) {
            return;
        }
        stopWatchdog();
    }

    private static void stopWatchdog() {
        synchronized (WATCHDOG_LOCK) {
            if (watchdog != null) {
                watchdog.cancel(false);
                watchdog = null;
            }
        }
    }

    private static void sendPlayerMessage(Player player, String text) {
        if (player == null || text == null) {
            return;
        }
        FormattedMessage component = new FormattedMessage();
        component.rawText = text;
        DruidPlayerCompat.sendMessage(player, new Message(component));
    }
}
