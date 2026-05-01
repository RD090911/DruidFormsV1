package me.druid.v1;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import me.druid.v1.hud.DruidHyUiCurrentFormHud;

import java.lang.reflect.Method;

public class DruidPlugin extends JavaPlugin {

    private ShapeshiftHandler shapeshiftHandler;

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
            for (Method m : cmdManager.getClass().getMethods()) {
                if (m.getName().equals("register") && m.getParameterCount() == 1) {
                    m.invoke(cmdManager, command);
                    m.invoke(cmdManager, druidCommand);
                    m.invoke(cmdManager, wardenLifeSeedCommand);
                    System.out.println("[DruidPlugin] Command /shapeshift successfully registered.");
                    break;
                }
            }
        } catch (Exception e) {
            System.out.println("[DruidPlugin] Error registering command: " + e.getMessage());
        }

        try {
            HytaleServer.get().getEventBus().register(PlayerConnectEvent.class, this::handleAuditedPlayerConnect);
            HytaleServer.get().getEventBus().registerGlobal(PlayerReadyEvent.class, this::handleAuditedPlayerReady);
            HytaleServer.get().getEventBus().register(PlayerDisconnectEvent.class, this::handleAuditedPlayerDisconnect);
            HytaleServer.get().getEventBus().register(PlayerConnectEvent.class, event -> DruidPermissions.rememberPlayer(event.getPlayer()));
            HytaleServer.get().getEventBus().register(PlayerDisconnectEvent.class, event -> DruidPermissions.forgetPlayer(event.getPlayerRef().getUuid()));
            System.out.println("[DruidPlugin] Player event handlers registered.");
        } catch (Exception e) {
            System.out.println("[DruidPlugin] Error registering player events: " + e.getMessage());
        }
        System.out.println("[DruidPlugin] V1 Ready!");
    }

    private void handleAuditedPlayerConnect(PlayerConnectEvent event) {
        if (event == null) return;
        Player player = event.getPlayer();
        if (player == null) return;
        DruidPermissions.rememberPlayer(player);

        String displayName = safeDisplayName(player);
        String activeBefore = displayName == null ? null : ShapeshiftHandler.activeForms.get(displayName);
        String slotOneBefore = readHotbarSlotOneItem(player);
        String armorBefore = readArmorSlots(player);
        System.out.println("[DruidRelogAudit] login-restore start display=" + displayName
                + " activeBefore=" + activeBefore
                + " slot1Before=" + slotOneBefore
                + " armorBefore=" + armorBefore);

        shapeshiftHandler.handlePlayerConnect(event);

        String activeAfter = displayName == null ? null : ShapeshiftHandler.activeForms.get(displayName);
        String slotOneAfter = readHotbarSlotOneItem(player);
        String armorAfter = readArmorSlots(player);
        String resolvedForm = activeAfter == null ? "human" : activeAfter;
        System.out.println("[DruidRelogAudit] login-restore end display=" + displayName
                + " activeAfter=" + activeAfter
                + " slot1After=" + slotOneAfter
                + " armorAfter=" + armorAfter
                + " finalForm=" + resolvedForm);

        applyHudVisibility(player);
    }

    private void handleAuditedPlayerDisconnect(PlayerDisconnectEvent event) {
        if (event == null) return;
        Object playerRef = event.getPlayerRef();
        if (playerRef != null) {
            DruidHyUiCurrentFormHud.detach(event.getPlayerRef().getUuid());
            DruidHyUiClassCooldownOverlayHud.remove(event.getPlayerRef().getUuid());
            WardenLifeSeedCooldownHud.remove(event.getPlayerRef().getUuid());
        }
        String username = safeUsername(playerRef);
        String displayName = tryReadDisconnectDisplayName(event);
        String activeBeforeDisplay = displayName == null ? null : ShapeshiftHandler.activeForms.get(displayName);
        String activeBeforeUsername = username == null ? null : ShapeshiftHandler.activeForms.get(username);
        System.out.println("[DruidRelogAudit] disconnect cleanup start display=" + displayName
                + " username=" + username
                + " activeBeforeDisplay=" + activeBeforeDisplay
                + " activeBeforeUsername=" + activeBeforeUsername);

        shapeshiftHandler.handlePlayerDisconnect(event);

        String activeAfterDisplay = displayName == null ? null : ShapeshiftHandler.activeForms.get(displayName);
        String activeAfterUsername = username == null ? null : ShapeshiftHandler.activeForms.get(username);
        System.out.println("[DruidRelogAudit] disconnect cleanup end display=" + displayName
                + " username=" + username
                + " activeAfterDisplay=" + activeAfterDisplay
                + " activeAfterUsername=" + activeAfterUsername);
    }

    private void handleAuditedPlayerReady(PlayerReadyEvent event) {
        if (event == null) return;
        Player player = event.getPlayer();
        if (player == null) return;

        String displayName = safeDisplayName(player);
        String slotOneBefore = readHotbarSlotOneItem(player);
        String armorBefore = readArmorSlots(player);
        System.out.println("[DruidRelogAudit] login-ready start display=" + displayName
                + " slot1Before=" + slotOneBefore
                + " armorBefore=" + armorBefore);

        shapeshiftHandler.handlePlayerReady(event);

        String slotOneAfter = readHotbarSlotOneItem(player);
        String armorAfter = readArmorSlots(player);
        System.out.println("[DruidRelogAudit] login-ready end display=" + displayName
                + " slot1After=" + slotOneAfter
                + " armorAfter=" + armorAfter);

        applyHudVisibility(player);
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
        try {
            return player.getDisplayName();
        } catch (Exception ignored) {
            return null;
        }
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

    private void applyHudVisibility(Player player) {
        if (player == null) return;
        if (DruidPermissions.shouldShowHud(player)) {
            DruidHyUiCurrentFormHud.attachOrRefresh(player);
            return;
        }
        DruidHyUiCurrentFormHud.detach(player.getUuid());
    }
}
