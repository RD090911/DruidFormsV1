package me.druid.v1.forms;

public final class FormResolver {
    private FormResolver() {
    }

    public static FormId getFormForAnimal(String existingAnimal) {
        return FormRegistry.getFormForAnimal(existingAnimal);
    }

    public static String getBaseAnimalForForm(FormId formId) {
        return FormRegistry.getBaseAnimal(formId);
    }

    public static String getDisplayNameForForm(FormId formId) {
        FormDefinition definition = FormRegistry.getDefinition(formId);
        if (definition == null) {
            return null;
        }
        return definition.getDisplayName();
    }
}
