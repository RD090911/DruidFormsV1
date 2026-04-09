package me.druid.v1.forms;

import java.util.EnumMap;
import java.util.Map;

public final class PlayerSkinPreferences {
    private final Map<FormId, SkinId> selectedSkinsByForm = new EnumMap<>(FormId.class);

    public boolean setSelectedSkin(FormId formId, SkinId skinId) {
        if (!isValidSelection(formId, skinId)) {
            return false;
        }
        selectedSkinsByForm.put(formId, skinId);
        return true;
    }

    public SkinId getSelectedSkin(FormId formId) {
        if (formId == null) {
            return null;
        }
        return selectedSkinsByForm.get(formId);
    }

    public void clearSelectedSkin(FormId formId) {
        if (formId == null) {
            return;
        }
        selectedSkinsByForm.remove(formId);
    }

    public void clearAll() {
        selectedSkinsByForm.clear();
    }

    public SkinId getSelectedSkinOrDefault(FormId formId) {
        if (formId == null) {
            return null;
        }

        SkinId selected = selectedSkinsByForm.get(formId);
        if (selected != null && isValidSelection(formId, selected)) {
            return selected;
        }

        return FormSkinResolver.getDefaultSkinForForm(formId);
    }

    private boolean isValidSelection(FormId formId, SkinId skinId) {
        if (formId == null || skinId == null) {
            return false;
        }

        FormId owningForm = SkinRegistry.getFormForSkin(skinId);
        if (owningForm != formId) {
            return false;
        }

        return FormSkinResolver.getAvailableSkinsForForm(formId).contains(skinId);
    }
}
