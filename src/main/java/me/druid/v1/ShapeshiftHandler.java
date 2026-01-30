package me.druid.v1;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class ShapeshiftHandler {
    private final Logger logger;
    public static final ConcurrentHashMap<String, String> activeForms = new ConcurrentHashMap<>();
    private static final Map<String, Object> originalModels = new HashMap<>();

    private static final Map<String, String> ALLOWED_FORMS = new HashMap<>();
    static {
        ALLOWED_FORMS.put("bear", "Bear_Grizzly");
        ALLOWED_FORMS.put("polar", "Bear_Polar");
        ALLOWED_FORMS.put("ram", "Ram");
        ALLOWED_FORMS.put("duck", "Duck");
        ALLOWED_FORMS.put("shark", "Shark_Hammerhead");
        ALLOWED_FORMS.put("hawk", "Hawk");
        ALLOWED_FORMS.put("sabretooth", "Tiger_Sabertooth");
        ALLOWED_FORMS.put("sabertooth", "Tiger_Sabertooth");
        ALLOWED_FORMS.put("jackalope", "Rabbit");
        ALLOWED_FORMS.put("antelope", "Antelope");
    }

    public ShapeshiftHandler(Logger logger) {
        this.logger = logger;
    }

    public void shapeshift(Player player, String formName) {
        String cleanName = formName.toLowerCase();

        if (!ALLOWED_FORMS.containsKey(cleanName)) {
            logger.warning("Invalid form: " + cleanName);
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
            logger.info(playerName + " transformed into " + shortName + " (" + targetModelID + ")!");

            // APPLY STATS & HEAL
            applyDruidPowers(player, shortName);
        }
    }

    public void restoreHuman(Player player) {
        String playerName = player.getDisplayName();
        if (originalModels.containsKey(playerName)) {
            if (injectRawModel(player, originalModels.get(playerName))) {
                activeForms.remove(playerName);
                originalModels.remove(playerName);
                logger.info(playerName + " returned to Human Form.");
                return;
            }
        }
        if (swapModel(player, "Player")) {
            activeForms.remove(playerName);
            logger.info(playerName + " returned to base Human Form.");
        }
    }

    private void applyDruidPowers(Player player, String shortName) {
        try {
            String effectID = "assets/druid" + shortName.substring(0, 1).toUpperCase() + shortName.substring(1).toLowerCase() + "Form";
            logger.info("Applying Powers: " + effectID + "...");

            // 1. REGISTRY LOOKUP
            Method getMapMethod = EntityEffect.class.getMethod("getAssetMap");
            Object assetMap = getMapMethod.invoke(null);
            Method getIndexMethod = assetMap.getClass().getMethod("getIndex", Object.class);
            Method getAssetMethod = assetMap.getClass().getMethod("getAsset", int.class);

            int foundIndex = (int) getIndexMethod.invoke(assetMap, effectID);

            // Fallback Search
            if (foundIndex == Integer.MIN_VALUE) {
                foundIndex = deepSearchRegistry(assetMap, getIndexMethod, shortName);
            }

            if (foundIndex == Integer.MIN_VALUE) {
                logger.severe(">> CRITICAL: Effect '" + effectID + "' not found. Check JSON.");
                return;
            }

            Object effectObject = getAssetMethod.invoke(assetMap, foundIndex);

            // 2. INJECT EFFECT
            injectEffect(player, effectObject);

            // 3. FORCE HEAL (Using Components, NOT Player API)
            forceHeal(player);

        } catch (Exception e) {
            logger.severe("Failed to apply powers: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void forceHeal(Player player) {
        new Thread(() -> {
            try {
                // Wait for the stat modifier to apply (raising Max HP)
                Thread.sleep(100);

                // A. Get Store & Ref (Reflection Boilerplate)
                Field refField = getFieldDeep(player.getClass(), "playerRef");
                refField.setAccessible(true);
                Object playerRef = refField.get(player);
                Method getRefMethod = playerRef.getClass().getMethod("getReference");
                Object internalRef = getRefMethod.invoke(playerRef);
                Method getStoreMethod = internalRef.getClass().getMethod("getStore");
                Object store = getStoreMethod.invoke(internalRef);

                // B. Get EntityStatMap Component
                Class<?> statMapClass = Class.forName("com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap");
                Method getCompType = statMapClass.getMethod("getComponentType");
                Object compType = getCompType.invoke(null);

                Method getComponent = store.getClass().getMethod("getComponent",
                        Class.forName("com.hypixel.hytale.component.Ref"),
                        Class.forName("com.hypixel.hytale.component.ComponentType")
                );
                Object statMap = getComponent.invoke(store, internalRef, compType);

                if (statMap != null) {
                    // C. Get Index for "Health"
                    Class<?> statTypeClass = Class.forName("com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType");
                    Method getAssetMap = statTypeClass.getMethod("getAssetMap");
                    Object assetMap = getAssetMap.invoke(null);
                    Method getIndex = assetMap.getClass().getMethod("getIndex", Object.class);
                    int healthIndex = (int) getIndex.invoke(assetMap, "Health");

                    // D. Maximize Health (Fill the bar)
                    // Method signature: maximizeStatValue(int index)
                    Method maximize = statMapClass.getMethod("maximizeStatValue", int.class);
                    maximize.invoke(statMap, healthIndex);

                    logger.info(">> HEALED: Health maximized via Component System!");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void injectEffect(Player player, Object effectObject) throws Exception {
        Field refField = getFieldDeep(player.getClass(), "playerRef");
        refField.setAccessible(true);
        Object playerRef = refField.get(player);

        Method getRefMethod = playerRef.getClass().getMethod("getReference");
        Object internalRef = getRefMethod.invoke(playerRef);

        Method getStoreMethod = internalRef.getClass().getMethod("getStore");
        Object store = getStoreMethod.invoke(internalRef);

        Method getTypeMethod = EffectControllerComponent.class.getMethod("getComponentType");
        Object compType = getTypeMethod.invoke(null);

        Method getCompMethod = store.getClass().getMethod("getComponent",
                Class.forName("com.hypixel.hytale.component.Ref"),
                Class.forName("com.hypixel.hytale.component.ComponentType")
        );
        Object effectComponent = getCompMethod.invoke(store, internalRef, compType);

        if (effectComponent == null) {
            logger.warning(">> EffectControllerComponent missing on player!");
            return;
        }

        Method addEffectMethod = null;
        for (Method m : EffectControllerComponent.class.getMethods()) {
            if (m.getName().equals("addEffect") && m.getParameterCount() == 3) {
                Class<?>[] params = m.getParameterTypes();
                if (params[1] == EntityEffect.class) {
                    addEffectMethod = m;
                    break;
                }
            }
        }

        if (addEffectMethod != null) {
            addEffectMethod.invoke(effectComponent, internalRef, effectObject, store);
            logger.info(">> SUCCESS: Powers applied!");
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
                        if (keyStr.toLowerCase().contains(shortName.toLowerCase()) || keyStr.toLowerCase().contains("assets/druid")) {
                            logger.info(">> FOUND CANDIDATE: " + keyStr);
                            return (int) getIndexMethod.invoke(assetMap, keyStr);
                        }
                    }
                }
            }
        } catch(Exception e) {}
        return Integer.MIN_VALUE;
    }

    private boolean swapModel(Player player, String assetId) {
        try {
            Method getMapMethod = ModelAsset.class.getMethod("getAssetMap");
            Object assetMap = getMapMethod.invoke(null);
            Method getAssetMethod = null;
            for(Method m : assetMap.getClass().getMethods()) { if(m.getName().equals("getAsset") && m.getParameterCount() == 1) { getAssetMethod = m; break; } }
            if (getAssetMethod == null) { for(Method m : assetMap.getClass().getMethods()) { if(m.getName().equals("get") && m.getParameterCount() == 1) { getAssetMethod = m; break; } } }
            getAssetMethod.setAccessible(true);
            Object rawAsset = getAssetMethod.invoke(assetMap, assetId);
            if (rawAsset == null) rawAsset = getAssetMethod.invoke(assetMap, "Hytale:" + assetId);
            if (rawAsset == null) rawAsset = getAssetMethod.invoke(assetMap, "Druid:" + assetId);
            if (rawAsset == null) { logger.severe("CRITICAL: Model asset '" + assetId + "' not found."); return false; }
            Model newModel = Model.createUnitScaleModel((ModelAsset) rawAsset);
            return injectRawModel(player, newModel);
        } catch (Exception e) { e.printStackTrace(); return false; }
    }
    private boolean injectRawModel(Object player, Object modelObject) {
        try {
            Object component = getModelComponent(player);
            if (component == null) return false;
            Field modelField = ModelComponent.class.getDeclaredField("model");
            modelField.setAccessible(true);
            modelField.set(component, modelObject);
            Field outdatedField = ModelComponent.class.getDeclaredField("isNetworkOutdated");
            outdatedField.setAccessible(true);
            outdatedField.set(component, true);
            return true;
        } catch (Exception e) { return false; }
    }
    private void saveOriginalModel(Player player) {
        try {
            Object component = getModelComponent(player);
            Field modelField = ModelComponent.class.getDeclaredField("model");
            modelField.setAccessible(true);
            Object currentModel = modelField.get(component);
            originalModels.put(player.getDisplayName(), currentModel);
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
        Method getTypeMethod = ModelComponent.class.getMethod("getComponentType");
        Object compType = getTypeMethod.invoke(null);
        Method getCompMethod = store.getClass().getMethod("getComponent", Class.forName("com.hypixel.hytale.component.Ref"), Class.forName("com.hypixel.hytale.component.ComponentType"));
        return getCompMethod.invoke(store, ref, compType);
    }
    private Field getFieldDeep(Class<?> clazz, String name) {
        while (clazz != null) { try { return clazz.getDeclaredField(name); } catch (Exception e) { clazz = clazz.getSuperclass(); } } return null;
    }
}