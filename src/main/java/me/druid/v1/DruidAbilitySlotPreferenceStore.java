package me.druid.v1;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class DruidAbilitySlotPreferenceStore {
    private static final Path STORAGE_PATH = Paths.get("run", "druid_ability_slot_preferences.properties");
    private static final int HOTBAR_SLOT_COUNT = 9;

    private DruidAbilitySlotPreferenceStore() {
    }

    static synchronized void loadInto(Map<UUID, Map<String, Map<String, Short>>> target) {
        if (target == null || !Files.exists(STORAGE_PATH)) {
            return;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(STORAGE_PATH)) {
            properties.load(input);
        } catch (IOException ignored) {
            return;
        }

        for (String key : properties.stringPropertyNames()) {
            Short slot = parseSlot(properties.getProperty(key));
            if (slot == null || !isValidHotbarSlot(slot)) {
                continue;
            }

            String[] parts = key.split("\\.", 3);
            if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) {
                continue;
            }

            UUID playerUuid;
            try {
                playerUuid = UUID.fromString(parts[0]);
            } catch (IllegalArgumentException ignored) {
                continue;
            }

            String normalizedFormKey = DruidAbilityItemIds.normalizeFormKey(parts[1]);
            String normalizedItemKey = DruidAbilityItemIds.normalizeItemId(parts[2]);
            if (normalizedFormKey == null || normalizedFormKey.isBlank()
                    || normalizedItemKey == null || normalizedItemKey.isBlank()) {
                continue;
            }

            target.computeIfAbsent(playerUuid, unusedPlayer -> new ConcurrentHashMap<>())
                    .computeIfAbsent(normalizedFormKey, unusedForm -> new ConcurrentHashMap<>())
                    .put(normalizedItemKey, slot);
        }
    }

    static synchronized void save(Map<UUID, Map<String, Map<String, Short>>> source) {
        if (source == null) {
            return;
        }

        Properties properties = new Properties();
        for (Map.Entry<UUID, Map<String, Map<String, Short>>> playerEntry : source.entrySet()) {
            UUID playerUuid = playerEntry.getKey();
            Map<String, Map<String, Short>> preferencesByForm = playerEntry.getValue();
            if (playerUuid == null || preferencesByForm == null) {
                continue;
            }

            for (Map.Entry<String, Map<String, Short>> formEntry : preferencesByForm.entrySet()) {
                String formKey = formEntry.getKey();
                Map<String, Short> preferencesByItem = formEntry.getValue();
                if (formKey == null || formKey.isBlank() || preferencesByItem == null) {
                    continue;
                }

                for (Map.Entry<String, Short> itemEntry : preferencesByItem.entrySet()) {
                    String itemKey = itemEntry.getKey();
                    Short slot = itemEntry.getValue();
                    if (itemKey == null || itemKey.isBlank() || slot == null || !isValidHotbarSlot(slot)) {
                        continue;
                    }
                    properties.setProperty(playerUuid + "." + formKey + "." + itemKey, Short.toString(slot));
                }
            }
        }

        try {
            Files.createDirectories(STORAGE_PATH.getParent());
            try (OutputStream output = Files.newOutputStream(STORAGE_PATH)) {
                properties.store(output, "DruidForms ability slot preferences");
            }
        } catch (IOException ignored) {
        }
    }

    private static Short parseSlot(String rawSlot) {
        if (rawSlot == null || rawSlot.isBlank()) {
            return null;
        }

        try {
            return Short.parseShort(rawSlot.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isValidHotbarSlot(short slot) {
        return slot >= 0 && slot < HOTBAR_SLOT_COUNT;
    }
}
