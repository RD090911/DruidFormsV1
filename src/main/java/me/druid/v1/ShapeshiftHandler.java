package me.druid.v1;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ShapeshiftHandler {

    public static final ConcurrentHashMap<String, String> activeForms = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static final ConcurrentHashMap<String, Boolean> maintenanceActive = new ConcurrentHashMap<>();
    private static final Set<String> duckOxygenBonusApplied = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<UUID, DruidFormProgress> PLAYER_PROGRESS = new ConcurrentHashMap<>();
    private static final float DUCK_UNDERWATER_BASE_SPEED = 13.2f;
    private static final float DUCK_UNDERWATER_DRAG = 0.008f;
    private static final float DUCK_UNDERWATER_SPRINT_MULTIPLIER = 1.18f;
    private static final int DUCK_OXYGEN_BONUS = 135;

    private static final String DRUID_ITEM = "Druid_Totem";
    private static final short HOTBAR_SLOT_ONE = 0;
    private static final short HOTBAR_SLOT_ONE_ALT = 1;
    private static final short HOTBAR_SIZE = 10;

    private static final Map<String, String> ALLOWED_FORMS = new HashMap<>();
    private final AnimalArmorService animalArmorService = new AnimalArmorService();

    static {
        ALLOWED_FORMS.put("bear", "Druid_Bear");
        ALLOWED_FORMS.put("ram", "Druid_Ram");
        ALLOWED_FORMS.put("duck", "Duck");
        ALLOWED_FORMS.put("shark", "Shark_Hammerhead");
        ALLOWED_FORMS.put("hawk", "Hawk");
        ALLOWED_FORMS.put("tiger", "Tiger_Sabertooth");
        ALLOWED_FORMS.put("rabbit", "Rabbit");
        ALLOWED_FORMS.put("antelope", "Antelope");
    }

    public static class AbilityConfig { }

    private enum TieredForm {
        TIGER("tiger", new String[]{"Tiger_Claw", "Verdant_Tiger_Claw", "Primal_Tiger_Claw", "Elder_Tiger_Claw"}),
        BEAR("bear", new String[]{"Bear_Skin", "Verdant_Bear_Skin", "Primal_Bear_Skin", "Elder_Bear_Skin"}),
        SHARK("shark", new String[]{"Shark_Tooth", "Verdant_Shark_Tooth", "Primal_Shark_Tooth", "Elder_Shark_Tooth"}),
        RAM("ram", new String[]{"Ram_Horn", "Verdant_Ram_Horn", "Primal_Ram_Horn", "Elder_Ram_Horn"});

        private final String formKey;
        private final String[] itemIdsByTier;

        TieredForm(String formKey, String[] itemIdsByTier) {
            this.formKey = formKey;
            this.itemIdsByTier = itemIdsByTier;
        }

        static TieredForm fromFormKey(String formKey) {
            for (TieredForm value : values()) {
                if (value.formKey.equals(formKey)) return value;
            }
            return null;
        }

        String itemForTier(int tier) {
            int index = Math.max(1, Math.min(4, tier)) - 1;
            return itemIdsByTier[index];
        }
    }

    private static final class DruidFormProgress {
        private int tigerTier = 1;
        private int bearTier = 1;
        private int sharkTier = 1;
        private int ramTier = 1;

        int getTier(TieredForm form) {
            switch (form) {
                case TIGER: return tigerTier;
                case BEAR: return bearTier;
                case SHARK: return sharkTier;
                case RAM: return ramTier;
                default: return 1;
            }
        }

        void setTier(TieredForm form, int newTier) {
            int clamped = Math.max(1, Math.min(4, newTier));
            switch (form) {
                case TIGER: tigerTier = clamped; break;
                case BEAR: bearTier = clamped; break;
                case SHARK: sharkTier = clamped; break;
                case RAM: ramTier = clamped; break;
                default: break;
            }
        }
    }

    public int getTier(Player player, String formName) {
        TieredForm tieredForm = TieredForm.fromFormKey(canonicalizeFormKey(formName.toLowerCase(Locale.ROOT)));
        if (tieredForm == null) return 1;
        return getProgress(player).getTier(tieredForm);
    }

    public boolean canUpgradeTier(Player player, String formName) {
        TieredForm tieredForm = TieredForm.fromFormKey(canonicalizeFormKey(formName.toLowerCase(Locale.ROOT)));
        if (tieredForm == null) return false;
        return getProgress(player).getTier(tieredForm) < 4;
    }

    public boolean upgradeTier(Player player, String formName) {
        TieredForm tieredForm = TieredForm.fromFormKey(canonicalizeFormKey(formName.toLowerCase(Locale.ROOT)));
        if (tieredForm == null) return false;
        DruidFormProgress progress = getProgress(player);
        int currentTier = progress.getTier(tieredForm);
        if (currentTier >= 4) return false;
        progress.setTier(tieredForm, currentTier + 1);
        if (isCurrentForm(player, tieredForm.formKey)) {
            animalArmorService.refreshActiveFormArmor(player, tieredForm.formKey, currentTier + 1);
        }
        return true;
    }

    private boolean isCurrentForm(Player player, String formKey) {
        if (player == null || formKey == null) return false;
        try {
            String playerName = player.getDisplayName();
            return formKey.equals(formKeyFromModel(activeForms.get(playerName)));
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean shapeshift(Player player, String formName) {
        String cleanName = formName.toLowerCase(Locale.ROOT);
        String canonicalName = canonicalizeFormKey(cleanName);
        if (!ALLOWED_FORMS.containsKey(canonicalName)) {
            return false;
        }

        if (!isHoldingValidItem(player)) {
            sendPlayerMessage(player, "You must focus through a Druid Totem to channel this form.");
            return false;
        }

        syncTierProgressFromInventory(player);
        return transform(player, ALLOWED_FORMS.get(canonicalName), canonicalName);
    }

    private static String canonicalizeFormKey(String key) {
        if ("sabertooth".equals(key) || "sabretooth".equals(key)) return "tiger";
        if ("jackalope".equals(key)) return "rabbit";
        return key;
    }

    private String formKeyFromModel(String modelId) {
        if (modelId == null) return null;
        return switch (modelId) {
            case "Druid_Bear" -> "bear";
            case "Druid_Ram" -> "ram";
            case "Duck" -> "duck";
            case "Shark_Hammerhead" -> "shark";
            case "Hawk" -> "hawk";
            case "Tiger_Sabertooth" -> "tiger";
            case "Rabbit" -> "rabbit";
            case "Antelope" -> "antelope";
            default -> null;
        };
    }

    private static int clampTier(int tier) {
        return Math.max(1, Math.min(4, tier));
    }

    public boolean transform(Player player, String targetModelID, String shortName) {
        String playerName = player.getDisplayName();
        String currentForm = activeForms.get(playerName);
        String previousForm = formKeyFromModel(currentForm);
        shortName = canonicalizeFormKey(shortName.toLowerCase(Locale.ROOT));

        if (currentForm != null && currentForm.equals(targetModelID)) {
            restoreHuman(player);
            return true;
        }

        if (currentForm != null) {
            maintenanceActive.put(playerName, false);
            if ("ram".equals(previousForm)) {
                toggleHumanoidFlag(player, true);
            }
            if ("duck".equals(previousForm) && !"duck".equals(shortName)) {
                setDuckOxygenBonus(playerName, player, false);
            }
        }

        playPoofEffect(player);

        if (swapModel(player, targetModelID)) {
            activeForms.put(playerName, targetModelID);
            sendPlayerMessage(player, "You have shapeshifted into a " + shortName + "!");

            updateCapabilities(player, shortName);
            swapAbilityItems(player, shortName);
            if ("ram".equals(shortName)) {
                toggleHumanoidFlag(player, false);
            }

            if (animalArmorService.isTrackedForm(shortName)) {
                int requestedTier = detectActiveFormTier(player, shortName);
                animalArmorService.onFormChanged(player, previousForm, shortName, clampTier(requestedTier));
            }

            TieredForm tieredForm = TieredForm.fromFormKey(shortName);
            maintenanceActive.put(playerName, tieredForm != null);
            if (tieredForm != null) {
                startFormMaintenance(player, shortName);
            }
            if ("duck".equals(shortName)) {
                setDuckOxygenBonus(playerName, player, true);
                startDuckMobilityMaintenance(player);
            }
            return true;
        }

        System.out.println("[Druid] Failed to shapeshift " + playerName + " into model: " + targetModelID);
        return false;
    }

    public void restoreHuman(Player player) {
        String playerName = player.getDisplayName();
        maintenanceActive.put(playerName, false);
        playPoofEffect(player);

        updateCapabilities(player, "human");

        if (swapModel(player, "Player")) {
            activeForms.remove(playerName);
            System.out.println("[Druid] " + playerName + " is Human again.");
            sendPlayerMessage(player, "You have returned to your human form.");

            refreshPlayerSkin(player);
            swapAbilityItems(player, "human");
            animalArmorService.onRestoreToHuman(player);
            setDuckOxygenBonus(playerName, player, false);

            toggleHumanoidFlag(player, true);
        }
    }

    public void handlePlayerConnect(PlayerConnectEvent event) {
        if (event == null) return;
        try {
            Player player = event.getPlayer();
            if (player != null) {
                if (!isFormActive(player)) {
                    setDuckOxygenBonus(player.getDisplayName(), player, false);
                    restoreHumanStateOnLogin(player);
                }
                animalArmorService.recoverHumanArmorOnLogin(player, isFormActive(player));
                sanitizeAnimalFormItemOnLogin(player);
            }
        } catch (Exception ignored) {
        }
    }

    public void handlePlayerDisconnect(PlayerDisconnectEvent event) {
        if (event == null) return;
        String playerName = getPlayerName(event.getPlayerRef());
        if (playerName != null) {
            boolean wasTransformed = activeForms.containsKey(playerName);
            maintenanceActive.remove(playerName);
            duckOxygenBonusApplied.remove(playerName);
            activeForms.remove(playerName);
            if (!wasTransformed) {
                animalArmorService.clearPlayerState(playerName);
            }
        }
    }

    private String getPlayerName(com.hypixel.hytale.server.core.universe.PlayerRef playerRef) {
        if (playerRef == null) return null;
        try {
            return playerRef.getUsername();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void restoreHumanStateOnLogin(Player player) {
        if (player == null) return;
        try {
            updateCapabilities(player, "human");
        } catch (Exception ignored) {
        }
        try {
            swapModel(player, "Player");
        } catch (Exception ignored) {
        }
        try {
            refreshPlayerSkin(player);
        } catch (Exception ignored) {
        }
        try {
            toggleHumanoidFlag(player, true);
        } catch (Exception ignored) {
        }
    }

    private void sanitizeAnimalFormItemOnLogin(Player player) {
        if (player == null) return;
        if (isFormActive(player)) return;

        try {
            Method getInventory = player.getClass().getMethod("getInventory");
            Object inventory = getInventory.invoke(player);
            if (inventory == null) return;

            Method getHotbar = inventory.getClass().getMethod("getHotbar");
            Object hotbar = getHotbar.invoke(inventory);
            if (hotbar == null) return;

            Method getStack = findGetStackMethod(hotbar);
            Method setStack = findSetStackMethod(hotbar);
            if (getStack == null || setStack == null) return;

            Class<?> itemStackClass = Class.forName("com.hypixel.hytale.server.core.inventory.ItemStack");
            Constructor<?> constructor = itemStackClass.getConstructor(String.class, int.class);
            Object totemItemStack = constructor.newInstance(DRUID_ITEM, 1);

            if (isAnimalTransformationStack(invokeGetStack(hotbar, getStack, HOTBAR_SLOT_ONE))) {
                invokeSetStack(hotbar, setStack, HOTBAR_SLOT_ONE, totemItemStack);
                return;
            }

            if (isAnimalTransformationStack(invokeGetStack(hotbar, getStack, HOTBAR_SLOT_ONE_ALT))) {
                invokeSetStack(hotbar, setStack, HOTBAR_SLOT_ONE_ALT, totemItemStack);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isFormActive(Player player) {
        if (player == null) return false;
        try {
            String playerName = player.getDisplayName();
            return activeForms.containsKey(playerName);
        } catch (Exception ignored) {
            return false;
        }
    }

    private int detectActiveFormTier(Player player, String form) {
        TieredForm tieredForm = TieredForm.fromFormKey(canonicalizeFormKey(form.toLowerCase(Locale.ROOT)));
        if (tieredForm == null) return 1;
        int tierFromHeldItem = getTierFromHeldItem(player, tieredForm);
        if (tierFromHeldItem > 0) return tierFromHeldItem;
        return getProgress(player).getTier(tieredForm);
    }

    private int getTierFromHeldItem(Player player, TieredForm tieredForm) {
        try {
            Method getInventory = player.getClass().getMethod("getInventory");
            Object inventory = getInventory.invoke(player);
            if (inventory == null) return -1;

            try {
                Method getItemInHand = inventory.getClass().getMethod("getItemInHand");
                int held = detectTierFromItemStack(getItemInHand.invoke(inventory), tieredForm);
                if (held > 0) return held;
            } catch (Exception ignored) {
            }

            Method getHotbar = inventory.getClass().getMethod("getHotbar");
            Object hotbar = getHotbar.invoke(inventory);
            if (hotbar == null) return -1;

            Method getStack = findGetStackMethod(hotbar);
            if (getStack == null) return -1;
            int size = getContainerSize(hotbar);
            if (size <= 0) size = HOTBAR_SIZE;

            for (short slot = 0; slot < Math.min(size, HOTBAR_SIZE); slot++) {
                int detected = detectTierFromItemStack(invokeGetStack(hotbar, getStack, slot), tieredForm);
                if (detected > 0) return detected;
            }
            for (short slot = 1; slot <= Math.min(size, HOTBAR_SIZE); slot++) {
                int detected = detectTierFromItemStack(invokeGetStack(hotbar, getStack, slot), tieredForm);
                if (detected > 0) return detected;
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private int detectTierFromItemStack(Object itemStack, TieredForm form) {
        String itemId = getItemIdFromStack(itemStack);
        if (itemId == null) return -1;
        String lowerId = itemId.toLowerCase(Locale.ROOT);
        for (int tier = 4; tier >= 1; tier--) {
            if (lowerId.contains(form.itemForTier(tier).toLowerCase(Locale.ROOT))) {
                return tier;
            }
        }
        return -1;
    }

    private String getItemIdFromStack(Object itemStack) {
        if (itemStack == null) return null;
        try {
            Method getItemId = itemStack.getClass().getMethod("getItemId");
            Object rawItemId = getItemId.invoke(itemStack);
            return rawItemId instanceof String ? (String) rawItemId : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void swapAbilityItems(Player player, String targetForm) {
        try {
            String canonical = canonicalizeFormKey(targetForm.toLowerCase(Locale.ROOT));
            String itemToGive = DRUID_ITEM;
            TieredForm tieredForm = TieredForm.fromFormKey(canonical);
            if (tieredForm != null) {
                itemToGive = tieredForm.itemForTier(getProgress(player).getTier(tieredForm));
            }

            Method getInventory = player.getClass().getMethod("getInventory");
            Object inventory = getInventory.invoke(player);

            Method getHotbar = inventory.getClass().getMethod("getHotbar");
            Object hotbarContainer = getHotbar.invoke(inventory);

            Class<?> itemStackClass = Class.forName("com.hypixel.hytale.server.core.inventory.ItemStack");
            Constructor<?> constructor = itemStackClass.getConstructor(String.class, int.class);
            Object newItemStack = constructor.newInstance(itemToGive, 1);

            Method getStack = findGetStackMethod(hotbarContainer);
            Method setItem = findSetStackMethod(hotbarContainer);
            if (getStack == null || setItem == null) return;

            short targetSlot = setHotbarItemAndGetSlot(hotbarContainer, getStack, setItem, newItemStack, itemToGive);
            clearDruidItemsFromInventory(inventory, hotbarContainer, targetSlot);
            trySetItemVerified(hotbarContainer, setItem, getStack, targetSlot, newItemStack, itemToGive);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean isHoldingValidItem(Player player) {
        try {
            Method getInventory = player.getClass().getMethod("getInventory");
            Object inventory = getInventory.invoke(player);
            if (inventory == null) return false;

            try {
                Method getItemInHand = inventory.getClass().getMethod("getItemInHand");
                Object itemStack = getItemInHand.invoke(inventory);
                if (isDruidAbilityStack(itemStack)) return true;
            } catch (Exception ignored) { }

            Method getHotbar = inventory.getClass().getMethod("getHotbar");
            Object hotbarContainer = getHotbar.invoke(inventory);
            if (hotbarContainer == null) return false;

            Method getStack = findGetStackMethod(hotbarContainer);
            if (getStack == null) return false;
            return hasDruidItemInHotbar(hotbarContainer, getStack);

        } catch (Exception e) {
            return false;
        }
    }

    private boolean isDruidAbilityStack(Object itemStack) {
        if (itemStack == null) return false;
        try {
            Method getItemId = itemStack.getClass().getMethod("getItemId");
            Object itemIdObj = getItemId.invoke(itemStack);
            if (!(itemIdObj instanceof String)) return false;
            String lowerId = ((String) itemIdObj).toLowerCase(Locale.ROOT);
            return isDruidAbilityItemId(lowerId);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isDruidAbilityItemId(String lowerId) {
        if (lowerId == null) return false;
        if (lowerId.contains("druid_totem")) return true;
        if (lowerId.contains("druid") && lowerId.contains("totem")) return true;
        for (TieredForm tieredForm : TieredForm.values()) {
            for (int tier = 1; tier <= 4; tier++) {
                String itemId = tieredForm.itemForTier(tier).toLowerCase(Locale.ROOT);
                if (lowerId.contains(itemId)) return true;
            }
        }
        return false;
    }

    private boolean isAnimalTransformationItemId(String lowerId) {
        if (lowerId == null) return false;
        for (TieredForm tieredForm : TieredForm.values()) {
            for (int tier = 1; tier <= 4; tier++) {
                String itemId = tieredForm.itemForTier(tier).toLowerCase(Locale.ROOT);
                if (lowerId.contains(itemId)) return true;
            }
        }
        return false;
    }

    private boolean isAnimalTransformationStack(Object itemStack) {
        if (itemStack == null) return false;
        try {
            Method getItemId = itemStack.getClass().getMethod("getItemId");
            Object itemIdObj = getItemId.invoke(itemStack);
            return itemIdObj instanceof String && isAnimalTransformationItemId(((String) itemIdObj).toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasDruidItemInHotbar(Object hotbarContainer, Method getStack) {
        try {
            for (short slot = 0; slot < HOTBAR_SIZE; slot++) {
                Object itemStack = invokeGetStack(hotbarContainer, getStack, slot);
                if (isDruidAbilityStack(itemStack)) return true;
            }

            for (short slot = 1; slot <= HOTBAR_SIZE; slot++) {
                Object itemStack = invokeGetStack(hotbarContainer, getStack, slot);
                if (isDruidAbilityStack(itemStack)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void clearDruidItemsFromHotbar(Object hotbarContainer, Method getStack, Method setItem, Class<?> itemStackClass, short keepSlot) {
        try {
            for (short slot = 0; slot < HOTBAR_SIZE; slot++) {
                if (slot == keepSlot) continue;
                Object itemStack = invokeGetStack(hotbarContainer, getStack, slot);
                if (itemStack == null) continue;
                Method getItemId = itemStack.getClass().getMethod("getItemId");
                Object itemIdObj = getItemId.invoke(itemStack);
                if (!(itemIdObj instanceof String)) continue;
                String lowerId = ((String) itemIdObj).toLowerCase(Locale.ROOT);
                if (!isDruidAbilityItemId(lowerId)) continue;
                try {
                    invokeSetStack(hotbarContainer, setItem, slot, null);
                } catch (Exception ignored) { }
            }

            for (short slot = 1; slot <= HOTBAR_SIZE; slot++) {
                if (slot == keepSlot) continue;
                Object itemStack = invokeGetStack(hotbarContainer, getStack, slot);
                if (itemStack == null) continue;
                Method getItemId = itemStack.getClass().getMethod("getItemId");
                Object itemIdObj = getItemId.invoke(itemStack);
                if (!(itemIdObj instanceof String)) continue;
                String lowerId = ((String) itemIdObj).toLowerCase(Locale.ROOT);
                if (!isDruidAbilityItemId(lowerId)) continue;
                try {
                    invokeSetStack(hotbarContainer, setItem, slot, null);
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    private void clearDruidItemsFromInventory(Object inventory, Object hotbarContainer, short keepHotbarSlot) {
        for (Object container : collectItemContainers(inventory)) {
            Method getStack = findGetStackMethod(container);
            Method setStack = findSetStackMethod(container);
            if (getStack == null || setStack == null) continue;
            boolean isHotbar = (container == hotbarContainer);
            int size = getContainerSize(container);
            if (size <= 0) size = HOTBAR_SIZE;

            for (short slot = 0; slot < size; slot++) {
                if (isHotbar && slot == keepHotbarSlot) continue;
                if (shouldRemoveDruidItem(container, getStack, slot)) {
                    try { invokeSetStack(container, setStack, slot, null); } catch (Exception ignored) { }
                }
            }

            for (short slot = 1; slot <= size; slot++) {
                if (isHotbar && slot == keepHotbarSlot) continue;
                if (shouldRemoveDruidItem(container, getStack, slot)) {
                    try { invokeSetStack(container, setStack, slot, null); } catch (Exception ignored) { }
                }
            }
        }
    }

    private boolean shouldRemoveDruidItem(Object container, Method getStack, short slot) {
        try {
            Object itemStack = invokeGetStack(container, getStack, slot);
            if (itemStack == null) return false;
            Method getItemId = itemStack.getClass().getMethod("getItemId");
            Object itemIdObj = getItemId.invoke(itemStack);
            if (!(itemIdObj instanceof String)) return false;
            String lowerId = ((String) itemIdObj).toLowerCase(Locale.ROOT);
            return isDruidAbilityItemId(lowerId);
        } catch (Exception ignored) {
            return false;
        }
    }

    private Short findDruidItemSlot(Object hotbarContainer, Method getStack) {
        try {
            for (short slot = 0; slot < HOTBAR_SIZE; slot++) {
                Object itemStack = invokeGetStack(hotbarContainer, getStack, slot);
                if (isDruidAbilityStack(itemStack)) return slot;
            }
            for (short slot = 1; slot <= HOTBAR_SIZE; slot++) {
                Object itemStack = invokeGetStack(hotbarContainer, getStack, slot);
                if (isDruidAbilityStack(itemStack)) return slot;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private short getActiveHotbarSlotFallback(Object inventory) {
        try {
            Method getActiveSlot = inventory.getClass().getMethod("getActiveHotbarSlot");
            return (short) ((byte) getActiveSlot.invoke(inventory));
        } catch (Exception ignored) {
            return HOTBAR_SLOT_ONE;
        }
    }

    private boolean trySetItem(Object hotbarContainer, Method setItem, short slot, Object itemStack) {
        try {
            invokeSetStack(hotbarContainer, setItem, slot, itemStack);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean trySetItemVerified(Object hotbarContainer, Method setItem, Method getStack, short slot, Object itemStack, String expectedItemId) {
        try {
            invokeSetStack(hotbarContainer, setItem, slot, itemStack);
            Object readBack = invokeGetStack(hotbarContainer, getStack, slot);
            if (readBack == null) return false;
            Method getItemId = readBack.getClass().getMethod("getItemId");
            Object itemIdObj = getItemId.invoke(readBack);
            if (!(itemIdObj instanceof String)) return false;
            String lowerId = ((String) itemIdObj).toLowerCase(Locale.ROOT);
            return lowerId.contains(expectedItemId.toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
            return false;
        }
    }

    private short setHotbarItemAndGetSlot(Object hotbarContainer, Method getStack, Method setItem, Object newItemStack, String expectedItemId) {
        if (trySetItemVerified(hotbarContainer, setItem, getStack, HOTBAR_SLOT_ONE, newItemStack, expectedItemId)) {
            return HOTBAR_SLOT_ONE;
        }
        if (trySetItemVerified(hotbarContainer, setItem, getStack, HOTBAR_SLOT_ONE_ALT, newItemStack, expectedItemId)) {
            return HOTBAR_SLOT_ONE_ALT;
        }
        return HOTBAR_SLOT_ONE;
    }

    private Method findGetStackMethod(Object hotbarContainer) {
        Method fallback = null;
        for (Method m : hotbarContainer.getClass().getMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (m.getName().equals("getItemStackForSlot")) return m;
            if (fallback == null && m.getName().toLowerCase(Locale.ROOT).contains("getitemstack")) {
                fallback = m;
            }
        }
        return fallback;
    }

    private Method findSetStackMethod(Object hotbarContainer) {
        Method fallback = null;
        for (Method m : hotbarContainer.getClass().getMethods()) {
            if (m.getParameterCount() != 2) continue;
            if (m.getName().equals("setItemStackForSlot")) return m;
            if (fallback == null && m.getName().toLowerCase(Locale.ROOT).contains("setitemstack")) {
                fallback = m;
            }
        }
        return fallback;
    }

    private Object invokeGetStack(Object hotbarContainer, Method getStack, short slot) throws Exception {
        Class<?> paramType = getStack.getParameterTypes()[0];
        Object arg = coerceSlot(slot, paramType);
        return getStack.invoke(hotbarContainer, arg);
    }

    private void invokeSetStack(Object hotbarContainer, Method setStack, short slot, Object itemStack) throws Exception {
        Class<?> slotType = setStack.getParameterTypes()[0];
        Object slotArg = coerceSlot(slot, slotType);
        setStack.invoke(hotbarContainer, slotArg, itemStack);
    }

    private Object coerceSlot(short slot, Class<?> paramType) {
        if (paramType == short.class || paramType == Short.class) return slot;
        if (paramType == int.class || paramType == Integer.class) return (int) slot;
        if (paramType == byte.class || paramType == Byte.class) return (byte) slot;
        return slot;
    }

    private void sendPlayerMessage(Player player, String text) {
        try {
            Object playerRef = getPlayerRef(player);
            Class<?> formattedMsgClass = Class.forName("com.hypixel.hytale.protocol.FormattedMessage");
            Object component = formattedMsgClass.getConstructor().newInstance();
            formattedMsgClass.getField("rawText").set(component, text);

            Class<?> msgClass = Class.forName("com.hypixel.hytale.server.core.Message");
            Object message = msgClass.getConstructor(formattedMsgClass).newInstance(component);

            Method sendMessage = playerRef.getClass().getMethod("sendMessage", msgClass);
            sendMessage.invoke(playerRef, message);
        } catch (Exception e) { }
    }

    private void refreshPlayerSkin(Player player) {
        try {
            Object store = getEntityStore(player);
            Object ref = getInternalRef(player);

            Class<?> skinCompClass = Class.forName("com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent");
            Method getCompType = skinCompClass.getMethod("getComponentType");
            Object compType = getCompType.invoke(null);

            Method getComponent = store.getClass().getMethod("getComponent", Class.forName("com.hypixel.hytale.component.Ref"), Class.forName("com.hypixel.hytale.component.ComponentType"));
            Object skinComponent = getComponent.invoke(store, ref, compType);

            if (skinComponent != null) {
                Method setOutdated = skinCompClass.getMethod("setNetworkOutdated");
                setOutdated.invoke(skinComponent);
            }
        } catch (Exception e) { }
    }

    private void startFormMaintenance(Player player, String shortName) {
        if (player == null) return;
        final String normalizedForm = canonicalizeFormKey(shortName.toLowerCase(Locale.ROOT));
        if (TieredForm.fromFormKey(normalizedForm) == null) return;

        final String playerName = player.getDisplayName();

        scheduler.schedule(() -> {
            if (!maintenanceActive.getOrDefault(playerName, false)) return;
            String activeForm = formKeyFromModel(activeForms.get(playerName));
            if (!normalizedForm.equals(activeForm)) return;
            if (player.getWorld() == null) {
                maintenanceActive.put(playerName, false);
                return;
            }

            try {
                player.getWorld().execute(() -> {
                    try {
                        String currentActiveForm = formKeyFromModel(activeForms.get(playerName));
                        if (!normalizedForm.equals(currentActiveForm)) return;
                        int requestedTier = detectActiveFormTier(player, normalizedForm);
                        animalArmorService.refreshActiveFormArmor(player, normalizedForm, clampTier(requestedTier));
                        if (normalizedForm.equals("shark")) {
                            modifyStat(player, "Oxygen", true, 10.0f);
                        }
                        if (maintenanceActive.getOrDefault(playerName, false)) {
                            startFormMaintenance(player, normalizedForm);
                        }
                    } catch (Exception e) { }
                });
            } catch (Exception e) { e.printStackTrace(); }
        }, 2000, TimeUnit.MILLISECONDS);
    }

    private void startDuckMobilityMaintenance(Player player) {
        if (player == null) return;
        final String playerName = player.getDisplayName();

        scheduler.schedule(() -> {
            String activeForm = formKeyFromModel(activeForms.get(playerName));
            if (!"duck".equals(activeForm)) return;
            if (player.getWorld() == null) {
                return;
            }

            try {
                player.getWorld().execute(() -> {
                    try {
                        String currentActiveForm = formKeyFromModel(activeForms.get(playerName));
                        if (!"duck".equals(currentActiveForm)) return;
                        updateCapabilities(player, "duck");
                        if ("duck".equals(formKeyFromModel(activeForms.get(playerName)))) {
                            startDuckMobilityMaintenance(player);
                        }
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
        }, 700, TimeUnit.MILLISECONDS);
    }

    private void updateCapabilities(Player player, String shortName) {
        try {
            Object movementManager = getMovementManager(player);
            if (movementManager == null) return;

            Method getSettings = movementManager.getClass().getMethod("getSettings");
            Object settings = getSettings.invoke(movementManager);

            Method getDefaultSettings = movementManager.getClass().getMethod("getDefaultSettings");
            Object defaultSettings = getDefaultSettings.invoke(movementManager);

            float baseSpeed = 5.5f;
            float jumpForce = 11.8f;
            float flySpeed = 10.32f;
            float dragCoefficient = 0.5f;
            float fallMomentumLoss = 0.1f;
            boolean canFly = false;
            boolean duckUnderwater = false;

            switch (shortName.toLowerCase(Locale.ROOT)) {
                case "antelope":
                    baseSpeed = 5.5f * 1.75f;
                    break;
                case "hawk":
                    baseSpeed = 5.5f * 1.0f;
                    flySpeed = 10.32f * 1.25f;
                    canFly = true;
                    break;
                case "duck":
                    baseSpeed = 5.5f * 0.8f;
                    flySpeed = 10.32f * 1.0f;
                    canFly = true;
                    if (isPlayerInWater(player)) {
                        duckUnderwater = true;
                        baseSpeed = DUCK_UNDERWATER_BASE_SPEED;
                        dragCoefficient = DUCK_UNDERWATER_DRAG;
                        canFly = false;
                    }
                    break;
                case "ram":
                    baseSpeed = 5.5f * 1.25f;
                    break;
                case "tiger":
                    baseSpeed = 5.5f * 1.5f;
                    jumpForce = 14.5f;
                    break;
                case "shark":
                    baseSpeed = 12.0f;
                    dragCoefficient = 0.01f;
                    break;
                case "rabbit":
                    baseSpeed = 8.0f;
                    jumpForce = 17.0f;
                    dragCoefficient = 14.0f;
                    fallMomentumLoss = 1.0f;
                    break;
                case "bear":
                    baseSpeed = 6.0f;
                    break;
            }

            float tierMultiplier = getTierMultiplier(player, shortName);
            if (tierMultiplier != 1.0f) {
                baseSpeed *= tierMultiplier;
                jumpForce *= tierMultiplier;
            }

            Object[] targets = {settings, defaultSettings};

            for (Object target : targets) {
                if (target == null) continue;
                if (!canFly) {
                    setField(target, "canFly", false);
                    setField(target, "verticalFlySpeed", -1.0f);
                } else {
                    setField(target, "canFly", true);
                    setField(target, "verticalFlySpeed", flySpeed);
                }
                setField(target, "baseSpeed", baseSpeed);
                setField(target, "jumpForce", jumpForce);
                setField(target, "horizontalFlySpeed", flySpeed);
                setField(target, "dragCoefficient", dragCoefficient);
                setField(target, "fallMomentumLoss", fallMomentumLoss);
                if ("duck".equals(shortName.toLowerCase(Locale.ROOT))) {
                    if (duckUnderwater) {
                        setField(target, "sprintMultiplier", DUCK_UNDERWATER_SPRINT_MULTIPLIER);
                        setField(target, "sprintSpeedMultiplier", DUCK_UNDERWATER_SPRINT_MULTIPLIER);
                        setField(target, "sprintingSpeedMultiplier", DUCK_UNDERWATER_SPRINT_MULTIPLIER);
                    } else {
                        restoreFloatFieldFromDefault(target, defaultSettings, "sprintMultiplier");
                        restoreFloatFieldFromDefault(target, defaultSettings, "sprintSpeedMultiplier");
                        restoreFloatFieldFromDefault(target, defaultSettings, "sprintingSpeedMultiplier");
                    }
                }
            }

            if (!canFly) {
                forceStopFlying(player);
            }

            Object playerRef = getPlayerRef(player);
            Method getPacketHandler = playerRef.getClass().getMethod("getPacketHandler");
            Object packetHandler = getPacketHandler.invoke(playerRef);
            Method updateMethod = movementManager.getClass().getMethod("update", Class.forName("com.hypixel.hytale.server.core.io.PacketHandler"));
            updateMethod.invoke(movementManager, packetHandler);

        } catch (Exception e) {}
    }

    private void forceStopFlying(Player player) {
        try {
            Object movementStatesComponent = getMovementStatesComponent(player);
            if (movementStatesComponent != null) {
                Class<?> movementStatesClass = Class.forName("com.hypixel.hytale.protocol.MovementStates");

                Method getMovementStates = movementStatesComponent.getClass().getMethod("getMovementStates");
                Object movementStates = getMovementStates.invoke(movementStatesComponent);

                Object newMovementStates = (movementStates == null)
                        ? movementStatesClass.getConstructor().newInstance()
                        : movementStatesClass.getConstructor(movementStatesClass).newInstance(movementStates);

                movementStatesClass.getField("flying").setBoolean(newMovementStates, false);
                movementStatesClass.getField("gliding").setBoolean(newMovementStates, false);

                Method setMovementStates = movementStatesComponent.getClass().getMethod("setMovementStates", movementStatesClass);
                setMovementStates.invoke(movementStatesComponent, newMovementStates);
            }
        } catch (Exception ignored) {
        }

        sendFlightPacket(player, false);
    }

    private void sendFlightPacket(Player player, boolean isFlying) {
        try {
            Class<?> savedStatesClass = Class.forName("com.hypixel.hytale.protocol.SavedMovementStates");
            Constructor<?> savedStatesCtor = savedStatesClass.getConstructor(boolean.class);
            Object savedStates = savedStatesCtor.newInstance(isFlying);

            Class<?> setStatesClass = Class.forName("com.hypixel.hytale.protocol.packets.player.SetMovementStates");
            Constructor<?> setStatesCtor = setStatesClass.getConstructor(savedStatesClass);
            Object packet = setStatesCtor.newInstance(savedStates);

            sendPacket(player, packet);
            System.out.println("[Druid] Synced Flight State Packet: " + isFlying);
        } catch (Exception e) {
            System.out.println("[Druid] Packet Sync Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void playPoofEffect(Player player) {
        try {
            Object transform = getTransformComponent(player);
            Method getPos = transform.getClass().getMethod("getPosition");
            Object vec3d = getPos.invoke(transform);

            double x = (double) vec3d.getClass().getMethod("getX").invoke(vec3d);
            double y = (double) vec3d.getClass().getMethod("getY").invoke(vec3d);
            double z = (double) vec3d.getClass().getMethod("getZ").invoke(vec3d);

            Class<?> posClass = Class.forName("com.hypixel.hytale.protocol.Position");
            Object position = posClass.getConstructor(double.class, double.class, double.class)
                    .newInstance(x, y + 1.0, z);

            Class<?> particlePacketClass = Class.forName("com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem");
            Class<?> dirClass = Class.forName("com.hypixel.hytale.protocol.Direction");
            Class<?> colorClass = Class.forName("com.hypixel.hytale.protocol.Color");

            Object particlePacket = particlePacketClass.getConstructor(String.class, posClass, dirClass, float.class, colorClass)
                    .newInstance("Potion_Morph_Burst", position, null, 1.0f, null);

            sendPacket(player, particlePacket);

            Class<?> soundConfigClass = Class.forName("com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent");
            Method getAssetMap = soundConfigClass.getMethod("getAssetMap");
            Object assetMap = getAssetMap.invoke(null);
            Method getIndex = assetMap.getClass().getMethod("getIndex", Object.class);

            int soundIndex = (int) getIndex.invoke(assetMap, "SFX_Portal_Neutral_Teleport_Local");

            if (soundIndex != -1) {
                Class<?> soundPacketClass = Class.forName("com.hypixel.hytale.protocol.packets.world.PlaySoundEvent3D");
                Class<?> soundCatClass = Class.forName("com.hypixel.hytale.protocol.SoundCategory");

                Object[] enums = (Object[]) soundCatClass.getEnumConstants();
                Object targetCategory = (enums.length > 0) ? enums[0] : null;

                if (targetCategory != null) {
                    for (Object e : enums) {
                        String name = e.toString().toLowerCase(Locale.ROOT);
                        if (name.contains("sfx") || name.contains("effect") || name.contains("master")) {
                            targetCategory = e;
                            break;
                        }
                    }

                    Object soundPacket = soundPacketClass.getConstructor(int.class, soundCatClass, posClass, float.class, float.class)
                            .newInstance(soundIndex, targetCategory, position, 1.0f, 1.0f);

                    sendPacket(player, soundPacket);
                }
            }

        } catch (Exception e) {}
    }

    private void setDuckOxygenBonus(String playerName, Player player, boolean apply) {
        if (playerName == null || player == null) return;
        if (apply) {
            if (duckOxygenBonusApplied.add(playerName)) {
                modifyStat(player, "Oxygen", true, DUCK_OXYGEN_BONUS);
            }
            return;
        }
        if (duckOxygenBonusApplied.remove(playerName)) {
            modifyStat(player, "Oxygen", false, DUCK_OXYGEN_BONUS);
        }
    }

    private boolean isPlayerInWater(Player player) {
        if (player == null) return false;
        try {
            Object movementStatesComponent = getMovementStatesComponent(player);
            if (movementStatesComponent == null) return false;

            Method getMovementStates = movementStatesComponent.getClass().getMethod("getMovementStates");
            Object movementStates = getMovementStates.invoke(movementStatesComponent);
            if (movementStates == null) return false;

            return getBooleanMovementState(movementStates, "swimming")
                    || getBooleanMovementState(movementStates, "underwater")
                    || getBooleanMovementState(movementStates, "inWater");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean getBooleanMovementState(Object stateObject, String fieldOrMethod) {
        if (stateObject == null || fieldOrMethod == null) return false;
        try {
            Field field = stateObject.getClass().getField(fieldOrMethod);
            return field.getBoolean(stateObject);
        } catch (Exception ignored) {
        }
        try {
            Method method = stateObject.getClass().getMethod("is" + Character.toUpperCase(fieldOrMethod.charAt(0)) + fieldOrMethod.substring(1));
            Object value = method.invoke(stateObject);
            return value instanceof Boolean && (Boolean) value;
        } catch (Exception ignored) {
        }
        try {
            Method method = stateObject.getClass().getMethod("get" + Character.toUpperCase(fieldOrMethod.charAt(0)) + fieldOrMethod.substring(1));
            Object value = method.invoke(stateObject);
            return value instanceof Boolean && (Boolean) value;
        } catch (Exception ignored) {
        }
        return false;
    }

    private void restoreFloatFieldFromDefault(Object target, Object defaultSource, String fieldName) {
        if (target == null || defaultSource == null || fieldName == null) return;
        Float defaultValue = readFloatField(defaultSource, fieldName);
        if (defaultValue != null) {
            setField(target, fieldName, defaultValue);
        }
    }

    private Float readFloatField(Object source, String fieldName) {
        if (source == null || fieldName == null) return null;
        try {
            Field field = source.getClass().getField(fieldName);
            Object value = field.get(source);
            if (value instanceof Number) return ((Number) value).floatValue();
        } catch (Exception ignored) {
        }
        try {
            Class<?> clazz = source.getClass();
            while (clazz != null) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object value = field.get(source);
                    if (value instanceof Number) return ((Number) value).floatValue();
                    break;
                } catch (NoSuchFieldException ex) {
                    clazz = clazz.getSuperclass();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void sendPacket(Player player, Object packet) {
        try {
            Object playerRef = getPlayerRef(player);
            Method getPacketHandler = playerRef.getClass().getMethod("getPacketHandler");
            Object handler = getPacketHandler.invoke(playerRef);
            Method write = handler.getClass().getMethod("writeNoCache", Class.forName("com.hypixel.hytale.protocol.ToClientPacket"));
            write.invoke(handler, packet);
        } catch (Exception e) {}
    }

    private void modifyStat(Player player, String statName, boolean increase, float amount) {
        try {
            Object statMap = getStatMap(player);
            int index = getStatIndex(statName);
            Method mod = statMap.getClass().getMethod("modifyStatValue", int.class, int.class);
            int val = increase ? (int)amount : -(int)amount;
            mod.invoke(statMap, index, val);
        } catch (Exception e) { maximizeStat(player, statName); }
    }

    private void maximizeStat(Player player, String statName) {
        try {
            Object statMap = getStatMap(player);
            int index = getStatIndex(statName);
            Method max = statMap.getClass().getMethod("maximizeStatValue", int.class);
            max.invoke(statMap, index);
        } catch (Exception e) {}
    }

    private void toggleHumanoidFlag(Player player, boolean enable) {
        try {
            Object store = getEntityStore(player);
            Object ref = getInternalRef(player);

            Class<?> tagCompClass = Class.forName("com.hypixel.hytale.server.core.modules.entity.component.TagComponent");
            Method getCompType = tagCompClass.getMethod("getComponentType");
            Object compType = getCompType.invoke(null);

            Method getComponent = store.getClass().getMethod("getComponent", Class.forName("com.hypixel.hytale.component.Ref"), Class.forName("com.hypixel.hytale.component.ComponentType"));
            Object tagComponent = getComponent.invoke(store, ref, compType);

            if (tagComponent != null) {
                Method getTags = tagCompClass.getMethod("getTags");
                Set<String> tags = (Set<String>) getTags.invoke(tagComponent);

                if (enable) {
                    tags.add("Humanoid");
                } else {
                    tags.remove("Humanoid");
                }

                Method setOutdated = tagCompClass.getMethod("setNetworkOutdated");
                setOutdated.invoke(tagComponent);
            }
        } catch (Exception e) { }
    }

    private Object getTransformComponent(Player player) throws Exception {
        Object store = getEntityStore(player);
        Object ref = getInternalRef(player);
        Class<?> compClass = Class.forName("com.hypixel.hytale.server.core.modules.entity.component.TransformComponent");
        Method getCompType = compClass.getMethod("getComponentType");
        Object compType = getCompType.invoke(null);
        Method getComponent = store.getClass().getMethod("getComponent", Class.forName("com.hypixel.hytale.component.Ref"), Class.forName("com.hypixel.hytale.component.ComponentType"));
        return getComponent.invoke(store, ref, compType);
    }

    private Object getStatMap(Player player) throws Exception {
        Object store = getEntityStore(player);
        Object ref = getInternalRef(player);
        Class<?> statMapClass = Class.forName("com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap");
        Method getCompType = statMapClass.getMethod("getComponentType");
        Object compType = getCompType.invoke(null);
        Method getComponent = store.getClass().getMethod("getComponent", Class.forName("com.hypixel.hytale.component.Ref"), Class.forName("com.hypixel.hytale.component.ComponentType"));
        return getComponent.invoke(store, ref, compType);
    }

    private int getStatIndex(String statName) throws Exception {
        Class<?> statTypeClass = Class.forName("com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType");
        Method getAssetMap = statTypeClass.getMethod("getAssetMap");
        Object assetMap = getAssetMap.invoke(null);
        Method getIndex = assetMap.getClass().getMethod("getIndex", Object.class);
        return (int) getIndex.invoke(assetMap, statName);
    }

    private Object getMovementManager(Player player) throws Exception {
        Object store = getEntityStore(player);
        Object ref = getInternalRef(player);
        Class<?> managerClass = Class.forName("com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager");
        Method getCompType = managerClass.getMethod("getComponentType");
        Object compType = getCompType.invoke(null);
        Method getComponent = store.getClass().getMethod("getComponent", Class.forName("com.hypixel.hytale.component.Ref"), Class.forName("com.hypixel.hytale.component.ComponentType"));
        return getComponent.invoke(store, ref, compType);
    }

    private Object getMovementStatesComponent(Player player) throws Exception {
        Object store = getEntityStore(player);
        Object ref = getInternalRef(player);
        Class<?> compClass = Class.forName("com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent");
        Method getCompType = compClass.getMethod("getComponentType");
        Object compType = getCompType.invoke(null);
        Method getComponent = store.getClass().getMethod("getComponent", Class.forName("com.hypixel.hytale.component.Ref"), Class.forName("com.hypixel.hytale.component.ComponentType"));
        return getComponent.invoke(store, ref, compType);
    }

    private Object getEntityStore(Player player) throws Exception {
        Object ref = getInternalRef(player);
        Method getStore = ref.getClass().getMethod("getStore");
        return getStore.invoke(ref);
    }

    private Object getInternalRef(Player player) throws Exception {
        Object playerRef = getPlayerRef(player);
        Method getRef = playerRef.getClass().getMethod("getReference");
        return getRef.invoke(playerRef);
    }

    private Object getPlayerRef(Object player) {
        try {
            Field f = getFieldDeep(player.getClass(), "playerRef");
            if (f != null) { f.setAccessible(true); return f.get(player); }
        } catch (Exception e) { }
        return null;
    }

    private boolean swapModel(Object player, String assetId) {
        try {
            Class<?> modelAssetClass = Class.forName("com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset");
            Class<?> modelClass = Class.forName("com.hypixel.hytale.server.core.asset.type.model.config.Model");
            Method getMapMethod = modelAssetClass.getMethod("getAssetMap");
            Object assetMap = getMapMethod.invoke(null);

            Method getAssetMethod = null;
            for(Method m : assetMap.getClass().getMethods()) {
                if(m.getName().equals("getAsset") && m.getParameterCount() == 1) {
                    getAssetMethod = m; break;
                }
            }
            getAssetMethod.setAccessible(true);

            Object rawAsset = getAssetMethod.invoke(assetMap, assetId);
            if (rawAsset == null) rawAsset = getAssetMethod.invoke(assetMap, "Hytale:" + assetId);
            if (rawAsset == null) rawAsset = getAssetMethod.invoke(assetMap, "druid:" + assetId);
            if (rawAsset == null) rawAsset = getAssetMethod.invoke(assetMap, "Druid:" + assetId);
            if (rawAsset == null) return false;

            Method createUnitScale = modelClass.getMethod("createUnitScaleModel", modelAssetClass);
            Object newModel = createUnitScale.invoke(null, rawAsset);

            return injectRawModel(player, newModel);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean injectRawModel(Object player, Object modelObject) {
        try {
            Object component = getModelComponent(player);
            Class<?> modelCompClass = Class.forName("com.hypixel.hytale.server.core.modules.entity.component.ModelComponent");

            Field modelField = modelCompClass.getDeclaredField("model");
            modelField.setAccessible(true);
            modelField.set(component, modelObject);

            Field outdatedField = modelCompClass.getDeclaredField("isNetworkOutdated");
            outdatedField.setAccessible(true);
            outdatedField.set(component, true);

            return true;
        } catch (Exception e) { return false; }
    }

    private Object getModelComponent(Object player) throws Exception {
        Object store = getEntityStore((Player)player);
        Object ref = getInternalRef((Player)player);
        Class<?> modelCompClass = Class.forName("com.hypixel.hytale.server.core.modules.entity.component.ModelComponent");
        Method getTypeMethod = modelCompClass.getMethod("getComponentType");
        Object compType = getTypeMethod.invoke(null);
        Method getCompMethod = store.getClass().getMethod("getComponent", Class.forName("com.hypixel.hytale.component.Ref"), Class.forName("com.hypixel.hytale.component.ComponentType"));
        return getCompMethod.invoke(store, ref, compType);
    }

    private void setField(Object object, String fieldName, Object value) {
        try {
            Field f = object.getClass().getField(fieldName);
            f.set(object, value);
        } catch (Exception e) {
            try {
                Class<?> clazz = object.getClass();
                while (clazz != null) {
                    try {
                        Field f = clazz.getDeclaredField(fieldName);
                        f.setAccessible(true);
                        f.set(object, value);
                        return;
                    } catch (NoSuchFieldException ex) { clazz = clazz.getSuperclass(); }
                }
            } catch (Exception ignored) { }
        }
    }

    private DruidFormProgress getProgress(Player player) {
        return PLAYER_PROGRESS.computeIfAbsent(getPlayerUuid(player), ignored -> new DruidFormProgress());
    }

    private UUID getPlayerUuid(Player player) {
        try {
            Method getUuid = player.getClass().getMethod("getUuid");
            Object value = getUuid.invoke(player);
            if (value instanceof UUID) return (UUID) value;
        } catch (Exception ignored) {
        }
        try {
            Method getId = player.getClass().getMethod("getId");
            Object value = getId.invoke(player);
            if (value instanceof UUID) return (UUID) value;
        } catch (Exception ignored) {
        }
        return UUID.nameUUIDFromBytes(player.getDisplayName().getBytes());
    }

    private float getTierMultiplier(Player player, String shortName) {
        TieredForm tieredForm = TieredForm.fromFormKey(canonicalizeFormKey(shortName.toLowerCase(Locale.ROOT)));
        if (tieredForm == null) return 1.0f;
        int tier = getProgress(player).getTier(tieredForm);
        switch (tier) {
            case 2: return 1.15f;
            case 3: return 1.3f;
            case 4: return 1.5f;
            default: return 1.0f;
        }
    }

    private void syncTierProgressFromInventory(Player player) {
        try {
            Method getInventory = player.getClass().getMethod("getInventory");
            Object inventory = getInventory.invoke(player);
            if (inventory == null) return;

            DruidFormProgress progress = getProgress(player);

            for (Object container : collectItemContainers(inventory)) {
                syncTierFromContainer(container, progress);
            }
        } catch (Exception ignored) {
        }
    }

    private void syncTierFromContainer(Object container, DruidFormProgress progress) throws Exception {
        Method getStack = findGetStackMethod(container);
        int size = getContainerSize(container);

        if (getStack != null && size > 0) {
            for (short slot = 0; slot < size; slot++) {
                updateTierFromItemStack(invokeGetStack(container, getStack, slot), progress);
            }
            for (short slot = 1; slot <= size; slot++) {
                updateTierFromItemStack(invokeGetStack(container, getStack, slot), progress);
            }
            return;
        }

        Method getAll = findGetAllStacksMethod(container);
        if (getAll != null) {
            Object result = getAll.invoke(container);
            if (result instanceof Object[]) {
                for (Object itemStack : (Object[]) result) {
                    updateTierFromItemStack(itemStack, progress);
                }
            } else if (result instanceof Iterable) {
                for (Object itemStack : (Iterable<?>) result) {
                    updateTierFromItemStack(itemStack, progress);
                }
            }
            return;
        }

        if (getStack != null) {
            for (short slot = 0; slot < HOTBAR_SIZE; slot++) {
                updateTierFromItemStack(invokeGetStack(container, getStack, slot), progress);
            }
            for (short slot = 1; slot <= HOTBAR_SIZE; slot++) {
                updateTierFromItemStack(invokeGetStack(container, getStack, slot), progress);
            }
        }
    }

    private void updateTierFromItemStack(Object itemStack, DruidFormProgress progress) throws Exception {
        if (itemStack == null) return;
        Method getItemId = itemStack.getClass().getMethod("getItemId");
        Object rawItemId = getItemId.invoke(itemStack);
        if (!(rawItemId instanceof String)) return;

        String itemId = ((String) rawItemId).toLowerCase(Locale.ROOT);
        for (TieredForm tieredForm : TieredForm.values()) {
            for (int tier = 4; tier >= 1; tier--) {
                if (itemId.contains(tieredForm.itemForTier(tier).toLowerCase(Locale.ROOT))) {
                    if (progress.getTier(tieredForm) < tier) {
                        progress.setTier(tieredForm, tier);
                    }
                    return;
                }
            }
        }
    }

    private List<Object> collectItemContainers(Object inventory) {
        List<Object> containers = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        try {
            Method getHotbar = inventory.getClass().getMethod("getHotbar");
            Object hotbar = getHotbar.invoke(inventory);
            if (hotbar != null && seen.add(hotbar)) containers.add(hotbar);
        } catch (Exception ignored) {
        }

        for (Method m : inventory.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            Class<?> rt = m.getReturnType();
            if (rt == null) continue;
            String typeName = rt.getName().toLowerCase(Locale.ROOT);
            if (!(typeName.contains("item") && typeName.contains("container"))) continue;
            try {
                Object container = m.invoke(inventory);
                if (container != null && seen.add(container)) {
                    containers.add(container);
                }
            } catch (Exception ignored) {
            }
        }

        return containers;
    }

    private int getContainerSize(Object container) {
        try {
            for (Method m : container.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                Class<?> rt = m.getReturnType();
                if (!(rt == int.class || rt == Integer.class || rt == short.class || rt == Short.class || rt == byte.class || rt == Byte.class)) continue;
                String name = m.getName().toLowerCase(Locale.ROOT);
                if (!(name.contains("size") || name.contains("count") || name.contains("slot"))) continue;
                Object val = m.invoke(container);
                if (val instanceof Number) return ((Number) val).intValue();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private Method findGetAllStacksMethod(Object container) {
        for (Method m : container.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            String name = m.getName().toLowerCase(Locale.ROOT);
            if (name.contains("getitemstacks") || name.contains("getitems") || name.contains("getcontents")) {
                return m;
            }
        }
        return null;
    }

    private Field getFieldDeep(Class<?> clazz, String name) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (Exception e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
}
