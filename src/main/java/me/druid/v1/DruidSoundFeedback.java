package me.druid.v1;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.entity.entities.Player;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class DruidSoundFeedback {
    private static final Map<String, Integer> SOUND_INDEX_CACHE = new ConcurrentHashMap<>();
    private static final float DEFAULT_VOLUME_MODIFIER = 1.0f;

    private DruidSoundFeedback() {
    }

    static void playForPlayer(Player player, String soundEventId) {
        playForPlayer(player, soundEventId, soundEventId, DEFAULT_VOLUME_MODIFIER);
    }

    static void playForPlayer(Player player, String localSoundEventId, String worldSoundEventId) {
        playForPlayer(player, localSoundEventId, worldSoundEventId, DEFAULT_VOLUME_MODIFIER);
    }

    static void playForPlayer(
            Player player,
            String localSoundEventId,
            String worldSoundEventId,
            float volumeModifier
    ) {
        if (player == null) {
            return;
        }

        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        Store<EntityStore> store = playerRef.getStore();
        if (store == null) {
            return;
        }

        if (!runOnWorldThread(
                store,
                () -> playForPlayerOnWorldThread(playerRef, localSoundEventId, worldSoundEventId, volumeModifier)
        )) {
            return;
        }
    }

    private static void playForPlayerOnWorldThread(
            Ref<EntityStore> playerRef,
            String localSoundEventId,
            String worldSoundEventId,
            float volumeModifier
    ) {
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        Store<EntityStore> store = playerRef.getStore();
        if (store == null) {
            return;
        }

        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }

        PlayerRef playerRefComponent = store.getComponent(playerRef, PlayerRef.getComponentType());
        if (playerRefComponent == null) {
            return;
        }

        int worldSoundIndex = resolveSoundIndexCached(worldSoundEventId);
        int localSoundIndex = resolveSoundIndexCached(localSoundEventId);
        if (localSoundIndex <= 0) {
            localSoundIndex = worldSoundIndex;
        }

        if (localSoundIndex <= 0 && worldSoundIndex <= 0) {
            return;
        }

        SoundUtil.playLocalPlayerSoundEvent(
                playerRefComponent,
                localSoundIndex > 0 ? localSoundIndex : SoundEvent.EMPTY_ID,
                worldSoundIndex > 0 ? worldSoundIndex : SoundEvent.EMPTY_ID,
                SoundCategory.SFX,
                volumeModifier,
                1.0f
        );

        if (worldSoundIndex > 0) {
            SoundUtil.playSoundEvent3d(
                    worldSoundIndex,
                    SoundCategory.SFX,
                    transform.getPosition().x(),
                    transform.getPosition().y(),
                    transform.getPosition().z(),
                    volumeModifier,
                    1.0f,
                    ref -> !playerRef.equals(ref),
                    store
            );
        }
    }

    static void playAtPlayer(Player player, String soundEventId) {
        if (player == null) {
            return;
        }

        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        Store<EntityStore> store = playerRef.getStore();
        if (store == null) {
            return;
        }

        if (!runOnWorldThread(store, () -> playAtPlayerOnWorldThread(playerRef, soundEventId))) {
            return;
        }
    }

    private static void playAtPlayerOnWorldThread(Ref<EntityStore> playerRef, String soundEventId) {
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        Store<EntityStore> store = playerRef.getStore();
        if (store == null) {
            return;
        }

        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }

        playAtPosition(store, transform.getPosition(), soundEventId);
    }

    static void playAtPosition(Store<EntityStore> store, Vector3d position, String soundEventId) {
        if (store == null || position == null || soundEventId == null || soundEventId.isBlank()) {
            return;
        }

        Integer soundIndex = resolveSoundIndexCached(soundEventId);
        if (soundIndex == null || soundIndex <= 0) {
            return;
        }

        SoundUtil.playSoundEvent3d(soundIndex, SoundCategory.SFX, position, store);
    }

    private static boolean runOnWorldThread(Store<EntityStore> store, Runnable action) {
        if (store == null || action == null) {
            return false;
        }

        if (store.isInThread()) {
            action.run();
            return true;
        }

        EntityStore entityStore = store.getExternalData();
        World world = entityStore == null ? null : entityStore.getWorld();
        if (world == null || !world.isAlive()) {
            return false;
        }

        try {
            world.execute(action);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static int resolveSoundIndexCached(String soundEventId) {
        if (soundEventId == null || soundEventId.isBlank()) {
            return SoundEvent.EMPTY_ID;
        }
        Integer soundIndex = SOUND_INDEX_CACHE.computeIfAbsent(soundEventId, DruidSoundFeedback::resolveSoundIndex);
        return soundIndex == null ? SoundEvent.EMPTY_ID : soundIndex;
    }

    private static Integer resolveSoundIndex(String soundEventId) {
        try {
            Class<?> soundConfigClass = Class.forName("com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent");
            Method getAssetMap = soundConfigClass.getMethod("getAssetMap");
            Object assetMap = getAssetMap.invoke(null);
            Method getIndex = assetMap.getClass().getMethod("getIndex", Object.class);
            return (Integer) getIndex.invoke(assetMap, soundEventId);
        } catch (Exception ignored) {
            return -1;
        }
    }
}
