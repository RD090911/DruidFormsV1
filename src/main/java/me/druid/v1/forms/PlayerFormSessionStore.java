package me.druid.v1.forms;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerFormSessionStore {
    private static final ConcurrentHashMap<UUID, PlayerFormSessionState> SESSIONS = new ConcurrentHashMap<>();

    private PlayerFormSessionStore() {
    }

    public static PlayerFormSessionState getOrCreate(UUID playerUuid) {
        if (playerUuid == null) {
            return new PlayerFormSessionState();
        }
        return SESSIONS.computeIfAbsent(playerUuid, ignored -> new PlayerFormSessionState());
    }

    public static SkinId getSelectedSkin(UUID playerUuid, FormId formId) {
        if (playerUuid == null || formId == null) {
            return null;
        }
        PlayerFormSessionState state = SESSIONS.get(playerUuid);
        if (state == null) {
            return null;
        }
        return state.getSelectedSkin(formId);
    }

    public static boolean setSelectedSkin(UUID playerUuid, FormId formId, SkinId skinId) {
        if (playerUuid == null || formId == null || skinId == null) {
            return false;
        }
        PlayerFormSessionState state = getOrCreate(playerUuid);
        return state.setSelectedSkin(formId, skinId);
    }

    public static FormId getSelectedForm(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        PlayerFormSessionState state = SESSIONS.get(playerUuid);
        if (state == null) {
            return null;
        }
        return state.getActiveForm();
    }

    public static void setSelectedForm(UUID playerUuid, FormId formId) {
        if (playerUuid == null) {
            return;
        }
        PlayerFormSessionState state = getOrCreate(playerUuid);
        state.setActiveForm(formId);
    }

    public static void clearSelectedForm(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        PlayerFormSessionState state = SESSIONS.get(playerUuid);
        if (state == null) {
            return;
        }
        state.clearActiveForm();
    }
}
