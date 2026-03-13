package me.druid.v1;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

class AnimalArmorService {
    private static final short HEAD_SLOT = 0;
    private static final short CHEST_SLOT = 1;
    private static final short HANDS_SLOT = 2;
    private static final short LEGS_SLOT = 3;
    private static final int MIN_TIER = 1;
    private static final int MAX_TIER = 4;
    private static final short ARMOR_SLOT_COUNT = 4;
    private static final Path SNAPSHOT_FILE = Paths.get("run", "druid_armor_snapshots.properties");

    private static final Set<String> TIERED_ANIMAL_FORMS = Set.of("bear", "ram", "shark", "tiger");
    private static final Set<String> NON_ARMOR_ANIMAL_FORMS = Set.of("hawk", "duck", "rabbit", "antelope");

    private static final Map<String, String[][]> ANIMAL_ARMOR_SETS = Map.of(
            "bear", new String[][] {
                    {
                            "Bear_Visage",
                            "Bear_Spirit_Mantle",
                            "Bear_Rune_Bindings",
                            "Bear_Striders"
                    },
                    {
                            "Verdant_Bear_Visage",
                            "Verdant_Bear_Spirit_Mantle",
                            "Verdant_Bear_Rune_Bindings",
                            "Verdant_Bear_Striders"
                    },
                    {
                            "Primal_Bear_Visage",
                            "Primal_Bear_Spirit_Mantle",
                            "Primal_Bear_Rune_Bindings",
                            "Primal_Bear_Striders"
                    },
                    {
                            "Elder_Bear_Visage",
                            "Elder_Bear_Spirit_Mantle",
                            "Elder_Bear_Rune_Bindings",
                            "Elder_Bear_Striders"
                    }
            },
            "ram", new String[][] {
                    {
                            "Ram_Visage",
                            "Ram_Spirit_Mantle",
                            "Ram_Rune_Bindings",
                            "Ram_Striders"
                    },
                    {
                            "Verdant_Ram_Visage",
                            "Verdant_Ram_Spirit_Mantle",
                            "Verdant_Ram_Rune_Bindings",
                            "Verdant_Ram_Striders"
                    },
                    {
                            "Primal_Ram_Visage",
                            "Primal_Ram_Spirit_Mantle",
                            "Primal_Ram_Rune_Bindings",
                            "Primal_Ram_Striders"
                    },
                    {
                            "Elder_Ram_Visage",
                            "Elder_Ram_Spirit_Mantle",
                            "Elder_Ram_Rune_Bindings",
                            "Elder_Ram_Striders"
                    }
            },
            "shark", new String[][] {
                    {
                            "Shark_Visage",
                            "Shark_Spirit_Mantle",
                            "Shark_Rune_Bindings",
                            "Shark_Striders"
                    },
                    {
                            "Verdant_Shark_Visage",
                            "Verdant_Shark_Spirit_Mantle",
                            "Verdant_Shark_Rune_Bindings",
                            "Verdant_Shark_Striders"
                    },
                    {
                            "Primal_Shark_Visage",
                            "Primal_Shark_Spirit_Mantle",
                            "Primal_Shark_Rune_Bindings",
                            "Primal_Shark_Striders"
                    },
                    {
                            "Elder_Shark_Visage",
                            "Elder_Shark_Spirit_Mantle",
                            "Elder_Shark_Rune_Bindings",
                            "Elder_Shark_Striders"
                    }
            },
            "tiger", new String[][] {
                    {
                            "Tiger_Visage",
                            "Tiger_Spirit_Mantle",
                            "Tiger_Rune_Bindings",
                            "Tiger_Striders"
                    },
                    {
                            "Verdant_Tiger_Visage",
                            "Verdant_Tiger_Spirit_Mantle",
                            "Verdant_Tiger_Rune_Bindings",
                            "Verdant_Tiger_Striders"
                    },
                    {
                            "Primal_Tiger_Visage",
                            "Primal_Tiger_Spirit_Mantle",
                            "Primal_Tiger_Rune_Bindings",
                            "Primal_Tiger_Striders"
                    },
                    {
                            "Elder_Tiger_Visage",
                            "Elder_Tiger_Spirit_Mantle",
                            "Elder_Tiger_Rune_Bindings",
                            "Elder_Tiger_Striders"
                    }
            }
    );

