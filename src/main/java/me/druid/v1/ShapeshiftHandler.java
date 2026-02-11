package me.druid.v1;

import com.hypixel.hytale.server.core.entity.entities.Player;

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

    private static final String DRUID_ITEM = "Druid_Totem";

    private static final Map<String, String> ALLOWED_FORMS = new HashMap<>();

    static {
        ALLOWED_FORMS.put("bear", "Bear_Grizzly");
        ALLOWED_FORMS.put("ram", "Ram");
        ALLOWED_FORMS.put("duck", "Duck");
        ALLOWED_FORMS.put("shark", "Shark_Hammerhead");
        ALLOWED_FORMS.put("hawk", "Hawk");
        ALLOWED_FORMS.put("sabertooth", "Tiger_Sabertooth");
        ALLOWED_FORMS.put("tiger", "Tiger_Sabertooth");
        ALLOWED_FORMS.put("jackalope", "Rabbit");
        ALLOWED_FORMS.put("antelope", "Antelope");
    }

    public static class AbilityConfig { }

    public boolean shapeshift(Player player, String formName) {
        String cleanName = formName.toLowerCase();
        if (!ALLOWED_FORMS.containsKey(cleanName)) {
            return false;
        }

        if (!isHoldingValidItem(player)) {
            sendPlayerMessage(player, "You must focus through a Druid Totem to channel this form.");
            return false;
        }

        transform(player, ALLOWED_FORMS.get(cleanName), cleanName);
        return true;
    }

    public void transform(Player player, String targetModelID, String shortName) {
        String playerName = player.getDisplayName();
        String currentForm = activeForms.get(playerName);

        if (currentForm != null && currentForm.equals(targetModelID)) {
            restoreHuman(player);
            return;
        }

        if (currentForm != null) restoreHuman(player);

        playPoofEffect(player);

        if (swapModel(player, targetModelID)) {
            activeForms.put(playerName, targetModelID);
            System.out.println("[Druid] " + playerName + " became a " + shortName);
            sendPlayerMessage(player, "You have shapeshifted into a " + shortName + "!");

            updateCapabilities(player, shortName);
            swapAbilityItems(player, shortName);

            maintenanceActive.put(playerName, true);
            startFormMaintenance(player, shortName);
        }
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
        }
    }

    private void swapAbilityItems(Player player, String targetForm) {
        try {
            String itemToGive = null;
            switch (targetForm.toLowerCase()) {
                case "bear": itemToGive = "Bear_Skin"; break;
                case "antelope":
                case "ram": itemToGive = "Ram_Horn"; break;
                case "sabertooth":
                case "tiger": itemToGive = "Tiger_Claw"; break;
                case "shark": itemToGive = "Shark_Tooth"; break;
                case "human":
                case "hawk":
                case "duck":
                case "jackalope":
                    itemToGive = DRUID_ITEM; break;
            }

            if (itemToGive == null) return;

            Method getInventory = player.getClass().getMethod("getInventory");
            Object inventory = getInventory.invoke(player);

            Method getActiveSlot = inventory.getClass().getMethod("getActiveHotbarSlot");
            byte slotIndex = (byte) getActiveSlot.invoke(inventory);

            Method getHotbar = inventory.getClass().getMethod("getHotbar");
            Object hotbarContainer = getHotbar.invoke(inventory);

            Class<?> itemStackClass = Class.forName("com.hypixel.hytale.server.core.inventory.ItemStack");
            Constructor<?> constructor = itemStackClass.getConstructor(String.class, int.class);
            Object newItemStack = constructor.newInstance(itemToGive, 1);

            Method setItem = hotbarContainer.getClass().getMethod("setItemStackForSlot", short.class, itemStackClass);
            setItem.invoke(hotbarContainer, (short) slotIndex, newItemStack);

            System.out.println("[Druid] Successfully set slot " + slotIndex + " to " + itemToGive);

        } catch (Exception e) {
            System.out.println("[Druid] Transmutation Swap Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean isHoldingValidItem(Player player) {
        try {
            Method getInventory = player.getClass().getMethod("getInventory");
            Object inventory = getInventory.invoke(player);
            if (inventory == null) return false;

            Method getItemInHand = inventory.getClass().getMethod("getItemInHand");
            Object itemStack = getItemInHand.invoke(inventory);
            if (itemStack == null) return false;

            Method getItemId = itemStack.getClass().getMethod("getItemId");
            String itemId = (String) getItemId.invoke(itemStack);

            if (itemId == null) return false;

            String lowerId = itemId.toLowerCase();
            return lowerId.contains("druid_totem") ||
                    lowerId.contains("bear_skin") ||
                    lowerId.contains("ram_horn") ||
                    lowerId.contains("tiger_claw") ||
                    lowerId.contains("shark_tooth");

        } catch (Exception e) {
            return false;
        }
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
        String playerName = player.getDisplayName();

        scheduler.schedule(() -> {
            if (!maintenanceActive.getOrDefault(playerName, false)) return;
            if (player.getWorld() == null) { maintenanceActive.put(playerName, false); return; }

            try {
                player.getWorld().execute(() -> {
                    try {
                        if (shortName.equals("shark")) {
                            modifyStat(player, "Oxygen", true, 10.0f);
                        }
                    } catch (Exception e) { }
                });
                startFormMaintenance(player, shortName);
            } catch (Exception e) { e.printStackTrace(); }
        }, 250, TimeUnit.MILLISECONDS);
    }

    private void updateCapabilities(Player player, String shortName) {
        try {
            Object movementManager = getMovementManager(player);
            if (movementManager == null) return;

            Method getSettings = movementManager.getClass().getMethod("getSettings");
            Object settings = getSettings.invoke(movementManager);

            float baseSpeed = 5.5f;
            float jumpForce = 11.8f;
            float flySpeed = 10.32f;
            float dragCoefficient = 0.5f;
            float fallMomentumLoss = 0.1f;
            boolean canFly = false;

            switch (shortName.toLowerCase()) {
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
                    break;
                case "ram":
                    baseSpeed = 5.5f * 1.25f;
                    break;
                case "sabertooth":
                case "tiger":
                    baseSpeed = 5.5f * 1.5f;
                    jumpForce = 14.5f;
                    break;
                case "shark":
                    baseSpeed = 12.0f;
                    dragCoefficient = 0.01f;
                    break;
                case "jackalope":
                    baseSpeed = 8.0f;
                    jumpForce = 17.0f;
                    dragCoefficient = 14.0f;
                    fallMomentumLoss = 1.0f;
                    break;
                case "bear":
                    baseSpeed = 6.0f;
                    break;
            }

            setField(settings, "canFly", canFly);
            setField(settings, "baseSpeed", baseSpeed);
            setField(settings, "jumpForce", jumpForce);
            setField(settings, "horizontalFlySpeed", flySpeed);
            setField(settings, "verticalFlySpeed", flySpeed);
            setField(settings, "dragCoefficient", dragCoefficient);
            setField(settings, "fallMomentumLoss", fallMomentumLoss);

            Object playerRef = getPlayerRef(player);
            Method getPacketHandler = playerRef.getClass().getMethod("getPacketHandler");
            Object packetHandler = getPacketHandler.invoke(playerRef);
            Method updateMethod = movementManager.getClass().getMethod("update", Class.forName("com.hypixel.hytale.server.core.io.PacketHandler"));
            updateMethod.invoke(movementManager, packetHandler);

        } catch (Exception e) {}
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
                        String name = e.toString().toLowerCase();
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

    private void sendPacket(Player player, Object packet) {
        try {
            Object playerRef = getPlayerRef(player);
            Method getPacketHandler = playerRef.getClass().getMethod("getPacketHandler");
            Object handler = getPacketHandler.invoke(playerRef);
            Method write = handler.getClass().getMethod("writeNoCache", Class.forName("com.hypixel.hytale.protocol.Packet"));
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