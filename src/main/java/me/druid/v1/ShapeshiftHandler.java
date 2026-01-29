package me.druid.v1;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class ShapeshiftHandler {
    private final Logger logger;
    // Tracks which form a player is currently in
    public static final ConcurrentHashMap<String, String> activeForms = new ConcurrentHashMap<>();

    // Stores the player's original "Human" model (Key = Player Name)
    private static final Map<String, Object> originalModels = new HashMap<>();

    // --- THE WHITELIST ---
    // Maps your command inputs to the REAL internal Hytale Asset IDs
    private static final Map<String, String> ALLOWED_FORMS = new HashMap<>();
    static {
        // 1. Bear
        ALLOWED_FORMS.put("bear", "Bear_Grizzly");
        ALLOWED_FORMS.put("polar", "Bear_Polar");

        // 2. Ram
        ALLOWED_FORMS.put("ram", "Ram");

        // 3. Duck
        ALLOWED_FORMS.put("duck", "Duck");

        // 4. Shark
        ALLOWED_FORMS.put("shark", "Shark_Hammerhead");

        // 5. Hawk
        ALLOWED_FORMS.put("hawk", "Hawk");

        // 6. Sabertooth (Accepts both spellings now!)
        ALLOWED_FORMS.put("sabertooth", "Tiger_Sabertooth");
        ALLOWED_FORMS.put("sabretooth", "Tiger_Sabertooth");

        // 7. Jackalope (Uses Rabbit model)
        ALLOWED_FORMS.put("jackalope", "Rabbit");

        // 8. Antelope
        ALLOWED_FORMS.put("antelope", "Antelope");
    }

    public ShapeshiftHandler(Logger logger) {
        this.logger = logger;
    }

    public void shapeshift(Player player, String formName) {
        String cleanName = formName.toLowerCase();

        // DEV TOOL: Search registry (e.g. /shapeshift search:tiger)
        if (cleanName.startsWith("search:")) {
            performRegistrySearch(cleanName.split(":")[1]);
            return;
        }

        if (!ALLOWED_FORMS.containsKey(cleanName)) {
            logger.warning("Invalid form requested: " + cleanName);
            return;
        }

        String targetID = ALLOWED_FORMS.get(cleanName);
        transform(player, targetID, cleanName);
    }

    public void transform(Player player, String targetModelID, String shortName) {
        String playerName = player.getDisplayName();
        String currentForm = activeForms.get(playerName);

        // Toggle Logic: If already in this form, turn back to human
        if (currentForm != null && currentForm.equals(targetModelID)) {
            restoreHuman(player);
            return;
        }

        // 1. Save Human Model (if we haven't already)
        if (!activeForms.containsKey(playerName)) {
            saveOriginalModel(player);
        }

        // 2. Perform the Transformation
        if (swapModel(player, targetModelID)) {
            activeForms.put(playerName, targetModelID);
            logger.info(playerName + " transformed into " + shortName + " (" + targetModelID + ")!");

            // NOTE: This is where we will add the code to apply the .json stats later
            // applyDruidEffects(player, shortName);
        }
    }

    public void restoreHuman(Player player) {
        String playerName = player.getDisplayName();

        // 1. Try to restore the exact original model (skins + clothes)
        if (originalModels.containsKey(playerName)) {
            if (injectRawModel(player, originalModels.get(playerName))) {
                activeForms.remove(playerName);
                originalModels.remove(playerName); // Clear cache
                logger.info(playerName + " returned to Human Form.");
                return;
            }
        }

        // 2. Fallback: If cache is missing, reset to default "Player" model (might be naked)
        if (swapModel(player, "Player")) {
            activeForms.remove(playerName);
            logger.info(playerName + " returned to base Human Form (Fallback).");
        }
    }

    // --- CORE LOGIC ---

    private boolean swapModel(Player player, String assetId) {
        try {
            // 1. Get the Asset Map
            Method getMapMethod = ModelAsset.class.getMethod("getAssetMap");
            Object assetMap = getMapMethod.invoke(null);

            // 2. Get retrieval method using Object.class (Fixes NoSuchMethodException)
            Method getAssetMethod = assetMap.getClass().getMethod("getAsset", Object.class);
            getAssetMethod.setAccessible(true);

            // 3. Fetch the Asset
            Object rawAsset = getAssetMethod.invoke(assetMap, assetId);

            // Auto-Correction: If simple name fails, try adding namespaces
            if (rawAsset == null) rawAsset = getAssetMethod.invoke(assetMap, "Hytale:" + assetId);
            if (rawAsset == null) rawAsset = getAssetMethod.invoke(assetMap, "Druid:" + assetId);

            if (rawAsset == null) {
                logger.severe("CRITICAL: Model asset '" + assetId + "' could not be found.");
                return false;
            }

            // 4. Create the new Model Object
            Model newModel = Model.createUnitScaleModel((ModelAsset) rawAsset);

            // 5. Inject it into the player
            return injectRawModel(player, newModel);

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean injectRawModel(Object player, Object modelObject) {
        try {
            Object component = getModelComponent(player);
            if (component == null) return false;

            // Overwrite the 'model' field
            Field modelField = ModelComponent.class.getDeclaredField("model");
            modelField.setAccessible(true);
            modelField.set(component, modelObject);

            // Trigger Network Update
            Field outdatedField = ModelComponent.class.getDeclaredField("isNetworkOutdated");
            outdatedField.setAccessible(true);
            outdatedField.set(component, true);

            return true;
        } catch (Exception e) {
            logger.severe("Injection failed: " + e.getMessage());
            return false;
        }
    }

    private void saveOriginalModel(Player player) {
        try {
            Object component = getModelComponent(player);
            Field modelField = ModelComponent.class.getDeclaredField("model");
            modelField.setAccessible(true);
            Object currentModel = modelField.get(component);
            originalModels.put(player.getDisplayName(), currentModel);
        } catch (Exception e) {
            logger.warning("Failed to save original skin: " + e.getMessage());
        }
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

        Method getCompMethod = store.getClass().getMethod("getComponent",
                Class.forName("com.hypixel.hytale.component.Ref"),
                Class.forName("com.hypixel.hytale.component.ComponentType")
        );
        return getCompMethod.invoke(store, ref, compType);
    }

    private Field getFieldDeep(Class<?> clazz, String name) {
        while (clazz != null) {
            try { return clazz.getDeclaredField(name); }
            catch (Exception e) { clazz = clazz.getSuperclass(); }
        }
        return null;
    }

    private void performRegistrySearch(String query) {
        try {
            logger.info("Searching registry for: " + query);
            Method getMapMethod = ModelAsset.class.getMethod("getAssetMap");
            Object assetMap = getMapMethod.invoke(null);

            Method getUnderlyingMap = assetMap.getClass().getMethod("getAssetMap");
            Map<?, ?> map = (Map<?, ?>) getUnderlyingMap.invoke(assetMap);

            for (Object key : map.keySet()) {
                if (key.toString().toLowerCase().contains(query.toLowerCase())) {
                    logger.info(">> MATCH FOUND: " + key.toString());
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}