package me.druid.v1;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.druid.v1.forms.FormId;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class WardenGaiasTouchAbilityHandler {
    private static final Map<UUID, Long> COOLDOWN_END_BY_OWNER = new ConcurrentHashMap<>();
    private static final long GAIAS_TOUCH_COOLDOWN_MILLIS = 30_000L;
    private static final float GAIAS_TOUCH_HEAL_AMOUNT = 75.0f;
    private static final String GAIAS_TOUCH_HEAL_EFFECT_ID = "Healing_Totem_Heal";

    private WardenGaiasTouchAbilityHandler() {
    }

    static void trigger(Player player) {
        if (player == null) {
            return;
        }

        if (player.getWorld() != null) {
            player.getWorld().execute(() -> triggerOnWorldThread(player));
            return;
        }

        triggerOnWorldThread(player);
    }

    private static void triggerOnWorldThread(Player player) {
        if (player == null) {
            return;
        }

        FormId activeFormId = ShapeshiftHandler.getActiveFormId(player);
        if (activeFormId != FormId.FORM_WARDEN) {
            return;
        }

        if (!canUseAbility(player)) {
            return;
        }

        if (applyHeal(player)) {
            UUID ownerId = player.getUuid();
            if (ownerId != null) {
                COOLDOWN_END_BY_OWNER.put(ownerId, System.currentTimeMillis() + GAIAS_TOUCH_COOLDOWN_MILLIS);
                showCooldownHud(player);
            }
            applyHealVisual(player);
            System.out.println(String.format(Locale.ROOT,
                    "Warden Gaia's Touch ability triggered heal=%.1f owner=%s",
                    GAIAS_TOUCH_HEAL_AMOUNT,
                    DruidPlayerCompat.getPlayerNameOrUnknown(player)));
            sendPlayerMessage(player, "Gaia's Touch restores your vitality.");
        }
    }

    private static boolean canUseAbility(Player player) {
        UUID ownerId = player == null ? null : player.getUuid();
        if (ownerId == null) {
            return false;
        }

        long nowMillis = System.currentTimeMillis();
        Long cooldownEndMillis = COOLDOWN_END_BY_OWNER.get(ownerId);
        if (cooldownEndMillis == null) {
            return true;
        }

        if (nowMillis >= cooldownEndMillis) {
            COOLDOWN_END_BY_OWNER.remove(ownerId);
            return true;
        }

        long remainingMillis = cooldownEndMillis - nowMillis;
        double remainingSeconds = remainingMillis / 1000.0d;
        sendPlayerMessage(player, String.format(Locale.ROOT, "Gaia's Touch recharging: %.1fs", remainingSeconds));
        showCooldownHud(player);
        System.out.println(String.format(Locale.ROOT,
                "GaiasTouch cast blocked cooldownRemainingMillis=%d cooldownRemainingSeconds=%.1f owner=%s",
                remainingMillis,
                remainingSeconds,
                DruidPlayerCompat.getPlayerNameOrUnknown(player)));
        return false;
    }

    private static boolean applyHeal(Player player) {
        try {
            EntityStatMap stats = EntityStatsModule.get(player);
            if (stats == null) {
                return false;
            }
            stats.addStatValue(DefaultEntityStatTypes.getHealth(), GAIAS_TOUCH_HEAL_AMOUNT);
            return true;
        } catch (Exception exception) {
            System.out.println("GaiasTouch heal failed reason=" + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean applyHealVisual(Player player) {
        if (player == null) {
            return false;
        }

        EntityEffect healingEffect = EntityEffect.getAssetMap().getAsset(GAIAS_TOUCH_HEAL_EFFECT_ID);
        if (healingEffect == null) {
            return false;
        }

        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }

        World world = player.getWorld();
        if (world == null) {
            return false;
        }

        EntityStore entityStore = world.getEntityStore();
        if (entityStore == null) {
            return false;
        }

        Store<EntityStore> store = entityStore.getStore();
        if (store == null) {
            return false;
        }

        EffectControllerComponent effectController = store.getComponent(playerRef, EffectControllerComponent.getComponentType());
        if (effectController == null) {
            return false;
        }

        try {
            return effectController.addEffect(playerRef, healingEffect, store);
        } catch (Exception exception) {
            System.out.println("GaiasTouch visual failed reason=" + exception.getClass().getSimpleName());
            return false;
        }
    }

    private static void sendPlayerMessage(Player player, String text) {
        if (player == null || text == null) return;
        FormattedMessage component = new FormattedMessage();
        component.rawText = text;
        DruidPlayerCompat.sendMessage(player, new Message(component));
    }

    private static void showCooldownHud(Player player) {
        if (player == null) {
            return;
        }

        UUID ownerId = player.getUuid();
        Long cooldownEndMillis = ownerId == null ? null : COOLDOWN_END_BY_OWNER.get(ownerId);
        long remainingMillis = cooldownEndMillis == null ? 0L : cooldownEndMillis - System.currentTimeMillis();
        if (remainingMillis <= 0L) {
            DruidAbilityCooldownHotbarHud.remove(player);
            return;
        }
        DruidAbilityCooldownHotbarHud.showOrUpdate(player, 0, "Gaias_Touch", remainingMillis, GAIAS_TOUCH_COOLDOWN_MILLIS);
    }
}
