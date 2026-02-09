package me.druid.v1;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.hypixel.hytale.server.core.entity.entities.Player;

public class ShapeshiftHandler {

    public static final ConcurrentHashMap<String, String> activeForms = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<String, List<AbilityConfig>> activeAbilities = new ConcurrentHashMap<>();

    // Scheduler for passive effects (Oxygen / Gravity)
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static final ConcurrentHashMap<String, Boolean> maintenanceActive = new ConcurrentHashMap<>();

    private static final Map<String, Object> originalModels = new HashMap<>();
    private static final Gson gson = new Gson();

    private static final Map<String, String> ALLOWED_FORMS = new HashMap<>();
    static {
        ALLOWED_FORMS.put("bear", "Bear_Grizzly");
        ALLOWED_FORMS.put("ram", "Ram");
        ALLOWED_FORMS.put("duck", "Duck");
        ALLOWED_FORMS.put("shark", "Shark_Hammerhead");
        ALLOWED_FORMS.put("hawk", "Hawk");
        ALLOWED_FORMS.put("sabertooth", "Tiger_Sabertooth");
        ALLOWED_FORMS.put("sabretooth", "Tiger_Sabertooth");
        ALLOWED_FORMS.put("jackalope", "Rabbit");
        ALLOWED_FORMS.put("antelope", "Antelope");
    }

    public static class AbilityConfig {
        public String trigger;
        public String action;
        public double cooldown;
        public double range;
    }

    public void shapeshift(Player player, String formName) {
        String cleanName = formName.toLowerCase();
        if (!ALLOWED_FORMS.containsKey(cleanName)) {
            System.out.println("[Druid] Invalid form: " + cleanName);
            return;
        }
        transform(player, ALLOWED_FORMS.get(cleanName), cleanName);
    }

    public void transform(Player player, String targetModelID, String shortName) {
        String playerName = player.getDisplayName();
        String currentForm = activeForms.get(playerName);

        if (currentForm != null && currentForm.equals(targetModelID)) {
            restoreHuman(player);
            return;
        }

        if (currentForm != null) {
            activeAbilities.remove(playerName);
            maintenanceActive.put(playerName, false);
        }

        // Wardrobe is disabled, but we call the empty method to prevent errors
        if (!activeForms.containsKey(playerName)) {
            saveWardrobe(player);
            saveOriginalModel(player);
        }

        if (swapModel(player, targetModelID)) {
            activeForms.put(playerName, targetModelID);

            System.out.println("[Druid] " + playerName + " transformed into " + shortName);

            applyDruidPowers(player, shortName);
            loadCustomAbilities(player, shortName);
            updateCapabilities(player, shortName);

            // START THE PHYSICS LOOP (Oxygen + No Gravity)
            maintenanceActive.put(playerName, true);
            startFormMaintenance(player, shortName);
        }
    }

    public void restoreHuman(Player player) {
        String playerName = player.getDisplayName();

        maintenanceActive.put(playerName, false); // Stop the loop

        updateCapabilities(player, "human");
        activeAbilities.remove(playerName);

        restoreWardrobe(player);

        if (swapModel(player, "Player")) {
            activeForms.remove(playerName);
            System.out.println("[Druid] " + playerName + " returned to Human Form.");
        }
    }

    // --- THE PHYSICS HACK LOOP ---
    private void startFormMaintenance(Player player, String shortName) {
        String playerName = player.getDisplayName();

        // Run every 50ms (20 times a second)
        scheduler.schedule(() -> {
            if (!maintenanceActive.getOrDefault(playerName, false)) return;

            if (player.getWorld() == null) {
                maintenanceActive.put(playerName, false);
                return;
            }

            try {
                player.getWorld().execute(() -> {
                    try {
                        // 1. SHARK: Infinite Oxygen
                        if (shortName.equals("shark")) {
                            maximizeStat(player, "Oxygen");
                        }

                        // 2. JACKALOPE: PHYSICS HACK
                        if (shortName.equals("jackalope")) {
                            setField(player, "fallDistance", 0.0f);
                        }

                    } catch (Exception e) { /* Ignore */ }
                });

                startFormMaintenance(player, shortName);

            } catch (Exception e) { e.printStackTrace(); }
        }, 50, TimeUnit.MILLISECONDS);
    }

    // --- MISSING METHODS ADDED BACK BELOW ---

    private void applyDruidPowers(Player player, String shortName) {
        // Placeholder: Logic is currently handled in updateCapabilities
    }

    private void loadCustomAbilities(Player player, String shortName) {
        // Placeholder: Temporarily disabled to focus on Physics fixes
    }

    private void saveWardrobe(Player player) {
        // Disabled as requested
    }

    private void restoreWardrobe(Player player) {
        // Disabled as requested
    }

    // --- UTILS ---

    private void maximizeStat(Player player, String statName) {
        try {
            Object playerRef = getPlayerRef(player);
            Method getRefMethod = playerRef.getClass().getMethod("getReference");
            Object internalRef = getRefMethod.invoke(playerRef);
            Method getStoreMethod = internalRef.getClass().getMethod("getStore");
            Object store = getStoreMethod.invoke(internalRef);

            Class<?> statMapClass = Class.forName("com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap");
            Method getCompType = statMapClass.getMethod("getComponentType");
            Object compType = getCompType.invoke(null);

            Method getComponent = store.getClass().getMethod("getComponent", Class.forName("com.hypixel.hytale.component.Ref"), Class.forName("com.hypixel.hytale.component.ComponentType"));
            Object statMap = getComponent.invoke(store, internalRef, compType);

            if (statMap != null) {
                Class<?> statTypeClass = Class.forName("com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType");
                Method getAssetMap = statTypeClass.getMethod("getAssetMap");
                Object assetMap = getAssetMap.invoke(null);
                Method getIndex = assetMap.getClass().getMethod("getIndex", Object.class);
                int index = (int) getIndex.invoke(assetMap, statName);
                Method maximize = statMapClass.getMethod("maximizeStatValue", int.class);
                maximize.invoke(statMap, index);
            }
        } catch (Exception e) {}
    }

