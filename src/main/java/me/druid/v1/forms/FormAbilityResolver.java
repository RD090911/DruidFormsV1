package me.druid.v1.forms;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class FormAbilityResolver {
    private static final FormAbilityProfile DEFAULT_PROFILE =
            new FormAbilityProfile(false, false, 1.0f, false, false, 0.0f, false, false);
    private static final Map<FormId, FormAbilityProfile> PROFILES;

    static {
        EnumMap<FormId, FormAbilityProfile> profiles = new EnumMap<>(FormId.class);

        profiles.put(FormId.FORM_GUARDIAN,
                new FormAbilityProfile(false, false, 1.0f, true, false, 0.20f, false, false));
        profiles.put(FormId.FORM_PROWLER,
                new FormAbilityProfile(false, false, 1.0f, false, true, 0.0f, false, false));
        profiles.put(FormId.FORM_FLIGHT,
                new FormAbilityProfile(true, false, 1.0f, false, false, 0.0f, false, false));
        profiles.put(FormId.FORM_TRAVEL,
                new FormAbilityProfile(false, false, 1.75f, false, false, 0.0f, false, false));
        profiles.put(FormId.FORM_STALKER,
                new FormAbilityProfile(false, true, 1.0f, false, false, 0.0f, false, false));
        profiles.put(FormId.FORM_SPRINGER,
                new FormAbilityProfile(false, false, 1.0f, false, false, 0.0f, true, false));
        profiles.put(FormId.FORM_FORAGER,
                new FormAbilityProfile(false, false, 1.0f, false, false, 0.0f, false, true));
        // Planning intent only (not runtime-wired yet):
        // Aquatic is intended as a fast-swim utility form with lower combat emphasis.
        profiles.put(FormId.FORM_AQUATIC,
                new FormAbilityProfile(false, true, 1.35f, false, false, 0.0f, false, false));

        PROFILES = Collections.unmodifiableMap(profiles);
    }

    private FormAbilityResolver() {
    }

    public static FormAbilityProfile get(FormId formId) {
        if (formId == null) {
            return DEFAULT_PROFILE;
        }
        return PROFILES.getOrDefault(formId, DEFAULT_PROFILE);
    }

    public static FormAbilityProfile getProfile(FormId formId) {
        return get(formId);
    }
}
