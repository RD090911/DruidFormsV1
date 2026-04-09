package me.druid.v1.forms;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class FormRegistry {
    private static final Map<FormId, FormDefinition> DEFINITIONS;
    private static final Map<String, FormId> FORM_BY_ANIMAL;

    static {
        EnumMap<FormId, FormDefinition> definitions = new EnumMap<>(FormId.class);
        definitions.put(
                FormId.FORM_GUARDIAN,
                new FormDefinition(FormId.FORM_GUARDIAN, "Guardian", "bear", "Defensive beast form class.")
        );
        definitions.put(
                FormId.FORM_PROWLER,
                new FormDefinition(FormId.FORM_PROWLER, "Prowler", "tiger", "Predator strike form class.")
        );
        definitions.put(
                FormId.FORM_STALKER,
                new FormDefinition(FormId.FORM_STALKER, "Stalker", "shark", "Ambush water hunter form class.")
        );
        definitions.put(
                FormId.FORM_TRAVEL,
                new FormDefinition(FormId.FORM_TRAVEL, "Travel", "antelope", "Mobility travel form class.")
        );
        definitions.put(
                FormId.FORM_FLIGHT,
                new FormDefinition(FormId.FORM_FLIGHT, "Flight", "hawk", "Aerial movement form class.")
        );
        definitions.put(
                FormId.FORM_SPRINGER,
                new FormDefinition(FormId.FORM_SPRINGER, "Springer", "rabbit", "Leap-focused mobility form class.")
        );
        definitions.put(
                FormId.FORM_FORAGER,
                new FormDefinition(FormId.FORM_FORAGER, "Forager", "ram", "Gathering and traversal form class.")
        );
        definitions.put(
                FormId.FORM_AQUATIC,
                new FormDefinition(
                        FormId.FORM_AQUATIC,
                        "Aquatic",
                        "bluegill",
                        "Fast-swim utility form class. Planned Bluegill base skin; future infinite oxygen, higher swim speed than Stalker, lower combat emphasis."
                )
        );
        DEFINITIONS = Collections.unmodifiableMap(definitions);

        Map<String, FormId> formByAnimal = new HashMap<>();
        formByAnimal.put("bear", FormId.FORM_GUARDIAN);
        formByAnimal.put("tiger", FormId.FORM_PROWLER);
        formByAnimal.put("shark", FormId.FORM_STALKER);
        formByAnimal.put("antelope", FormId.FORM_TRAVEL);
        formByAnimal.put("hawk", FormId.FORM_FLIGHT);
        formByAnimal.put("duck", FormId.FORM_FLIGHT);
        formByAnimal.put("rabbit", FormId.FORM_SPRINGER);
        formByAnimal.put("ram", FormId.FORM_FORAGER);
        formByAnimal.put("bluegill", FormId.FORM_AQUATIC);
        FORM_BY_ANIMAL = Collections.unmodifiableMap(formByAnimal);
    }

    private FormRegistry() {
    }

    public static FormId getFormForAnimal(String animalKey) {
        String normalized = normalizeAnimalKey(animalKey);
        if (normalized == null) {
            return null;
        }
        return FORM_BY_ANIMAL.get(normalized);
    }

    public static FormDefinition getDefinition(FormId formId) {
        if (formId == null) {
            return null;
        }
        return DEFINITIONS.get(formId);
    }

    public static String getBaseAnimal(FormId formId) {
        FormDefinition definition = getDefinition(formId);
        if (definition == null) {
            return null;
        }
        return definition.getBaseAnimal();
    }

    public static Map<FormId, FormDefinition> getDefinitions() {
        return DEFINITIONS;
    }

    private static String normalizeAnimalKey(String animalKey) {
        if (animalKey == null) {
            return null;
        }
        String normalized = animalKey.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized;
    }
}
