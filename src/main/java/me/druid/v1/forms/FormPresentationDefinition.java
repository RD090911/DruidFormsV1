package me.druid.v1.forms;

import java.util.Objects;

public final class FormPresentationDefinition {
    private final FormId formId;
    private final int displayOrder;
    private final String category;
    private final String shortLabel;

    public FormPresentationDefinition(FormId formId, int displayOrder, String category, String shortLabel) {
        this.formId = Objects.requireNonNull(formId, "formId");
        this.displayOrder = displayOrder;
        this.category = category;
        this.shortLabel = shortLabel;
    }

    public FormId getFormId() {
        return formId;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public String getCategory() {
        return category;
    }

    public String getShortLabel() {
        return shortLabel;
    }
}
