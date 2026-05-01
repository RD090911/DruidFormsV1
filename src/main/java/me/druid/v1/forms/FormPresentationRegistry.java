package me.druid.v1.forms;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class FormPresentationRegistry {
    private static final Map<FormId, FormPresentationDefinition> DEFINITIONS;
    private static final List<FormId> ORDERED_FORMS = List.of(
            FormId.FORM_GUARDIAN,
            FormId.FORM_WARDEN,
            FormId.FORM_PROWLER,
            FormId.FORM_STALKER,
            FormId.FORM_TRAVEL,
            FormId.FORM_FLIGHT,
            FormId.FORM_SPRINGER,
            FormId.FORM_FORAGER,
            FormId.FORM_AQUATIC
    );

    static {
        EnumMap<FormId, FormPresentationDefinition> definitions = new EnumMap<>(FormId.class);
        add(definitions, FormId.FORM_GUARDIAN, 1, "Combat", "Guardian");
        add(definitions, FormId.FORM_WARDEN, 2, "Support", "Warden");
        add(definitions, FormId.FORM_PROWLER, 3, "Combat", "Prowler");
        add(definitions, FormId.FORM_STALKER, 4, "Combat", "Stalker");
        add(definitions, FormId.FORM_TRAVEL, 5, "Mobility", "Travel");
        add(definitions, FormId.FORM_FLIGHT, 6, "Mobility", "Flight");
        add(definitions, FormId.FORM_SPRINGER, 7, "Mobility", "Springer");
        add(definitions, FormId.FORM_FORAGER, 8, "Utility", "Forager");
        add(definitions, FormId.FORM_AQUATIC, 9, "Aquatic", "Aquatic");
        DEFINITIONS = Collections.unmodifiableMap(definitions);
    }

    private FormPresentationRegistry() {
    }

    public static int getDisplayOrder(FormId formId) {
        FormPresentationDefinition definition = getDefinition(formId);
        if (definition == null) {
            return -1;
        }
        return definition.getDisplayOrder();
    }

    public static List<FormId> getOrderedForms() {
        return ORDERED_FORMS;
    }

    public static FormPresentationDefinition getDefinition(FormId formId) {
        if (formId == null) {
            return null;
        }
        return DEFINITIONS.get(formId);
    }

    public static String getCategory(FormId formId) {
        FormPresentationDefinition definition = getDefinition(formId);
        if (definition == null) {
            return null;
        }
        return definition.getCategory();
    }

    private static void add(
            Map<FormId, FormPresentationDefinition> definitions,
            FormId formId,
            int displayOrder,
            String category,
            String shortLabel
    ) {
        definitions.put(formId, new FormPresentationDefinition(formId, displayOrder, category, shortLabel));
    }
}
