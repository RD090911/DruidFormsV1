package me.druid.v1.forms;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class SkinRegistry {
    private static final Map<SkinId, SkinDefinition> DEFINITIONS;
    private static final Map<String, SkinId> SKIN_BY_ANIMAL;

    static {
        EnumMap<SkinId, SkinDefinition> definitions = new EnumMap<>(SkinId.class);

        add(definitions, SkinId.SKIN_GRIZZLY, FormId.FORM_GUARDIAN, "Grizzly", "bear", null);
        add(definitions, SkinId.SKIN_POLAR_BEAR, FormId.FORM_GUARDIAN, "Polar Bear", null, null);
        add(definitions, SkinId.SKIN_CAVE_REX, FormId.FORM_GUARDIAN, "Cave Rex", null, null);

        add(definitions, SkinId.SKIN_TIGER_SABERTOOTH, FormId.FORM_PROWLER, "Tiger / Sabertooth", "tiger", null);
        add(definitions, SkinId.SKIN_PANTHER, FormId.FORM_PROWLER, "Panther", null, null);
        add(definitions, SkinId.SKIN_CAVE_RAPTOR, FormId.FORM_PROWLER, "Cave Raptor", null, null);
        add(definitions, SkinId.SKIN_HYENA, FormId.FORM_PROWLER, "Hyena", null, null);
        add(definitions, SkinId.SKIN_SNOW_LEOPARD, FormId.FORM_PROWLER, "Snow Leopard", null, null);
        add(definitions, SkinId.SKIN_WOLF, FormId.FORM_PROWLER, "Wolf", null, null);

        add(definitions, SkinId.SKIN_SHARK, FormId.FORM_STALKER, "Shark", "shark", null);
        add(definitions, SkinId.SKIN_PIRANHA, FormId.FORM_STALKER, "Piranha", null, null);
        add(definitions, SkinId.SKIN_SNAPJAW, FormId.FORM_STALKER, "Snapjaw", null, null);

        add(definitions, SkinId.SKIN_ANTELOPE, FormId.FORM_TRAVEL, "Antelope", "antelope", null);
        add(definitions, SkinId.SKIN_DEER_STAG, FormId.FORM_TRAVEL, "Deer (Stag)", null, null);
        add(definitions, SkinId.SKIN_HORSE, FormId.FORM_TRAVEL, "Horse", null, null);
        add(definitions, SkinId.SKIN_SKELETAL_HORSE, FormId.FORM_TRAVEL, "Skeletal Horse", null, null);
        add(definitions, SkinId.SKIN_CAMEL, FormId.FORM_TRAVEL, "Camel", null, null);

        add(definitions, SkinId.SKIN_HAWK, FormId.FORM_FLIGHT, "Hawk", "hawk", null);
        add(definitions, SkinId.SKIN_CROW, FormId.FORM_FLIGHT, "Crow", null, null);
        add(definitions, SkinId.SKIN_BAT_SNOW_BAT, FormId.FORM_FLIGHT, "Bat / Snow Bat", null, null);
        add(definitions, SkinId.SKIN_DUCK, FormId.FORM_FLIGHT, "Duck", "duck", null);
        add(definitions, SkinId.SKIN_OWL, FormId.FORM_FLIGHT, "Owl", null, null);
        add(definitions, SkinId.SKIN_PARROT, FormId.FORM_FLIGHT, "Parrot", null, null);
        add(definitions, SkinId.SKIN_PIGEON, FormId.FORM_FLIGHT, "Pigeon", null, null);
        add(definitions, SkinId.SKIN_PTERODACTYL, FormId.FORM_FLIGHT, "Pterodactyl", null, null);
        add(definitions, SkinId.SKIN_FIRE_DRAGON, FormId.FORM_FLIGHT, "Fire Dragon", null, null);

        add(definitions, SkinId.SKIN_RABBIT, FormId.FORM_SPRINGER, "Rabbit", "rabbit", null);
        add(definitions, SkinId.SKIN_FROG, FormId.FORM_SPRINGER, "Frog", null, null);
        add(definitions, SkinId.SKIN_CAT, FormId.FORM_SPRINGER, "Cat", null, null);
        add(definitions, SkinId.SKIN_FOX, FormId.FORM_SPRINGER, "Fox", null, null);
        add(definitions, SkinId.SKIN_SPIDER, FormId.FORM_SPRINGER, "Spider", null, null);

        add(definitions, SkinId.SKIN_RAM, FormId.FORM_FORAGER, "Ram", "ram", null);
        add(definitions, SkinId.SKIN_GOAT, FormId.FORM_FORAGER, "Goat", null, null);
        add(definitions, SkinId.SKIN_MOOSE, FormId.FORM_FORAGER, "Moose", null, null);
        add(definitions, SkinId.SKIN_WARTHOG, FormId.FORM_FORAGER, "Warthog", null, null);
        add(definitions, SkinId.SKIN_BISON, FormId.FORM_FORAGER, "Bison", null, null);
        add(definitions, SkinId.SKIN_TRILODON, FormId.FORM_FORAGER, "Trilodon", null, null);

        add(definitions, SkinId.SKIN_EEL, FormId.FORM_AQUATIC, "Eel", null, null);
        add(
                definitions,
                SkinId.SKIN_BLUEGILL,
                FormId.FORM_AQUATIC,
                "Bluegill",
                "bluegill",
                "Planned first Aquatic base skin for future runtime activation."
        );
        add(definitions, SkinId.SKIN_CLOWNFISH, FormId.FORM_AQUATIC, "Clownfish", null, null);
        add(definitions, SkinId.SKIN_PIKE, FormId.FORM_AQUATIC, "Pike", null, null);
        add(definitions, SkinId.SKIN_RAINBOW_TROUT, FormId.FORM_AQUATIC, "Rainbow Trout", null, null);
        add(definitions, SkinId.SKIN_TANG, FormId.FORM_AQUATIC, "Tang", null, null);
        add(definitions, SkinId.SKIN_FROSTGILL, FormId.FORM_AQUATIC, "Frostgill", null, null);

        DEFINITIONS = Collections.unmodifiableMap(definitions);

        Map<String, SkinId> skinByAnimal = new HashMap<>();
        skinByAnimal.put("bear", SkinId.SKIN_GRIZZLY);
        skinByAnimal.put("tiger", SkinId.SKIN_TIGER_SABERTOOTH);
        skinByAnimal.put("shark", SkinId.SKIN_SHARK);
        skinByAnimal.put("antelope", SkinId.SKIN_ANTELOPE);
        skinByAnimal.put("hawk", SkinId.SKIN_HAWK);
        skinByAnimal.put("duck", SkinId.SKIN_DUCK);
        skinByAnimal.put("rabbit", SkinId.SKIN_RABBIT);
        skinByAnimal.put("ram", SkinId.SKIN_RAM);
        skinByAnimal.put("bluegill", SkinId.SKIN_BLUEGILL);
        SKIN_BY_ANIMAL = Collections.unmodifiableMap(skinByAnimal);
    }

    private SkinRegistry() {
    }

    public static SkinId getSkinForAnimal(String animalKey) {
        String normalized = normalizeAnimalKey(animalKey);
        if (normalized == null) {
            return null;
        }
        return SKIN_BY_ANIMAL.get(normalized);
    }

    public static SkinDefinition getDefinition(SkinId skinId) {
        if (skinId == null) {
            return null;
        }
        return DEFINITIONS.get(skinId);
    }

    public static FormId getFormForSkin(SkinId skinId) {
        SkinDefinition definition = getDefinition(skinId);
        if (definition == null) {
            return null;
        }
        return definition.getFormId();
    }

    public static String getAnimalForSkin(SkinId skinId) {
        SkinDefinition definition = getDefinition(skinId);
        if (definition == null) {
            return null;
        }
        return definition.getBackingAnimal();
    }

    public static Map<SkinId, SkinDefinition> getDefinitions() {
        return DEFINITIONS;
    }

    private static void add(
            Map<SkinId, SkinDefinition> definitions,
            SkinId skinId,
            FormId formId,
            String displayName,
            String backingAnimal,
            String description
    ) {
        definitions.put(skinId, new SkinDefinition(skinId, formId, displayName, backingAnimal, description));
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
