package me.druid.v1.forms;

import java.util.Objects;

public final class FormDefinition {
    private final FormId formId;
    private final String displayName;
    private final String baseAnimal;
    private final String description;

    public FormDefinition(FormId formId, String displayName, String baseAnimal, String description) {
        this.formId = Objects.requireNonNull(formId, "formId");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.baseAnimal = baseAnimal;
        this.description = description;
    }

    public FormId getFormId() {
        return formId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBaseAnimal() {
        return baseAnimal;
    }

    public String getDescription() {
        return description;
    }
}
