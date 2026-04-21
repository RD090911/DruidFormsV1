package me.druid.v1;

import java.lang.reflect.Method;
import java.util.Locale;

final class DruidControlItemMatcher {
    private DruidControlItemMatcher() {
    }

    static boolean matches(ShapeshiftHandler shapeshiftHandler, String itemId) {
        if (shapeshiftHandler == null || itemId == null || itemId.isBlank()) return false;
        try {
            Method matcher = ShapeshiftHandler.class.getDeclaredMethod("isDruidAbilityItemId", String.class);
            matcher.setAccessible(true);
            Object result = matcher.invoke(shapeshiftHandler, itemId.toLowerCase(Locale.ROOT));
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception ignored) {
            return false;
        }
    }
}