    private void updateCapabilities(Player player, String shortName) {
        try {
            Object ref = getPlayerRef(player);
            Method getRefMethod = ref.getClass().getMethod("getReference");
            Object internalRef = getRefMethod.invoke(ref);
            Method getStoreMethod = internalRef.getClass().getMethod("getStore");
            Object store = getStoreMethod.invoke(internalRef);

            Class<?> managerClass = Class.forName("com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager");
            Method getCompType = managerClass.getMethod("getComponentType");
            Object compType = getCompType.invoke(null);

            Method getComponent = store.getClass().getMethod("getComponent", Class.forName("com.hypixel.hytale.component.Ref"), Class.forName("com.hypixel.hytale.component.ComponentType"));
            Object movementManager = getComponent.invoke(store, internalRef, compType);

            if (movementManager != null) {
                Method getSettings = managerClass.getMethod("getSettings");
                Object settings = getSettings.invoke(movementManager);

                float baseSpeed = 5.5f;
                float jumpForce = 11.8f;
                float flySpeed = 10.32f;
                float dragCoefficient = 0.5f;
                float velocityResistance = 0.242f;
                boolean canFly = false;

                switch (shortName.toLowerCase()) {
                    case "antelope": baseSpeed = 5.5f * 1.75f; break;
                    case "hawk": baseSpeed = 5.5f * 1.25f; flySpeed = 10.32f * 1.25f; canFly = true; break;
                    case "duck": baseSpeed = 5.5f; flySpeed = 10.32f * 1.0f; canFly = true; break;
                    case "jackalope": baseSpeed = 5.5f * 1.25f; jumpForce = 19.0f; break;
                    case "shark": baseSpeed = 5.5f * 2.5f; dragCoefficient = 0.01f; velocityResistance = 0.01f; break;
                }

                setField(settings, "canFly", canFly);
                setField(settings, "baseSpeed", baseSpeed);
                setField(settings, "jumpForce", jumpForce);
                setField(settings, "horizontalFlySpeed", flySpeed);
                setField(settings, "verticalFlySpeed", flySpeed);
                setField(settings, "dragCoefficient", dragCoefficient);
                setField(settings, "velocityResistance", velocityResistance);

                Method getPacketHandler = ref.getClass().getMethod("getPacketHandler");
                Object packetHandler = getPacketHandler.invoke(ref);
                Method updateMethod = managerClass.getMethod("update", Class.forName("com.hypixel.hytale.server.core.io.PacketHandler"));
                updateMethod.invoke(movementManager, packetHandler);
            }
        } catch (Exception e) { System.out.println("[Druid] Capability Error: " + e.getMessage()); }
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
            for(Method m : assetMap.getClass().getMethods()) { if(m.getName().equals("getAsset") && m.getParameterCount() == 1) { getAssetMethod = m; break; } }
            getAssetMethod.setAccessible(true);
            Object rawAsset = getAssetMethod.invoke(assetMap, assetId);
            if (rawAsset == null) rawAsset = getAssetMethod.invoke(assetMap, "Hytale:" + assetId);
            if (rawAsset == null) rawAsset = getAssetMethod.invoke(assetMap, "Druid:" + assetId);
            if (rawAsset == null) return false;
            Method createUnitScale = modelClass.getMethod("createUnitScaleModel", modelAssetClass);
            Object newModel = createUnitScale.invoke(null, rawAsset);
            return injectRawModel(player, newModel);
        } catch (Exception e) { return false; }
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

    private void saveOriginalModel(Object player) {
        try {
            Object component = getModelComponent(player);
            Class<?> modelCompClass = Class.forName("com.hypixel.hytale.server.core.modules.entity.component.ModelComponent");
            Field modelField = modelCompClass.getDeclaredField("model");
            modelField.setAccessible(true);
            originalModels.put(getPlayerName(player), modelField.get(component));
        } catch (Exception e) {}
    }

    private Object getModelComponent(Object player) throws Exception {
        Object playerRef = getPlayerRef(player);
        Method getRefMethod = playerRef.getClass().getMethod("getReference");
        Object ref = getRefMethod.invoke(playerRef);
        Method getStoreMethod = ref.getClass().getMethod("getStore");
        Object store = getStoreMethod.invoke(ref);
        Class<?> modelCompClass = Class.forName("com.hypixel.hytale.server.core.modules.entity.component.ModelComponent");
        Method getTypeMethod = modelCompClass.getMethod("getComponentType");
        Object compType = getTypeMethod.invoke(null);
        Method getCompMethod = store.getClass().getMethod("getComponent", Class.forName("com.hypixel.hytale.component.Ref"), Class.forName("com.hypixel.hytale.component.ComponentType"));
        return getCompMethod.invoke(store, ref, compType);
    }

    private String getPlayerName(Object player) {
        try {
            Method getName = player.getClass().getMethod("getDisplayName");
            return (String) getName.invoke(player);
        } catch (Exception e) { return "UnknownPlayer"; }
    }

    private Field getFieldDeep(Class<?> clazz, String name) {
        while (clazz != null) { try { return clazz.getDeclaredField(name); } catch (Exception e) { clazz = clazz.getSuperclass(); } } return null;
    }
}