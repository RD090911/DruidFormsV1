package me.druid.v1;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

// We only need the Player import now.
// If this is red, Alt+Enter to import YOUR specific Player class.
import com.hypixel.hytale.server.core.entity.entities.Player;

public class ShapeshiftHandler {

    // Logger Removed. We use System.out now.

    public static final ConcurrentHashMap<String, String> activeForms = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<String, List<AbilityConfig>> activeAbilities = new ConcurrentHashMap<>();

    private static final Map<String, Object> originalModels = new HashMap<>();
    private static final Gson gson = new Gson();

    private static final Map<String, String> ALLOWED_FORMS = new HashMap<>();
    static {
        ALLOWED_FORMS.put("bear", "Bear_Grizzly");
        ALLOWED_FORMS.put("polar", "Bear_Polar");
        ALLOWED_FORMS.put("ram", "Ram");
        ALLOWED_FORMS.put("duck", "Duck");
        ALLOWED_FORMS.put("shark", "Shark_Hammerhead");
        ALLOWED_FORMS.put("hawk", "Hawk");
        ALLOWED_FORMS.put("sabertooth", "Tiger_Sabertooth");
        ALLOWED_FORMS.put("jackalope", "Rabbit");
        ALLOWED_FORMS.put("antelope", "Antelope");
    }

    // Constructor: No arguments needed anymore!
    public ShapeshiftHandler() {
        // No logger setup needed
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

        if (!activeForms.containsKey(playerName)) saveOriginalModel(player);

        if (swapModel(player, targetModelID)) {
            activeForms.put(playerName, targetModelID);
            System.out.println("[Druid] " + playerName + " transformed into " + shortName + " (" + targetModelID + ")!");

            applyDruidPowers(player, shortName);
            loadCustomAbilities(player, shortName);
            updateCapabilities(player, shortName);
        }
    }

    private void loadCustomAbilities(Player player, String shortName) {
        String playerName = player.getDisplayName();
        try {
            String path = "/assets/druid/entity_effects/druid_" + shortName + "_form.json";
            InputStream stream = getClass().getResourceAsStream(path);

            if (stream == null) {
                System.out.println("[Druid] Could not find config file for abilities: " + path);
                return;
            }

            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream)).getAsJsonObject();

