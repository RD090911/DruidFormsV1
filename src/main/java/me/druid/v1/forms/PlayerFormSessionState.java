package me.druid.v1.forms;

import java.util.Objects;

public final class PlayerFormSessionState {
    private FormId activeForm;
    private final PlayerSkinPreferences skinPreferences;

    public PlayerFormSessionState() {
        this(new PlayerSkinPreferences());
    }

    public PlayerFormSessionState(PlayerSkinPreferences skinPreferences) {
        this.skinPreferences = Objects.requireNonNull(skinPreferences, "skinPreferences");
    }

    public void setActiveForm(FormId formId) {
        this.activeForm = formId;
    }

    public FormId getActiveForm() {
        return activeForm;
    }

    public void clearActiveForm() {
        this.activeForm = null;
    }

    public SkinId getSelectedSkin(FormId formId) {
        return skinPreferences.getSelectedSkin(formId);
    }

    public boolean setSelectedSkin(FormId formId, SkinId skinId) {
        return skinPreferences.setSelectedSkin(formId, skinId);
    }

    public SkinId getSelectedSkinOrDefault(FormId formId) {
        return skinPreferences.getSelectedSkinOrDefault(formId);
    }

    public void clearAll() {
        clearActiveForm();
        skinPreferences.clearAll();
    }
}
