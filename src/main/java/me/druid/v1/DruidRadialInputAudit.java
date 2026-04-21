package me.druid.v1;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;

import java.lang.reflect.Method;

final class DruidRadialInputAudit {
    private DruidRadialInputAudit() {
    }

    static void handle(PlayerMouseButtonEvent event, ShapeshiftHandler shapeshiftHandler) {
        System.out.println("[DruidRadialP1AProbe] entry reached");
        MouseButtonProbe probe = inspectMouseButton(event);
        System.out.println("[DruidRadialP1AProbe] eventClass=" + probe.eventClassName
                + " hasGetMouseButton=" + probe.hasGetMouseButtonMethod
                + " mouseButtonNonNull=" + probe.mouseButtonNonNull
                + " reflectionSucceeded=" + probe.buttonStateReflectionSucceeded
                + " rawButton=" + probe.rawButtonValue
                + " rawState=" + probe.rawStateValue
                + " isRightPressed=" + probe.isRightPressed);

        if (!probe.isRightPressed) return;

        Player player = event.getPlayer();
        if (player == null) return;

        int activeSlot = resolveActiveHotbarSlot(player);
        String heldItemId = resolveHeldItemId(player);
        boolean isSlot1Selected = activeSlot == 0 || activeSlot == 1;
        boolean isDruidControlItem = DruidControlItemMatcher.matches(shapeshiftHandler, heldItemId);
        boolean canTransform = DruidPermissions.canTransform(player);

        System.out.println("[DruidRadialP1A] right-click-audit display=" + safeDisplayName(player)
                + " slot=" + activeSlot
                + " heldItem=" + (heldItemId == null ? "null" : heldItemId)
                + " isSlot1Selected=" + isSlot1Selected
                + " isDruidControlItem=" + isDruidControlItem
                + " canTransform=" + canTransform);
    }

    private static MouseButtonProbe inspectMouseButton(PlayerMouseButtonEvent event) {
        String eventClassName = event == null ? "null" : event.getClass().getName();
        boolean hasGetMouseButtonMethod = false;
        boolean mouseButtonNonNull = false;
        boolean buttonStateReflectionSucceeded = false;
        String rawButtonValue = "null";
        String rawStateValue = "null";
        boolean isRightPressed = false;
        if (event == null) {
            return new MouseButtonProbe(
                    eventClassName,
                    hasGetMouseButtonMethod,
                    mouseButtonNonNull,
                    buttonStateReflectionSucceeded,
                    rawButtonValue,
                    rawStateValue,
                    isRightPressed
            );
        }
        try {
            Method getMouseButton = event.getClass().getMethod("getMouseButton");
            hasGetMouseButtonMethod = true;
            Object mouseButton = getMouseButton.invoke(event);
            mouseButtonNonNull = mouseButton != null;
            if (mouseButton == null) {
                return new MouseButtonProbe(
                        eventClassName,
                        hasGetMouseButtonMethod,
                        mouseButtonNonNull,
                        buttonStateReflectionSucceeded,
                        rawButtonValue,
                        rawStateValue,
                        isRightPressed
                );
            }

            Object type = mouseButton.getClass().getField("mouseButtonType").get(mouseButton);
            Object state = mouseButton.getClass().getField("state").get(mouseButton);
            rawButtonValue = String.valueOf(type);
            rawStateValue = String.valueOf(state);
            buttonStateReflectionSucceeded = true;
            isRightPressed = "Right".equals(rawButtonValue) && "Pressed".equals(rawStateValue);
        } catch (Exception ignored) {
        }
        return new MouseButtonProbe(
                eventClassName,
                hasGetMouseButtonMethod,
                mouseButtonNonNull,
                buttonStateReflectionSucceeded,
                rawButtonValue,
                rawStateValue,
                isRightPressed
        );
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

    private static String resolveHeldItemId(Player player) {
        try {
            Object inventory = player.getClass().getMethod("getInventory").invoke(player);
            if (inventory == null) return null;
            Object heldStack = inventory.getClass().getMethod("getItemInHand").invoke(inventory);
            return getItemId(heldStack);
        } catch (Exception ignored) {
            return null;
        }
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

    private static String safeDisplayName(Player player) {
        try {
            return player.getDisplayName();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class MouseButtonProbe {
        private final String eventClassName;
        private final boolean hasGetMouseButtonMethod;
        private final boolean mouseButtonNonNull;
        private final boolean buttonStateReflectionSucceeded;
        private final String rawButtonValue;
        private final String rawStateValue;
        private final boolean isRightPressed;

        private MouseButtonProbe(
                String eventClassName,
                boolean hasGetMouseButtonMethod,
                boolean mouseButtonNonNull,
                boolean buttonStateReflectionSucceeded,
                String rawButtonValue,
                String rawStateValue,
                boolean isRightPressed
        ) {
            this.eventClassName = eventClassName;
            this.hasGetMouseButtonMethod = hasGetMouseButtonMethod;
            this.mouseButtonNonNull = mouseButtonNonNull;
            this.buttonStateReflectionSucceeded = buttonStateReflectionSucceeded;
            this.rawButtonValue = rawButtonValue;
            this.rawStateValue = rawStateValue;
            this.isRightPressed = isRightPressed;
        }
    }
}
