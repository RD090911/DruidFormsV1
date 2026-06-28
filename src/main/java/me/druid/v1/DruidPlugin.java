package me.druid.v1;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerCraftEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.player.DamageInfo;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;
import me.druid.v1.hud.DruidHyUiCurrentFormHud;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class DruidPlugin extends JavaPlugin {

    private static final long HUD_ATTACH_DELAY_MILLIS = 1200L;
    private static final boolean OAKENSHIELD_DAMAGE_DEBUG =
            Boolean.getBoolean("druid.oakenshield.debugDamage");
    private ShapeshiftHandler shapeshiftHandler;
    private volatile boolean packetBridgeDisabledByGuard;
    private volatile boolean packetBridgeRegistered;

    public DruidPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        System.out.println("[DruidPlugin] Initializing V1 (Command Mode)...");

        this.shapeshiftHandler = new ShapeshiftHandler();
        DruidPermissions.initializeStorage(getDataDirectory());

        try {
            Object cmdManager = HytaleServer.get().getCommandManager();
            Object command = new ShapeshiftCommand(shapeshiftHandler);
            Object druidCommand = new DruidAdminCommand(shapeshiftHandler);
            Object wardenLifeSeedCommand = new WardenLifeSeedCommand();
            Object wardenGaiasTouchCommand = new WardenGaiasTouchCommand();
            Object wardenSpringOfRenewalCommand = new WardenSpringOfRenewalCommand();
            Object wardenNaturesResurgenceCommand = new WardenNaturesResurgenceCommand();
            Object rootlightSpiritCommand = new RootlightSpiritCommand();
            Object rootlightSpiritCompanionCommand = new RootlightSpiritCompanionCommand();
            Object legacyRootlightSpiritCompanionCommand = new RootlightSpiritCompanionCommand("wardenrootspirit");
            for (Method m : cmdManager.getClass().getMethods()) {
                if (m.getName().equals("register") && m.getParameterCount() == 1) {
                    m.invoke(cmdManager, command);
                    m.invoke(cmdManager, druidCommand);
                    m.invoke(cmdManager, wardenLifeSeedCommand);
                    m.invoke(cmdManager, wardenGaiasTouchCommand);
                    m.invoke(cmdManager, wardenSpringOfRenewalCommand);
                    m.invoke(cmdManager, wardenNaturesResurgenceCommand);
                    m.invoke(cmdManager, rootlightSpiritCommand);
                    m.invoke(cmdManager, rootlightSpiritCompanionCommand);
                    m.invoke(cmdManager, legacyRootlightSpiritCompanionCommand);
                    System.out.println("[DruidPlugin] Command /shapeshift successfully registered.");
                    break;
                }
            }
        } catch (Exception e) {
            System.out.println("[DruidPlugin] Error registering command: " + e.getMessage());
        }

        try {
            HytaleServer.get().getEventBus().register(PlayerConnectEvent.class, this::handleAuditedPlayerConnect);
            HytaleServer.get().getEventBus().registerGlobal(PlayerCraftEvent.class, this::handlePlayerCraftPersistence);
            HytaleServer.get().getEventBus().registerGlobal(PlayerReadyEvent.class, this::handleAuditedPlayerReady);
            HytaleServer.get().getEventBus().register(PlayerDisconnectEvent.class, this::handleAuditedPlayerDisconnect);
            HytaleServer.get().getEventBus().register(PlayerMouseButtonEvent.class, DruidCommandInteractionCompat::handleMouseButton);
            HytaleServer.get().getEventBus().registerGlobal(PlayerInteractEvent.class, DruidCommandInteractionCompat::handleInteract);
            packetBridgeDisabledByGuard = isPacketBridgeDisabled();
            if (packetBridgeDisabledByGuard) {
                System.out.println("[DruidPacketBridge] disabled by runtime guard");
            } else {
                System.out.println("[DruidPacketBridge] deferred until player-ready");
            }
            HytaleServer.get().getEventBus().register(PlayerConnectEvent.class, event -> DruidPermissions.rememberPlayer(event.getPlayer()));
            HytaleServer.get().getEventBus().register(PlayerDisconnectEvent.class, event -> DruidPermissions.forgetPlayer(event.getPlayerRef().getUuid()));
            System.out.println("[DruidPlugin] Player event handlers registered.");
        } catch (Exception e) {
            System.out.println("[DruidPlugin] Error registering player events: " + e.getMessage());
        }
        try {
            HytaleServer.get().getEventBus().registerGlobal(StartWorldEvent.class, event ->
                    ProwlerStealthNpcAttitudeService.registerWorld(event.getWorld()));
            HytaleServer.get().getEventBus().registerGlobal(RemoveWorldEvent.class, event ->
                    ProwlerStealthNpcAttitudeService.unregisterWorld(event.getWorld()));
            ProwlerStealthNpcAttitudeService.registerExistingWorlds();
            System.out.println("[DruidPlugin] NPC attitude handlers registered.");
        } catch (Exception e) {
            System.out.println("[DruidPlugin] Error registering NPC attitude handlers: " + e.getMessage());
        }
        System.out.println("[DruidPlugin] V1 Ready!");
    }

    private void handleAuditedPlayerConnect(PlayerConnectEvent event) {
        if (event == null) return;
        Player player = event.getPlayer();
        if (player == null) return;
        DruidPermissions.rememberPlayer(player);

        shapeshiftHandler.handlePlayerConnect(event);

        // Update 5.0.2 stabilization: avoid opening HyUI HUD during connect/login restore.
        // HUD attach is deferred from the ready event after JoinWorld processing.
    }

    private void handleAuditedPlayerDisconnect(PlayerDisconnectEvent event) {
        if (event == null) return;
        Object playerRef = event.getPlayerRef();
        ProwlerStealthService.handleDisconnect(event.getPlayerRef());
        ProwlerPounceAbilityService.handleDisconnect(event.getPlayerRef());
        StalkerBreachAbilityService.handleDisconnect(event.getPlayerRef());
        StalkerHuntersMarkAbilityService.handleDisconnect(event.getPlayerRef());
        RootlightSpiritLightService.handleDisconnect(event.getPlayerRef());
        RootlightSpiritCompanionService.handleDisconnect(event.getPlayerRef());
        GuardianOakenshieldCooldownService.clearActive(event.getPlayerRef().getUuid());
        if (playerRef != null) {
            DruidCommandInteractionCompat.clearPlayerReady(event.getPlayerRef().getUuid());
        }
        if (playerRef != null) {
            DruidHyUiCurrentFormHud.detach(event.getPlayerRef().getUuid());
            DruidHyUiClassCooldownOverlayHud.remove(event.getPlayerRef().getUuid());
            DruidBuffDebuffHud.remove(event.getPlayerRef().getUuid());
        }
        shapeshiftHandler.handlePlayerDisconnect(event);
    }

    private void handleAuditedPlayerReady(PlayerReadyEvent event) {
        if (event == null) return;
        Player player = event.getPlayer();
        if (player == null) return;
        ensurePacketBridgeRegisteredAfterReady();
        DruidCommandInteractionCompat.markPlayerReady(player.getUuid());

        shapeshiftHandler.handlePlayerReady(event);

        scheduleHudVisibilityAttach(player);
    }

    private void handlePlayerCraftPersistence(PlayerCraftEvent event) {
        if (event == null || shapeshiftHandler == null) {
            return;
        }

        Player player = event.getPlayer();
        Object recipe = event.getCraftedRecipe();
        if (player == null || recipe == null) {
            return;
        }

        if (!ShapeshiftHandler.isDruidShrineRecipe(recipe)) {
            return;
        }

        String outputItemId = ShapeshiftHandler.getCraftedRecipePrimaryOutputItemId(recipe);
        shapeshiftHandler.persistShrineUpgradeResult(player, outputItemId);
    }

    @Override
    protected void shutdown() {
        ProwlerStealthService.shutdown();
        ProwlerPounceAbilityService.shutdown();
        ProwlerStealthNpcAttitudeService.shutdown();
        StalkerBreachAbilityService.shutdown();
        StalkerHuntersMarkAbilityService.shutdown();
        RootlightSpiritCompanionService.shutdown();
        RootlightSpiritLightService.shutdown();
        DruidBuffDebuffHud.removeAllCached();
    }

    private synchronized void ensurePacketBridgeRegisteredAfterReady() {
        if (packetBridgeDisabledByGuard || packetBridgeRegistered) {
            return;
        }
        PacketAdapters.registerInbound(DruidCommandInteractionCompat::handleInboundPacket);
        PacketAdapters.registerOutbound(this::handleOutboundPlayerPacket);
        packetBridgeRegistered = true;
        System.out.println("[DruidPacketBridge] registered after player-ready");
    }

    private void handleOutboundPlayerPacket(PlayerRef playerRef, Packet packet) {
        if (!(packet instanceof DamageInfo damageInfo) || playerRef == null || damageInfo.damageAmount <= 0.0f) {
            return;
        }

        Player player = DruidPermissions.getOnlinePlayer(playerRef.getUuid());
        if (OAKENSHIELD_DAMAGE_DEBUG && GuardianOakenshieldCooldownService.isActive(player)) {
            System.out.println("[OakenshieldDamageDebug] active=true finalDamage=" + damageInfo.damageAmount);
        }
        if (player == null || !ProwlerStealthService.isStealthed(player)) {
            return;
        }

        ProwlerStealthService.handleIncomingDamage(player);
    }

    private String tryReadDisconnectDisplayName(PlayerDisconnectEvent event) {
        try {
            Method getPlayer = event.getClass().getMethod("getPlayer");
            Object playerObj = getPlayer.invoke(event);
            if (playerObj instanceof Player player) {
                return safeDisplayName(player);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String safeDisplayName(Player player) {
        return DruidPlayerCompat.getPlayerName(player);
    }

    private String safeUsername(Object playerRef) {
        if (playerRef == null) return null;
        try {
            Method getUsername = playerRef.getClass().getMethod("getUsername");
            Object value = getUsername.invoke(playerRef);
            return value instanceof String ? (String) value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readHotbarSlotOneItem(Player player) {
        try {
            Object inventory = player.getClass().getMethod("getInventory").invoke(player);
            if (inventory == null) return "n/a";
            Object hotbar = inventory.getClass().getMethod("getHotbar").invoke(inventory);
            if (hotbar == null) return "n/a";

            Method getStack = findGetStackMethod(hotbar);
            if (getStack == null) return "n/a";

            Object slotZero = invokeGetStack(hotbar, getStack, (short) 0);
            String zeroId = getItemId(slotZero);
            if (zeroId != null) return zeroId;

            Object slotOne = invokeGetStack(hotbar, getStack, (short) 1);
            String oneId = getItemId(slotOne);
            return oneId == null ? "empty" : oneId;
        } catch (Exception ignored) {
            return "n/a";
        }
    }

    private String readArmorSlots(Player player) {
        try {
            Object inventory = player.getClass().getMethod("getInventory").invoke(player);
            if (inventory == null) return "n/a";
            Object armor = inventory.getClass().getMethod("getArmor").invoke(inventory);
            if (armor == null) return "n/a";

            Method getStack = findGetStackMethod(armor);
            if (getStack == null) return "n/a";

            StringBuilder sb = new StringBuilder();
            for (short slot = 0; slot < 4; slot++) {
                if (slot > 0) sb.append('|');
                String itemId = getItemId(invokeGetStack(armor, getStack, slot));
                sb.append(itemId == null ? "empty" : itemId);
            }
            return sb.toString();
        } catch (Exception ignored) {
            return "n/a";
        }
    }

    private Method findGetStackMethod(Object container) {
        Class<?> clazz = container.getClass();
        try {
            return clazz.getMethod("getItemStackForSlot", short.class);
        } catch (Exception ignored) {
        }
        try {
            return clazz.getMethod("getItemStackForSlot", int.class);
        } catch (Exception ignored) {
        }
        for (Method m : clazz.getMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (m.getName().toLowerCase().contains("getitemstack")) return m;
        }
        return null;
    }

    private Object invokeGetStack(Object container, Method getter, short slot) {
        try {
            Class<?> param = getter.getParameterTypes()[0];
            if (param == short.class || param == Short.class) {
                return getter.invoke(container, slot);
            }
            if (param == int.class || param == Integer.class) {
                return getter.invoke(container, (int) slot);
            }
            return getter.invoke(container, slot);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String getItemId(Object itemStack) {
        if (itemStack == null) return null;
        try {
            Method getItemId = itemStack.getClass().getMethod("getItemId");
            Object value = getItemId.invoke(itemStack);
            return value instanceof String ? (String) value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isPacketBridgeDisabled() {
        String propertyValue = System.getProperty("druid.disablePacketBridge");
        if (isTruthy(propertyValue)) return true;
        String envValue = System.getenv("DRUID_DISABLE_PACKET_BRIDGE");
        return isTruthy(envValue);
    }

    private boolean isTruthy(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase();
        return normalized.equals("true")
                || normalized.equals("1")
                || normalized.equals("yes")
                || normalized.equals("on");
    }

    private void applyHudVisibility(Player player) {
        if (player == null) return;
        if (DruidPermissions.shouldShowHud(player)) {
            DruidHyUiCurrentFormHud.attachOrRefresh(player);
            return;
        }
        DruidHyUiCurrentFormHud.detach(player.getUuid());
    }

    private void scheduleHudVisibilityAttach(Player player) {
        if (player == null) return;
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) return;

        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            Player online = DruidPermissions.getOnlinePlayer(playerUuid);
            if (online == null) return;
            try {
                if (online.getWorld() != null) {
                    online.getWorld().execute(() -> applyHudVisibility(online));
                    return;
                }
            } catch (Exception ignored) {
            }
            applyHudVisibility(online);
        }, HUD_ATTACH_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }
}
