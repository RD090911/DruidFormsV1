package me.druid.v1.forms;

import java.util.Objects;

public final class SkinDefinition {
    private final SkinId skinId;
    private final FormId formId;
    private final String displayName;
    private final String backingAnimal;
    private final String description;

    public SkinDefinition(SkinId skinId, FormId formId, String displayName, String backingAnimal, String description) {
        this.skinId = Objects.requireNonNull(skinId, "skinId");
        this.formId = Objects.requireNonNull(formId, "formId");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.backingAnimal = backingAnimal;
        this.description = description;
    }

    public SkinId getSkinId() {
        return skinId;
    }

    public FormId getFormId() {
        return formId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBackingAnimal() {
        return backingAnimal;
    }

    public String getDescription() {
        return description;
    }
}