    private final Map<String, AnimalArmorState> playerStates = new ConcurrentHashMap<>();
    private final Object persistenceLock = new Object();

    AnimalArmorService() {
        loadPersistedSnapshots();
    }

    void onFormChanged(Player player, String previousForm, String newForm, int requestedTier) {
        if (player == null) {
            return;
        }

        String normalizedNewForm = normalizeForm(newForm);
        if (!isTrackedForm(normalizedNewForm)) {
            return;
        }

        String playerId = getPlayerKey(player);
        if (playerId == null) {
            return;
        }

        ensureSnapshotCaptured(playerId, player);
        refreshArmorForForm(player, normalizedNewForm, clampTier(requestedTier));
    }

    void refreshActiveFormArmor(Player player, String activeForm, int requestedTier) {
        if (player == null) {
            return;
        }

        String normalizedForm = normalizeForm(activeForm);
        if (!isTrackedForm(normalizedForm)) {
            return;
        }

        String playerId = getPlayerKey(player);
        if (playerId == null) {
            return;
        }

        // Keep the first captured snapshot until player returns to human.
        ensureSnapshotCaptured(playerId, player);
        refreshArmorForForm(player, normalizedForm, clampTier(requestedTier));
    }

    void onRestoreToHuman(Player player) {
        if (player == null) {
            return;
        }
        restoreSavedArmor(player);
    }

    void clearPlayerState(Player player) {
        if (player == null) {
            return;
        }
        String playerId = getPlayerKey(player);
        if (playerId == null) {
            return;
        }
        clearPlayerState(playerId);
    }

    void clearPlayerState(String playerName) {
        if (playerName == null) {
            return;
        }
        removeSnapshot(playerName);
    }

    void recoverHumanArmorOnLogin(Player player, boolean isTransformed) {
        if (player == null || isTransformed) {
            return;
        }

        String playerId = getPlayerKey(player);
        if (playerId != null && playerStates.get(playerId) != null) {
            restoreSavedArmor(player);
            return;
        }

        ItemContainer armor = getArmorContainer(player);
        if (armor == null) {
            return;
        }

        if (hasAnimalArmorEquipped(armor)) {
            clearArmorSlots(armor);
        }
    }

    boolean isTrackedForm(String formKey) {
        if (formKey == null) {
            return false;
        }
        String normalized = normalizeForm(formKey);
        return TIERED_ANIMAL_FORMS.contains(normalized) || NON_ARMOR_ANIMAL_FORMS.contains(normalized);
    }

    private void restoreSavedArmor(Player player) {
        ItemContainer armor = getArmorContainer(player);
        if (armor == null) {
            return;
        }

        // Always clear transformed armor before restoring the saved snapshot.
        clearArmorSlots(armor);

        String playerId = getPlayerKey(player);
        if (playerId == null) {
            return;
        }

        AnimalArmorState state = playerStates.get(playerId);
        if (state == null) {
            return;
        }

        for (short slot = 0; slot < AnimalArmorState.SLOT_COUNT; slot++) {
            setArmorSlot(armor, slot, state.getSlot(slot));
        }
        removeSnapshot(playerId);
    }

    private void refreshArmorForForm(Player player, String formKey, int requestedTier) {
        ItemContainer armor = getArmorContainer(player);
        if (armor == null) return;

        String[] armorSet = getArmorSet(formKey, requestedTier);
        if (armorSet == null) {
            if (isNonArmorForm(formKey)) {
                clearArmorSlots(armor);
            }
            return;
        }

        setArmorSlot(armor, HEAD_SLOT, createArmorStack(armorSet[0]));
        setArmorSlot(armor, CHEST_SLOT, createArmorStack(armorSet[1]));
        setArmorSlot(armor, HANDS_SLOT, createArmorStack(armorSet[2]));
        setArmorSlot(armor, LEGS_SLOT, createArmorStack(armorSet[3]));
    }

    private void clearArmorSlots(ItemContainer armor) {
        setArmorSlot(armor, HEAD_SLOT, null);
        setArmorSlot(armor, CHEST_SLOT, null);
        setArmorSlot(armor, HANDS_SLOT, null);
        setArmorSlot(armor, LEGS_SLOT, null);
    }

