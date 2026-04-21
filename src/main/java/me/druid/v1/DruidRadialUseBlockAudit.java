package me.druid.v1;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;

import java.lang.reflect.Method;

final class DruidRadialUseBlockAudit {
    private DruidRadialUseBlockAudit() {
    }

    static void handle(UseBlockEvent.Pre event, ShapeshiftHandler shapeshiftHandler) {
        if (event == null) return;

        Object context = safeCall(event, "getContext");
        Player player = resolvePlayer(context);
        Integer activeSlot = resolveActiveHotbarSlot(player, context);
        String heldItemId = resolveHeldItemId(context);

        String displayName = player == null ? "unavailable" : nullToUnavailable(safeDisplayName(player));
        String interactionType = nullToUnavailable(safeString(event.getInteractionType()));
        String hasTargetBlock = boolOrUnavailable(event, "getTargetBlock");
        String hasTargetEntity = boolOrUnavailable(context, "getTargetEntity");
        String slotValue = activeSlot == null ? "unavailable" : String.valueOf(activeSlot);
        String isSlot1Selected = activeSlot == null ? "unavailable" : String.valueOf(activeSlot == 0 || activeSlot == 1);
        String isDruidControlItem = heldItemId == null
                ? "unavailable"
                : String.valueOf(DruidControlItemMatcher.matches(shapeshiftHandler, heldItemId));
        String canTransform = player == null ? "unavailable" : String.valueOf(DruidPermissions.canTransform(player));

        System.out.println("[DruidRadialP1CProbe] display=" + displayName
                + " eventClass=" + event.getClass().getName()
                + " interactionType=" + interactionType
                + " heldItemId=" + nullToUnavailable(heldItemId)
                + " hasTargetBlock=" + hasTargetBlock
                + " hasTargetEntity=" + hasTargetEntity
                + " slot=" + slotValue
                + " isSlot1Selected=" + isSlot1Selected
                + " isDruidControlItem=" + isDruidControlItem
                + " canTransform=" + canTransform);
    }

    private static Player resolvePlayer(Object context) {
        Object entityRef = safeCall(context, "getEntity");
        if (entityRef == null) {
            entityRef = safeCall(context, "getOwningEntity");
        }
        if (entityRef == null) return null;

        try {
            Object store = safeCall(entityRef, "getStore");
            if (store == null) return null;

            Class<?> refClass = Class.forName("com.hypixel.hytale.component.Ref");
            Class<?> componentTypeClass = Class.forName("com.hypixel.hytale.component.ComponentType");
            Class<?> playerClass = Class.forName("com.hypixel.hytale.server.core.entity.entities.Player");
            Object playerComponentType = playerClass.getMethod("getComponentType").invoke(null);

            Method getComponent = store.getClass().getMethod("getComponent", refClass, componentTypeClass);
            Object playerObj = getComponent.invoke(store, entityRef, playerComponentType);
            return playerObj instanceof Player ? (Player) playerObj : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer resolveActiveHotbarSlot(Player player, Object context) {
        Integer fromPlayer = resolveActiveHotbarSlotFromPlayer(player);
        if (fromPlayer != null) return fromPlayer;
        return resolveHeldSlotFromContext(context);
    }

    private static Integer resolveActiveHotbarSlotFromPlayer(Player player) {
        if (player == null) return null;
        try {
            Object inventory = player.getClass().getMethod("getInventory").invoke(player);
            if (inventory == null) return null;
            Method getActiveSlot = inventory.getClass().getMethod("getActiveHotbarSlot");
            Object value = getActiveSlot.invoke(inventory);
            if (value instanceof Number number) return number.intValue();
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Integer resolveHeldSlotFromContext(Object context) {
        if (context == null) return null;
        try {
            Object value = context.getClass().getMethod("getHeldItemSlot").invoke(context);
            if (value instanceof Number number) return number.intValue();
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String resolveHeldItemId(Object context) {
        if (context == null) return null;

        String fromHeld = extractItemId(safeCall(context, "getHeldItem"));
        if (fromHeld != null && !fromHeld.isBlank()) return fromHeld;

        String fromOriginalType = extractItemId(safeCall(context, "getOriginalItemType"));
        if (fromOriginalType != null && !fromOriginalType.isBlank()) return fromOriginalType;

        return null;
    }

    private static String extractItemId(Object itemLike) {
        if (itemLike == null) return null;
        try {
            Method getItemId = itemLike.getClass().getMethod("getItemId");
            Object value = getItemId.invoke(itemLike);
            if (value instanceof String) return (String) value;
        } catch (Exception ignored) {
        }
        try {
            Method getId = itemLike.getClass().getMethod("getId");
            Object value = getId.invoke(itemLike);
            if (value instanceof String) return (String) value;
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Object safeCall(Object target, String methodName) {
        if (target == null || methodName == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String boolOrUnavailable(Object target, String methodName) {
        if (target == null) return "unavailable";
        try {
            Method m = target.getClass().getMethod(methodName);
            Object value = m.invoke(target);
            return String.valueOf(value != null);
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    private static String safeDisplayName(Player player) {
        try {
            return player.getDisplayName();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safeString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String nullToUnavailable(String value) {
        return value == null ? "unavailable" : value;
    }
}
