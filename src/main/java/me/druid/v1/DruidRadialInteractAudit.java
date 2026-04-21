package me.druid.v1;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;

import java.lang.reflect.Method;

final class DruidRadialInteractAudit {
    private DruidRadialInteractAudit() {
    }

    static void handle(PlayerInteractEvent event, ShapeshiftHandler shapeshiftHandler) {
        if (event == null) return;
        Player player = event.getPlayer();
        if (player == null) return;

        Object actionType = event.getActionType();
        boolean hasTargetBlock = event.getTargetBlock() != null;
        boolean hasTargetEntity = event.getTargetEntity() != null;

        HeldItemResolution held = resolveHeldItem(player, event);
        int activeSlot = resolveActiveHotbarSlot(player);
        boolean isSlot1Selected = activeSlot == 0 || activeSlot == 1;
        boolean isDruidControlItem = DruidControlItemMatcher.matches(shapeshiftHandler, held.resolvedItemId);
        boolean canTransform = DruidPermissions.canTransform(player);

        System.out.println("[DruidRadialP1BProbe] display=" + safeDisplayName(player)
                + " actionType=" + String.valueOf(actionType)
                + " eventItemId=" + nullToLiteral(held.eventItemId)
                + " derivedItemId=" + nullToLiteral(held.derivedItemId)
                + " heldItemId=" + nullToLiteral(held.resolvedItemId)
                + " heldItemSource=" + held.source
                + " hasTargetBlock=" + hasTargetBlock
                + " hasTargetEntity=" + hasTargetEntity
                + " slot=" + activeSlot
                + " isSlot1Selected=" + isSlot1Selected
                + " isDruidControlItem=" + isDruidControlItem
                + " canTransform=" + canTransform);
    }

    private static HeldItemResolution resolveHeldItem(Player player, PlayerInteractEvent event) {
        String eventItemId = null;
        String derivedItemId = null;

        try {
            eventItemId = getItemId(event.getItemInHand());
        } catch (Exception ignored) {
        }

        try {
            Object inventory = player.getClass().getMethod("getInventory").invoke(player);
            if (inventory != null) {
                Object heldStack = inventory.getClass().getMethod("getItemInHand").invoke(inventory);
                derivedItemId = getItemId(heldStack);
            }
        } catch (Exception ignored) {
        }

        if (eventItemId != null && !eventItemId.isBlank()) {
            return new HeldItemResolution(eventItemId, derivedItemId, eventItemId, "event");
        }
        if (derivedItemId != null && !derivedItemId.isBlank()) {
            return new HeldItemResolution(eventItemId, derivedItemId, derivedItemId, "inventory");
        }
        return new HeldItemResolution(eventItemId, derivedItemId, null, "none");
    }

    private static String getItemId(Object itemStack) {
        if (itemStack == null) return null;
        try {
            Method getItemId = itemStack.getClass().getMethod("getItemId");
            Object value = getItemId.invoke(itemStack);
            return value instanceof String ? (String) value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int resolveActiveHotbarSlot(Player player) {
        try {
            Object inventory = player.getClass().getMethod("getInventory").invoke(player);
            if (inventory == null) return -1;
            Method getActiveSlot = inventory.getClass().getMethod("getActiveHotbarSlot");
            Object value = getActiveSlot.invoke(inventory);
            if (value instanceof Number number) return number.intValue();
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static String safeDisplayName(Player player) {
        try {
            return player.getDisplayName();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String nullToLiteral(String value) {
        return value == null ? "null" : value;
    }

    private static final class HeldItemResolution {
        private final String eventItemId;
        private final String derivedItemId;
        private final String resolvedItemId;
        private final String source;

        private HeldItemResolution(String eventItemId, String derivedItemId, String resolvedItemId, String source) {
            this.eventItemId = eventItemId;
            this.derivedItemId = derivedItemId;
            this.resolvedItemId = resolvedItemId;
            this.source = source;
        }
    }
}