    private String[] getArmorSet(String formKey, int tier) {
        String[][] sets = ANIMAL_ARMOR_SETS.get(formKey);
        if (sets == null || sets.length == 0) {
            return null;
        }
        int index = Math.max(0, Math.min(sets.length - 1, tier - 1));
        return sets[index];
    }

    private boolean isNonArmorForm(String formKey) {
        return NON_ARMOR_ANIMAL_FORMS.contains(formKey);
    }

    private ItemContainer getArmorContainer(Player player) {
        try {
            Inventory inventory = player.getInventory();
            if (inventory == null) return null;
            return inventory.getArmor();
        } catch (Exception e) {
            System.out.println("[Druid] Failed to access player armor container for " + player.getDisplayName() + ": " + e.getMessage());
            return null;
        }
    }

    private void setArmorSlot(ItemContainer armor, short slot, ItemStack itemStack) {
        if (armor == null) return;
        try {
            armor.setItemStackForSlot(slot, itemStack);
        } catch (Exception e) {
            System.out.println("[Druid] Failed to set animal armor slot " + slot + ": " + e.getMessage());
        }
    }

    private AnimalArmorState captureArmorState(Player player) {
        ItemContainer armor = getArmorContainer(player);
        if (armor == null) {
            return new AnimalArmorState();
        }

        AnimalArmorState snapshot = new AnimalArmorState();
        for (short slot = 0; slot < AnimalArmorState.SLOT_COUNT; slot++) {
            try {
                snapshot.setSlot(slot, cloneStack(armor.getItemStack(slot)));
            } catch (Exception ignored) {
            }
        }
        return snapshot;
    }

    private void ensureSnapshotCaptured(String playerId, Player player) {
        if (playerId == null || player == null) {
            return;
        }
        if (playerStates.containsKey(playerId)) {
            return;
        }
        saveSnapshot(playerId, captureArmorState(player));
    }

