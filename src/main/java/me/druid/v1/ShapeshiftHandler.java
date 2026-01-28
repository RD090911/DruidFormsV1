package me.druid.v1;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.buuz135.mhud.MultipleHUD;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class ShapeshiftHandler {
    private final Logger logger;

    public static final ConcurrentHashMap<String, String> activeForms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MovementStats> originalStats = new ConcurrentHashMap<>();

    public ShapeshiftHandler(Logger logger) {
        this.logger = logger;
    }

    // --- MAIN TRANSFORMATION LOGIC ---
    public void transform(Player player, String targetModel) {
        String playerName = player.getDisplayName();
        String currentForm = activeForms.get(playerName);

        // 1. TOGGLE CHECK
        if (targetModel.equals(currentForm)) {
            restoreHuman(player);
            // FIX: Logging to console instead of chat to prevent version errors
            logger.info(playerName + " reverted to Human Form!");
            return;
        }

        // 2. SWITCH CHECK
        if (currentForm == null) {
            saveOriginalStats(player);
        }

        // 3. APPLY TRANSFORMATION
        setModelSafe(player, targetModel);
        activeForms.put(playerName, targetModel);

        // 4. Apply Stats
        applyFormStats(player, targetModel);

        // 5. Update HUD
        updateHUDSafe(player, targetModel);

        // FIX: Logging to console
        logger.info(playerName + " transformed into " + targetModel + "!");
    }

    public void restoreHuman(Player player) {
        String playerName = player.getDisplayName();

        if (activeForms.containsKey(playerName)) {
            setModelSafe(player, "human");

            if (originalStats.containsKey(playerName)) {
                MovementStats defaultStats = originalStats.get(playerName);
                applyMovementStats(player, defaultStats.canFly, defaultStats.walkSpeed, defaultStats.flySpeed);
                originalStats.remove(playerName);
            }

            activeForms.remove(playerName);
            updateHUDSafe(player, "Human");
        }
    }

    // --- SAFE HUD UPDATE ---
    public void updateHUDSafe(Player player, String formName) {
        try {
            // 1. Get PlayerRef safely
            Method getRefMethod = player.getClass().getMethod("getPlayerRef");
            Object playerRef = getRefMethod.invoke(player);

            if (playerRef != null) {
                // 2. Get the Store safely
                Method getReference = playerRef.getClass().getMethod("getReference");
                Object entityRef = getReference.invoke(playerRef);

                Method getStore = entityRef.getClass().getMethod("getStore");
                Object store = getStore.invoke(entityRef);

                // 3. Call MultipleHUD via Reflection
                for (Method m : MultipleHUD.getInstance().getClass().getMethods()) {
                    if (m.getName().equals("setCustomHud")) {
                        m.invoke(MultipleHUD.getInstance(), player, store, "DruidStanceBar", new DruidHUD(formName));
                        return;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore HUD errors
        }
    }

    // --- KEY LISTENER ---
    public void checkHotbarKeys(Player player) {
        int slot = player.getHotbarManager().getCurrentHotbarIndex();
        String playerName = player.getDisplayName();

        if (slot == 0) {
            if (activeForms.containsKey(playerName)) restoreHuman(player);
        }
        if (slot == 1) {
            if (!"DruidBearForm".equals(activeForms.get(playerName))) transform(player, "DruidBearForm");
        }
        if (slot == 2) {
            if (!"DruidSharkForm".equals(activeForms.get(playerName))) transform(player, "DruidSharkForm");
        }
    }

    // --- STATS HELPERS ---
    private void applyFormStats(Player player, String modelId) {
        boolean canFly = false;
        float walkSpeed = 6.0f;
        float flySpeed = 10.0f;

        switch (modelId) {
            case "DruidBearForm": walkSpeed = 4.0f; break;
            case "DruidSharkForm": walkSpeed = 8.0f; break;
            case "DruidHawkForm": canFly = true; walkSpeed = 12.0f; flySpeed = 18.0f; break;
            case "DruidAntelopeForm": walkSpeed = 14.0f; break;
            case "DruidRamForm": walkSpeed = 7.0f; break;
            case "DruidJackalopeForm":
            case "DruidRabbitForm": walkSpeed = 10.0f; break;
            case "DruidSabretoothForm": walkSpeed = 10.0f; break;
        }
        applyMovementStats(player, canFly, walkSpeed, flySpeed);
    }

    private void saveOriginalStats(Player player) {
        originalStats.put(player.getDisplayName(), new MovementStats(false, 6.0f, 10.0f));
    }

    private void applyMovementStats(Player player, boolean canFly, float walkSpeed, float flySpeed) {
        try {
            setField(player, "canFly", canFly);
            setField(player, "baseSpeed", walkSpeed);
            setField(player, "horizontalFlySpeed", flySpeed);
            player.getHotbarManager().getCurrentHotbarIndex();
        } catch (Exception e) { }
    }

    // --- HELPER: SAFE MODEL SETTER ---
    private void setModelSafe(Object player, String model) {
        try {
            Method m = player.getClass().getMethod("setCreatureType", String.class);
            m.invoke(player, model);
        } catch (Exception e) {
            try {
                Method m = player.getClass().getMethod("setModel", String.class);
                m.invoke(player, model);
            } catch (Exception ex) {}
        }
    }

    // --- HELPER: REFLECTION FIELD SETTER ---
    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = null;
            try { field = target.getClass().getField(fieldName); } catch (Exception e) {}
            if (field == null) {
                try { field = target.getClass().getDeclaredField(fieldName); } catch(Exception e) {}
            }
            if (field != null) {
                field.setAccessible(true);
                field.set(target, value);
            }
        } catch (Exception e) { }
    }

    private static class MovementStats {
        boolean canFly;
        float walkSpeed;
        float flySpeed;

        public MovementStats(boolean canFly, float walkSpeed, float flySpeed) {
            this.canFly = canFly;
            this.walkSpeed = walkSpeed;
            this.flySpeed = flySpeed;
        }
    }
}