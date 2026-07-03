package me.druid.v1;

import java.util.Locale;
import java.util.Map;

final class DruidAbilityItemIds {
    private static final Map<String, String> LEGACY_ITEM_ID_ALIASES = Map.ofEntries(
            Map.entry("Shark_Bite", "Shark_Tooth"),
            Map.entry("Verdant_Shark_Bite", "Verdant_Shark_Tooth"),
            Map.entry("Primal_Shark_Bite", "Primal_Shark_Tooth"),
            Map.entry("Elder_Shark_Bite", "Elder_Shark_Tooth"),
            Map.entry("Guardian_Slash", "Bear_Skin"),
            Map.entry("Verdant_Guardian_Slash", "Verdant_Bear_Skin"),
            Map.entry("Primal_Guardian_Slash", "Primal_Bear_Skin"),
            Map.entry("Elder_Guardian_Slash", "Elder_Bear_Skin"),
            Map.entry("Warden_Life_Seed", "Life_Seed"),
            Map.entry("Verdant_Warden_Life_Seed", "Verdant_Life_Seed"),
            Map.entry("Primal_Warden_Life_Seed", "Primal_Life_Seed"),
            Map.entry("Elder_Warden_Life_Seed", "Elder_Life_Seed"),
            Map.entry("Shark_Hunters_Mark", "Stalker_Hunters_Mark"),
            Map.entry("Verdant_Shark_Hunters_Mark", "Verdant_Stalker_Hunters_Mark"),
            Map.entry("Primal_Shark_Hunters_Mark", "Primal_Stalker_Hunters_Mark"),
            Map.entry("Elder_Shark_Hunters_Mark", "Elder_Stalker_Hunters_Mark")
    );

    private static final Map<String, String> LEGACY_FORM_KEY_ALIASES = Map.of(
            "guardian", "bear",
            "prowler", "tiger",
            "stalker", "shark",
            "forager", "ram",
            "travel", "antelope",
            "flight", "hawk",
            "springer", "rabbit",
            "aquatic", "bluegill"
    );

    private DruidAbilityItemIds() {
    }

    static String normalizeItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return itemId;
        }
        return LEGACY_ITEM_ID_ALIASES.getOrDefault(itemId, itemId);
    }

    static String normalizeFormKey(String formKey) {
        if (formKey == null || formKey.isBlank()) {
            return formKey;
        }
        return LEGACY_FORM_KEY_ALIASES.getOrDefault(formKey.toLowerCase(Locale.ROOT), formKey);
    }

    static String canonicalCooldownItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return itemId;
        }

        String normalized = normalizeItemId(itemId);
        if (normalized == null || normalized.isBlank()) {
            return normalized;
        }

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("life_seed")) return "Life_Seed";
        if (lower.contains("gaias_touch")) return "Gaias_Touch";
        if (lower.contains("spring_of_renewal")) return "Spring_Of_Renewal";
        if (lower.contains("natures_resurgence")) return "Natures_Resurgence";
        if (lower.contains("tiger_bite")) return "Tiger_Bite";
        if (lower.contains("tiger_pounce")) return "Tiger_Pounce";
        if (lower.contains("tiger_stealth")) return "Tiger_Stealth";
        if (lower.contains("shark_surge")) return "Shark_Surge";
        if (lower.contains("shark_dive")) return "Shark_Dive";
        if (lower.contains("shark_breach")) return "Shark_Breach";
        if (lower.contains("stalker_hunters_mark")) return "Stalker_Hunters_Mark";
        if (lower.contains("guardian_ground_slam")) return "Guardian_Ground_Slam";
        if (lower.contains("guardian_oakenshield")) return "Guardian_Oakenshield";
        if (lower.contains("guardian_challenging_roar")) return "Guardian_Challenging_Roar";
        if (lower.contains("guardian_terrifying_roar")) return "Guardian_Terrifying_Roar";
        return normalized;
    }

    static boolean matchesCanonicalCooldownItem(String candidateItemId, String cooldownItemId) {
        String candidateKey = canonicalCooldownItemId(candidateItemId);
        String cooldownKey = canonicalCooldownItemId(cooldownItemId);
        return candidateKey != null && cooldownKey != null && candidateKey.equals(cooldownKey);
    }
}