            if (root.has("abilities")) {
                Type listType = new TypeToken<ArrayList<AbilityConfig>>(){}.getType();
                List<AbilityConfig> abilities = gson.fromJson(root.get("abilities"), listType);
                activeAbilities.put(playerName, abilities);
                System.out.println("[Druid] Loaded " + abilities.size() + " abilities for " + shortName);
            } else {
                activeAbilities.remove(playerName);
            }

        } catch (Exception e) {
            System.out.println("[Druid] Failed to load custom abilities: " + e.getMessage());
        }
    }

    public void restoreHuman(Player player) {
        String playerName = player.getDisplayName();

        updateCapabilities(player, "human");
        activeAbilities.remove(playerName);

        if (originalModels.containsKey(playerName)) {
            if (injectRawModel(player, originalModels.get(playerName))) {
                activeForms.remove(playerName);
                originalModels.remove(playerName);
                System.out.println("[Druid] " + playerName + " returned to Human Form.");
                return;
            }
        }
        if (swapModel(player, "Player")) {
            activeForms.remove(playerName);
            System.out.println("[Druid] " + playerName + " returned to base Human Form.");
        }
    }

    // --- Reflection Methods (Untouched) ---

    private void updateCapabilities(Player player, String shortName) {
        try {
            Field refField = getFieldDeep(player.getClass(), "playerRef");
            refField.setAccessible(true);
            Object playerRef = refField.get(player);

            Method getRefMethod = playerRef.getClass().getMethod("getReference");
            Object internalRef = getRefMethod.invoke(playerRef);
            Method getStoreMethod = internalRef.getClass().getMethod("getStore");
            Object store = getStoreMethod.invoke(internalRef);

            Class<?> managerClass = Class.forName("com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager");
            Method getCompType = managerClass.getMethod("getComponentType");
            Object compType = getCompType.invoke(null);

            Method getComponent = store.getClass().getMethod("getComponent",
                    Class.forName("com.hypixel.hytale.component.Ref"),
                    Class.forName("com.hypixel.hytale.component.ComponentType")
            );
            Object movementManager = getComponent.invoke(store, internalRef, compType);

            if (movementManager != null) {
                Method getSettings = managerClass.getMethod("getSettings");
                Object settings = getSettings.invoke(movementManager);

                Field canFlyField = settings.getClass().getField("canFly");
                boolean shouldFly = shortName.equals("duck") || shortName.equals("hawk");
                canFlyField.setBoolean(settings, shouldFly);

                Method getPacketHandler = playerRef.getClass().getMethod("getPacketHandler");
                Object packetHandler = getPacketHandler.invoke(playerRef);
                Method updateMethod = managerClass.getMethod("update",
                        Class.forName("com.hypixel.hytale.server.core.io.PacketHandler")
                );
                updateMethod.invoke(movementManager, packetHandler);
            }
        } catch (Exception e) {
            System.out.println("[Druid] Failed to update capabilities: " + e.getMessage());
        }
    }

    private void applyDruidPowers(Player player, String shortName) {
        try {
            String effectID = "druid_" + shortName.toLowerCase() + "_form";

            Class<?> entityEffectClass = Class.forName("com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect");
            Method getMapMethod = entityEffectClass.getMethod("getAssetMap");
            Object assetMap = getMapMethod.invoke(null);
            Method getIndexMethod = assetMap.getClass().getMethod("getIndex", Object.class);
            Method getAssetMethod = assetMap.getClass().getMethod("getAsset", int.class);

            int foundIndex = (int) getIndexMethod.invoke(assetMap, effectID);

            if (foundIndex == Integer.MIN_VALUE) {
                foundIndex = deepSearchRegistry(assetMap, getIndexMethod, shortName);
            }

            if (foundIndex == Integer.MIN_VALUE) {
                System.out.println("[Druid] Effect '" + effectID + "' not found.");
                return;
            }

            Object effectObject = getAssetMethod.invoke(assetMap, foundIndex);
            injectEffect(player, effectObject);

            player.getWorld().execute(() -> forceHealReflective(player));

        } catch (Exception e) {
            System.out.println("[Druid] Failed to apply powers: " + e.getMessage());
        }
    }

    private void forceHealReflective(Player player) {
        try {
            Field refField = getFieldDeep(player.getClass(), "playerRef");
            refField.setAccessible(true);
            Object playerRef = refField.get(player);
            Method getRefMethod = playerRef.getClass().getMethod("getReference");
            Object internalRef = getRefMethod.invoke(playerRef);
            Method getStoreMethod = internalRef.getClass().getMethod("getStore");
            Object store = getStoreMethod.invoke(internalRef);

            Class<?> statMapClass = Class.forName("com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap");
            Method getCompType = statMapClass.getMethod("getComponentType");
            Object compType = getCompType.invoke(null);

            Method getComponent = store.getClass().getMethod("getComponent",
                    Class.forName("com.hypixel.hytale.component.Ref"),
                    Class.forName("com.hypixel.hytale.component.ComponentType")
            );
            Object statMap = getComponent.invoke(store, internalRef, compType);

            if (statMap != null) {
                Class<?> statTypeClass = Class.forName("com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType");
                Method getAssetMap = statTypeClass.getMethod("getAssetMap");
                Object assetMap = getAssetMap.invoke(null);
                Method getIndex = assetMap.getClass().getMethod("getIndex", Object.class);
                int healthIndex = (int) getIndex.invoke(assetMap, "Health");

                Method maximize = statMapClass.getMethod("maximizeStatValue", int.class);
                maximize.invoke(statMap, healthIndex);
            }
        } catch (Exception e) {
            // Silent fail
        }
    }

    private void injectEffect(Player player, Object effectObject) throws Exception {
        Field refField = getFieldDeep(player.getClass(), "playerRef");
        refField.setAccessible(true);
        Object playerRef = refField.get(player);
        Method getRefMethod = playerRef.getClass().getMethod("getReference");
        Object internalRef = getRefMethod.invoke(playerRef);
        Method getStoreMethod = internalRef.getClass().getMethod("getStore");
        Object store = getStoreMethod.invoke(internalRef);

        Class<?> controllerClass = Class.forName("com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent");
        Method getTypeMethod = controllerClass.getMethod("getComponentType");
        Object compType = getTypeMethod.invoke(null);

        Method getCompMethod = store.getClass().getMethod("getComponent",
                Class.forName("com.hypixel.hytale.component.Ref"),
                Class.forName("com.hypixel.hytale.component.ComponentType")
        );
        Object effectComponent = getCompMethod.invoke(store, internalRef, compType);

        if (effectComponent != null) {
            Method addEffectMethod = null;
            for (Method m : controllerClass.getMethods()) {
                if (m.getName().equals("addEffect") && m.getParameterCount() == 3) {
                    addEffectMethod = m;
                    break;
                }
            }
            if (addEffectMethod != null) {
                addEffectMethod.invoke(effectComponent, internalRef, effectObject, store);
                System.out.println("[Druid] JSON Effect applied!");
            }
        }
    }

    private int deepSearchRegistry(Object assetMap, Method getIndexMethod, String shortName) {
        try {
            Field keyMapField = null;
            for (Field f : assetMap.getClass().getDeclaredFields()) {
                if (f.getName().equals("keyToIndex") || f.getType().getSimpleName().contains("Map")) {
                    keyMapField = f;
                    break;
                }
            }
            if (keyMapField != null) {
                keyMapField.setAccessible(true);
                Object keyMap = keyMapField.get(assetMap);
                if (keyMap instanceof Map) {
                    for (Object k : ((Map<?,?>) keyMap).keySet()) {
                        String keyStr = k.toString();
                        if (keyStr.toLowerCase().contains(shortName.toLowerCase())) return (int) getIndexMethod.invoke(assetMap, keyStr);
                    }
                }
            }
        } catch(Exception e) {}
        return Integer.MIN_VALUE;
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
        Field refField = getFieldDeep(player.getClass(), "playerRef");
        refField.setAccessible(true);
        Object playerRef = refField.get(player);
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