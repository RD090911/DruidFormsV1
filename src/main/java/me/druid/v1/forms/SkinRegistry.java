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
        add(definitions, SkinId.SKIN_POLAR_BEAR, FormId.FORM_GUARDIAN, "Polar Bear", "polarbear", null);
        add(definitions, SkinId.SKIN_CAVE_REX, FormId.FORM_GUARDIAN, "Cave Rex", null, null);

        add(definitions, SkinId.SKIN_TIGER_SABERTOOTH, FormId.FORM_PROWLER, "Tiger / Sabertooth", "tiger", null);
        add(definitions, SkinId.SKIN_CAVE_RAPTOR, FormId.FORM_PROWLER, "Cave Raptor", null, null);
        add(definitions, SkinId.SKIN_HYENA, FormId.FORM_PROWLER, "Hyena", "hyena", null);
        add(definitions, SkinId.SKIN_SNOW_LEOPARD, FormId.FORM_PROWLER, "Snow Leopard", "snowleopard", null);
        add(definitions, SkinId.SKIN_WOLF, FormId.FORM_PROWLER, "Wolf", "wolf", null);

        add(definitions, SkinId.SKIN_SHARK, FormId.FORM_STALKER, "Shark", "shark", null);
        add(definitions, SkinId.SKIN_PIRANHA, FormId.FORM_STALKER, "Piranha", "piranha", null);
        add(definitions, SkinId.SKIN_SNAPJAW, FormId.FORM_STALKER, "Snapjaw", "snapjaw", null);

        add(definitions, SkinId.SKIN_ANTELOPE, FormId.FORM_TRAVEL, "Antelope", "antelope", null);
        add(definitions, SkinId.SKIN_DEER_STAG, FormId.FORM_TRAVEL, "Deer (Stag)", "deer_stag", null);
        add(definitions, SkinId.SKIN_HORSE, FormId.FORM_TRAVEL, "Horse", "horse", null);
        add(definitions, SkinId.SKIN_SKELETAL_HORSE, FormId.FORM_TRAVEL, "Skeletal Horse", "horse_skeleton", null);
        add(definitions, SkinId.SKIN_CAMEL, FormId.FORM_TRAVEL, "Camel", "camel", null);

        add(definitions, SkinId.SKIN_HAWK, FormId.FORM_FLIGHT, "Hawk", "hawk", null);
        add(definitions, SkinId.SKIN_CROW, FormId.FORM_FLIGHT, "Crow", "crow", null);
        add(definitions, SkinId.SKIN_BAT_SNOW_BAT, FormId.FORM_FLIGHT, "Bat / Snow Bat", "bat", null);
        add(definitions, SkinId.SKIN_DUCK, FormId.FORM_FLIGHT, "Duck", "duck", null);
        add(definitions, SkinId.SKIN_OWL, FormId.FORM_FLIGHT, "Owl", "owl", null);
        add(definitions, SkinId.SKIN_PARROT, FormId.FORM_FLIGHT, "Parrot", "parrot", null);
        add(definitions, SkinId.SKIN_PIGEON, FormId.FORM_FLIGHT, "Pigeon", "pigeon", null);
        add(definitions, SkinId.SKIN_PTERODACTYL, FormId.FORM_FLIGHT, "Pterodactyl", "pterodactyl", null);
        add(definitions, SkinId.SKIN_FIRE_DRAGON, FormId.FORM_FLIGHT, "Fire Dragon", null, null);

        add(definitions, SkinId.SKIN_RABBIT, FormId.FORM_SPRINGER, "Rabbit", "rabbit", null);
        add(definitions, SkinId.SKIN_FROG, FormId.FORM_SPRINGER, "Frog", null, "Coming Soon");
        add(definitions, SkinId.SKIN_CAT, FormId.FORM_SPRINGER, "Cat", null, "Coming Soon");
        add(definitions, SkinId.SKIN_FOX, FormId.FORM_SPRINGER, "Fox", null, "Coming Soon");
        add(definitions, SkinId.SKIN_SPIDER, FormId.FORM_SPRINGER, "Spider", null, "Coming Soon");

        add(definitions, SkinId.SKIN_RAM, FormId.FORM_FORAGER, "Ram", "ram", null);
        add(definitions, SkinId.SKIN_GOAT, FormId.FORM_FORAGER, "Goat", "goat", null);
        add(definitions, SkinId.SKIN_MOOSE, FormId.FORM_FORAGER, "Moose", "moose", null);
        add(definitions, SkinId.SKIN_WARTHOG, FormId.FORM_FORAGER, "Warthog", "warthog", null);
        add(definitions, SkinId.SKIN_BISON, FormId.FORM_FORAGER, "Bison", "bison", null);
        add(definitions, SkinId.SKIN_TRILODON, FormId.FORM_FORAGER, "Trilodon", "trilodon", null);

        add(definitions, SkinId.SKIN_EEL, FormId.FORM_AQUATIC, "Eel", "eel_moray", null);
        add(
                definitions,
                SkinId.SKIN_BLUEGILL,
                FormId.FORM_AQUATIC,
                "Bluegill",
                "bluegill",
                "Planned first Aquatic base skin for future runtime activation."
        );
        add(definitions, SkinId.SKIN_CLOWNFISH, FormId.FORM_AQUATIC, "Clownfish", "clownfish", null);
        add(definitions, SkinId.SKIN_PIKE, FormId.FORM_AQUATIC, "Pike", "pike", null);
        add(definitions, SkinId.SKIN_RAINBOW_TROUT, FormId.FORM_AQUATIC, "Rainbow Trout", "trout_rainbow", null);
        add(definitions, SkinId.SKIN_TANG, FormId.FORM_AQUATIC, "Tang", "tang", null);
        add(definitions, SkinId.SKIN_FROSTGILL, FormId.FORM_AQUATIC, "Frostgill", "frostgill", null);

        DEFINITIONS = Collections.unmodifiableMap(definitions);

        Map<String, SkinId> skinByAnimal = new HashMap<>();
        skinByAnimal.put("bear", SkinId.SKIN_GRIZZLY);
        skinByAnimal.put("polarbear", SkinId.SKIN_POLAR_BEAR);
        skinByAnimal.put("tiger", SkinId.SKIN_TIGER_SABERTOOTH);
        skinByAnimal.put("hyena", SkinId.SKIN_HYENA);
        skinByAnimal.put("wolf", SkinId.SKIN_WOLF);
        skinByAnimal.put("snowleopard", SkinId.SKIN_SNOW_LEOPARD);
        skinByAnimal.put("shark", SkinId.SKIN_SHARK);
        skinByAnimal.put("piranha", SkinId.SKIN_PIRANHA);
        skinByAnimal.put("snapjaw", SkinId.SKIN_SNAPJAW);
        skinByAnimal.put("antelope", SkinId.SKIN_ANTELOPE);
        skinByAnimal.put("deer_stag", SkinId.SKIN_DEER_STAG);
        skinByAnimal.put("horse", SkinId.SKIN_HORSE);
        skinByAnimal.put("horse_skeleton", SkinId.SKIN_SKELETAL_HORSE);
        skinByAnimal.put("camel", SkinId.SKIN_CAMEL);
        skinByAnimal.put("hawk", SkinId.SKIN_HAWK);
        skinByAnimal.put("crow", SkinId.SKIN_CROW);
        skinByAnimal.put("bat", SkinId.SKIN_BAT_SNOW_BAT);
        skinByAnimal.put("owl", SkinId.SKIN_OWL);
        skinByAnimal.put("parrot", SkinId.SKIN_PARROT);
        skinByAnimal.put("pigeon", SkinId.SKIN_PIGEON);
        skinByAnimal.put("pterodactyl", SkinId.SKIN_PTERODACTYL);
        skinByAnimal.put("duck", SkinId.SKIN_DUCK);
        skinByAnimal.put("rabbit", SkinId.SKIN_RABBIT);
        skinByAnimal.put("ram", SkinId.SKIN_RAM);
        skinByAnimal.put("goat", SkinId.SKIN_GOAT);
        skinByAnimal.put("moose", SkinId.SKIN_MOOSE);
        skinByAnimal.put("warthog", SkinId.SKIN_WARTHOG);
        skinByAnimal.put("bison", SkinId.SKIN_BISON);
        skinByAnimal.put("trilodon", SkinId.SKIN_TRILODON);
        skinByAnimal.put("bluegill", SkinId.SKIN_BLUEGILL);
        skinByAnimal.put("eel_moray", SkinId.SKIN_EEL);
        skinByAnimal.put("clownfish", SkinId.SKIN_CLOWNFISH);
        skinByAnimal.put("pike", SkinId.SKIN_PIKE);
        skinByAnimal.put("trout_rainbow", SkinId.SKIN_RAINBOW_TROUT);
        skinByAnimal.put("tang", SkinId.SKIN_TANG);
        skinByAnimal.put("frostgill", SkinId.SKIN_FROSTGILL);
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
