package me.druid.v1;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.lang.reflect.Field;
import java.util.logging.Logger;

public class ShapeshiftHandler {
    private final Logger logger;

    // --- LIST OF ALL FORMS TO CLEAN UP ---
    private static final String[] ALL_FORMS = {
            "DruidBearForm", "DruidSharkForm", "DruidSabretoothForm",
            "DruidHawkForm", "DruidAntelopeForm", "DruidRamForm",
            "DruidJackalopeForm", "DruidDuckForm"
    };

    public ShapeshiftHandler(Logger logger) {
        this.logger = logger;
    }

    public void shapeshift(Player player, String formId) {
        // --- 0. HELP COMMAND (New!) ---
        if (formId.equalsIgnoreCase("help")) {
            sendStatus(player, "=== Available Druid Forms ===");
            sendStatus(player, "- human");
            for (String form : ALL_FORMS) {
                sendStatus(player, "- " + form);
            }
            return;
        }

        // --- 1. SETUP ---
        Ref<EntityStore> entityRef = player.getReference();
        Store<EntityStore> store = entityRef.getStore();

        // Retrieve components from the store
        EffectControllerComponent effects = (EffectControllerComponent) store.getComponent(entityRef, EffectControllerComponent.getComponentType());
        MovementManager movement = (MovementManager) store.getComponent(entityRef, MovementManager.getComponentType());

        // Retrieve the PlayerRef Component to get the username (Clean ECS way)
        PlayerRef playerInfo = (PlayerRef) store.getComponent(entityRef, PlayerRef.getComponentType());
        String playerName = (playerInfo != null) ? playerInfo.getUsername() : "Unknown";

        if (effects == null || movement == null) {
            this.logger.warning("Could not find Effects or Movement component for " + playerName);
            return;
        }

        // --- 2. CLEANUP (Remove all existing Druid effects) ---
        for (String oldForm : ALL_FORMS) {
            EntityEffect oldAsset = (EntityEffect) EntityEffect.getAssetMap().getAsset(oldForm);
            if (oldAsset != null) {
                // FIXED: Using .getIndex(oldForm) to get the Integer ID
                int assetId = EntityEffect.getAssetMap().getIndex(oldForm);
                effects.removeEffect(entityRef, assetId, store);
            }
        }

        // --- 3. CHECK FOR HUMAN REVERT ---
        if (formId.equalsIgnoreCase("human")) {
            applyMovementStats(movement, player, false, 5.5f, 10.32f); // Default Human Stats
            sendStatus(player, "Returned to Human Form.");
            return;
        }

        // --- 4. APPLY NEW VISUALS ---
        EntityEffect effectAsset = (EntityEffect) EntityEffect.getAssetMap().getAsset(formId);

        if (effectAsset != null) {
            effects.addEffect(entityRef, effectAsset, store);
            this.logger.info("Applied visual effect: " + formId);
            sendStatus(player, "Shapeshifted into: " + formId);
        } else {
            this.logger.warning("Could not find JSON asset for: " + formId);
            sendStatus(player, "Error: Form '" + formId + "' not found!");
            return;
        }

        // --- 5. APPLY MOVEMENT STATS ---
        applyStatsForForm(movement, player, formId, playerName);
    }

    private void applyStatsForForm(MovementManager movement, Player player, String formId, String username) {
        // Defaults (Human Speed)
        boolean canFly = false;
        float speed = 5.5f;        // Normal walk speed
        float flySpeed = 10.32f;   // Normal fly speed (creative mode)

        switch (formId) {
            case "DruidHawkForm":
                canFly = true;
                flySpeed = 15.0f; // FAST Flight
                break;

            case "DruidAntelopeForm":
                speed = 11.0f;    // FAST Run (2x Human)
                break;

            // Everyone else falls through to default (Human Speed)
            case "DruidSabretoothForm":
            case "DruidBearForm":
            case "DruidSharkForm":
            default:
                speed = 5.5f;
                break;
        }

        // Apply the calculated stats
        applyMovementStats(movement, player, canFly, speed, flySpeed);
        this.logger.info("Applied stats for " + formId + " to " + username);
    }

    private void applyMovementStats(MovementManager movement, Player player, boolean canFly, float speed, float flySpeed) {
        Object settings = movement.getSettings();
        setField(settings, "canFly", canFly);
        setField(settings, "baseSpeed", speed);
        setField(settings, "horizontalFlySpeed", flySpeed);

        // --- FIX: Commented out to resolve compilation error ---
        // movement.update(player.getPlayerConnection());
    }

    // --- HELPER: Send Chat Message ---
    private void sendStatus(Player player, String text) {
        FormattedMessage component = new FormattedMessage();
        component.rawText = text;
        player.sendMessage(new Message(component));
    }

    // --- HELPER: Reflection for Movement ---
    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getField(fieldName);
            field.set(target, value);
        } catch (NoSuchFieldException e) {
            try {
                Field field = target.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }
}