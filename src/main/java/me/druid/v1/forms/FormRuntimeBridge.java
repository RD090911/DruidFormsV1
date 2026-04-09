package me.druid.v1.forms;

public final class FormRuntimeBridge {
    private FormRuntimeBridge() {
    }

    public static FormId resolveFormIdForAnimal(String animalKey) {
        return FormResolver.getFormForAnimal(animalKey);
    }

    public static String resolveFormLabelForAnimal(String animalKey) {
        FormId formId = resolveFormIdForAnimal(animalKey);
        if (formId == null) {
            return null;
        }

        FormPresentationDefinition presentation = FormPresentationRegistry.getDefinition(formId);
        if (presentation != null) {
            String shortLabel = presentation.getShortLabel();
            if (shortLabel != null && !shortLabel.isBlank()) {
                return shortLabel;
            }
        }

        FormDefinition definition = FormRegistry.getDefinition(formId);
        if (definition == null) {
            return null;
        }
        return definition.getDisplayName();
    }

    public static FormPresentationDefinition getPresentationForAnimal(String animalKey) {
        FormId formId = resolveFormIdForAnimal(animalKey);
        if (formId == null) {
            return null;
        }
        return FormPresentationRegistry.getDefinition(formId);
    }

    public static String resolveAnimalKeyForForm(FormId formId) {
        if (formId == null) {
            return null;
        }

        String baseAnimal = normalizeAnimalKey(FormRegistry.getBaseAnimal(formId));
        if (baseAnimal != null) {
            return baseAnimal;
        }

        return resolveAnimalKeyForDefaultSkin(formId);
    }

    public static String resolveAnimalKeyForDefaultSkin(FormId formId) {
        if (formId == null) {
            return null;
        }

        SkinId defaultSkin = FormSkinResolver.getDefaultSkinForForm(formId);
        if (defaultSkin == null) {
            return null;
        }

        return normalizeAnimalKey(SkinRegistry.getAnimalForSkin(defaultSkin));
    }

    public static boolean hasRuntimeAnimalForForm(FormId formId) {
        return resolveAnimalKeyForForm(formId) != null;
    }

    private static String normalizeAnimalKey(String animalKey) {
        if (animalKey == null) {
            return null;
        }
        String normalized = animalKey.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized;
    }
}
