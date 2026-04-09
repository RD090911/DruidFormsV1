package me.druid.v1.forms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class FormSkinResolver {
    private static final Map<FormId, List<SkinId>> SKINS_BY_FORM;
    private static final Map<FormId, SkinId> PLANNED_BASE_SKIN_BY_FORM;

    static {
        EnumMap<FormId, List<SkinId>> skinsByForm = new EnumMap<>(FormId.class);
        for (FormId formId : FormId.values()) {
            skinsByForm.put(formId, new ArrayList<>());
        }

        for (SkinId skinId : SkinId.values()) {
            SkinDefinition definition = SkinRegistry.getDefinition(skinId);
            if (definition == null) {
                continue;
            }
            List<SkinId> skins = skinsByForm.get(definition.getFormId());
            if (skins != null) {
                skins.add(skinId);
            }
        }

        for (Map.Entry<FormId, List<SkinId>> entry : skinsByForm.entrySet()) {
            entry.setValue(Collections.unmodifiableList(entry.getValue()));
        }
        SKINS_BY_FORM = Collections.unmodifiableMap(skinsByForm);

        EnumMap<FormId, SkinId> plannedBaseSkins = new EnumMap<>(FormId.class);
        // Planning metadata only. This does not imply runtime implementation.
        plannedBaseSkins.put(FormId.FORM_AQUATIC, SkinId.SKIN_BLUEGILL);
        PLANNED_BASE_SKIN_BY_FORM = Collections.unmodifiableMap(plannedBaseSkins);
    }

    private FormSkinResolver() {
    }

    public static SkinId getDefaultSkinForForm(FormId formId) {
        if (formId == null) {
            return null;
        }

        String baseAnimal = FormRegistry.getBaseAnimal(formId);
        if (baseAnimal != null) {
            SkinId defaultSkin = SkinRegistry.getSkinForAnimal(baseAnimal);
            if (defaultSkin != null) {
                return defaultSkin;
            }
        }

        List<SkinId> implemented = getImplementedSkinsForForm(formId);
        if (!implemented.isEmpty()) {
            return implemented.get(0);
        }

        return null;
    }

    public static List<SkinId> getAvailableSkinsForForm(FormId formId) {
        if (formId == null) {
            return Collections.emptyList();
        }
        List<SkinId> skins = SKINS_BY_FORM.get(formId);
        return skins == null ? Collections.emptyList() : skins;
    }

    public static boolean isSkinImplemented(SkinId skinId) {
        if (skinId == null) {
            return false;
        }

        SkinDefinition definition = SkinRegistry.getDefinition(skinId);
        if (definition == null) {
            return false;
        }

        String backingAnimal = definition.getBackingAnimal();
        if (backingAnimal == null || backingAnimal.isBlank()) {
            return false;
        }

        SkinId resolved = SkinRegistry.getSkinForAnimal(backingAnimal);
        return skinId == resolved;
    }

    public static List<SkinId> getImplementedSkinsForForm(FormId formId) {
        List<SkinId> available = getAvailableSkinsForForm(formId);
        if (available.isEmpty()) {
            return Collections.emptyList();
        }

        List<SkinId> implemented = new ArrayList<>();
        for (SkinId skinId : available) {
            if (isSkinImplemented(skinId)) {
                implemented.add(skinId);
            }
        }
        return Collections.unmodifiableList(implemented);
    }

    public static SkinId getPlannedBaseSkinForForm(FormId formId) {
        if (formId == null) {
            return null;
        }
        return PLANNED_BASE_SKIN_BY_FORM.get(formId);
    }
}