    private boolean hasAnimalArmorEquipped(ItemContainer armor) {
        if (armor == null) return false;
        for (short slot = 0; slot < ARMOR_SLOT_COUNT; slot++) {
            try {
                ItemStack stack = armor.getItemStack(slot);
                if (stack == null) continue;
                String itemId = stack.getItemId();
                if (itemId == null) continue;
                if (isAnimalArmorItemId(itemId)) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private boolean isAnimalArmorItemId(String itemId) {
        String lower = itemId.toLowerCase(Locale.ROOT);
        for (String[][] sets : ANIMAL_ARMOR_SETS.values()) {
            for (String[] tierSet : sets) {
                for (String armorId : tierSet) {
                    if (lower.contains(armorId.toLowerCase(Locale.ROOT))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private ItemStack cloneStack(ItemStack source) {
        if (source == null) return null;
        try {
            String itemId = source.getItemId();
            int quantity = source.getQuantity();
            double durability = source.getDurability();
            double maxDurability = source.getMaxDurability();
            org.bson.BsonDocument metadata = source.getMetadata();

            if (itemId == null || itemId.isBlank()) return null;
            return new ItemStack(itemId, quantity, durability, maxDurability, metadata);
        } catch (Exception e) {
            return null;
        }
    }

    private ItemStack createArmorStack(String itemId) {
        try {
            return new ItemStack(itemId, 1);
        } catch (Exception e) {
            return null;
        }
    }

    private String getPlayerKey(Player player) {
        try {
            return player.getDisplayName();
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeForm(String form) {
        if (form == null) {
            return null;
        }
        return form.toLowerCase(Locale.ROOT);
    }

    private int clampTier(int tier) {
        if (tier < MIN_TIER) return MIN_TIER;
        if (tier > MAX_TIER) return MAX_TIER;
        return tier;
    }

    private void saveSnapshot(String playerId, AnimalArmorState snapshot) {
        if (playerId == null || snapshot == null) {
            return;
        }
        synchronized (persistenceLock) {
            playerStates.put(playerId, snapshot);
            persistSnapshotsLocked();
        }
    }

    private void removeSnapshot(String playerId) {
        if (playerId == null) {
            return;
        }
        synchronized (persistenceLock) {
            playerStates.remove(playerId);
            persistSnapshotsLocked();
        }
    }

    private void loadPersistedSnapshots() {
        synchronized (persistenceLock) {
            if (!Files.exists(SNAPSHOT_FILE)) {
                return;
            }

            Properties properties = new Properties();
            try (var input = Files.newInputStream(SNAPSHOT_FILE)) {
                properties.load(input);
            } catch (Exception e) {
                System.out.println("[Druid] Failed to load persisted armor snapshots: " + e.getMessage());
                return;
            }

            for (String key : properties.stringPropertyNames()) {
                if (!key.endsWith(".slot0")) continue;
                String playerId = key.substring(0, key.length() - ".slot0".length());
                AnimalArmorState state = new AnimalArmorState();
                for (short slot = 0; slot < ARMOR_SLOT_COUNT; slot++) {
                    String encoded = properties.getProperty(playerId + ".slot" + slot);
                    state.setSlot(slot, decodeStack(encoded));
                }
                playerStates.put(playerId, state);
            }
        }
    }

    private void persistSnapshotsLocked() {
        Properties properties = new Properties();
        for (Map.Entry<String, AnimalArmorState> entry : playerStates.entrySet()) {
            String playerId = entry.getKey();
            AnimalArmorState state = entry.getValue();
            for (short slot = 0; slot < ARMOR_SLOT_COUNT; slot++) {
                properties.setProperty(playerId + ".slot" + slot, encodeStack(state.getSlot(slot)));
            }
        }

        try {
            Path parent = SNAPSHOT_FILE.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (var output = Files.newOutputStream(SNAPSHOT_FILE)) {
                properties.store(output, "Druid armor snapshots");
            }
        } catch (Exception e) {
            System.out.println("[Druid] Failed to persist armor snapshots: " + e.getMessage());
        }
    }

    private String encodeStack(ItemStack stack) {
        if (stack == null) {
            return "";
        }

        try {
            String itemId = stack.getItemId();
            if (itemId == null || itemId.isBlank()) {
                return "";
            }

            BsonDocument payload = new BsonDocument();
            payload.put("itemId", new BsonString(itemId));
            payload.put("quantity", new BsonInt32(stack.getQuantity()));
            payload.put("durability", new BsonDouble(stack.getDurability()));
            payload.put("maxDurability", new BsonDouble(stack.getMaxDurability()));
            BsonDocument metadata = stack.getMetadata();
            payload.put("metadata", metadata != null ? metadata : new BsonDocument());

            byte[] raw = payload.toJson().getBytes(StandardCharsets.UTF_8);
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            return "";
        }
    }

    private ItemStack decodeStack(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }

        try {
            byte[] raw = Base64.getDecoder().decode(encoded);
            String json = new String(raw, StandardCharsets.UTF_8);
            BsonDocument payload = BsonDocument.parse(json);

            if (!payload.containsKey("itemId")) {
                return null;
            }

            String itemId = payload.getString("itemId").getValue();
            int quantity = payload.containsKey("quantity") ? payload.getInt32("quantity").getValue() : 1;
            double durability = payload.containsKey("durability") ? payload.getDouble("durability").getValue() : 0.0d;
            double maxDurability = payload.containsKey("maxDurability") ? payload.getDouble("maxDurability").getValue() : 0.0d;
            BsonDocument metadata = payload.containsKey("metadata") && payload.get("metadata").isDocument()
                    ? payload.getDocument("metadata")
                    : new BsonDocument();

            return new ItemStack(itemId, quantity, durability, maxDurability, metadata);
        } catch (Exception e) {
            return null;
        }
    }

    private static final class AnimalArmorState {
        static final short SLOT_COUNT = ARMOR_SLOT_COUNT;
        private final ItemStack[] slots = new ItemStack[SLOT_COUNT];

        void setSlot(short slot, ItemStack stack) {
            if (slot < 0 || slot >= SLOT_COUNT) return;
            slots[slot] = stack;
        }

        ItemStack getSlot(short slot) {
            if (slot < 0 || slot >= SLOT_COUNT) return null;
            return slots[slot];
        }
    }
}
