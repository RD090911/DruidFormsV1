package me.druid.v1;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import me.druid.v1.forms.FormAbilityProfile;
import me.druid.v1.forms.FormAbilityResolver;
import me.druid.v1.forms.FormId;
import me.druid.v1.forms.FormRuntimeBridge;
import com.hypixel.hytale.protocol.BenchRequirement;
import org.bson.BsonDocument;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ShapeshiftHandler {

    public static final ConcurrentHashMap<String, String> activeForms = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static final ConcurrentHashMap<String, Boolean> maintenanceActive = new ConcurrentHashMap<>();
    private static final Set<String> duckOxygenBonusApplied = ConcurrentHashMap.newKeySet();
    private static final Set<String> aquaticOxygenBonusApplied = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> pendingLoginRestoreRetry = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> queuedLoginRestoreRetry = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<UUID, DruidFormProgress> PLAYER_PROGRESS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Map<String, Map<String, Short>>> ABILITY_SLOT_PREFERENCES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Map<Short, ItemStack>> HUMAN_HOTBAR_SNAPSHOTS = new ConcurrentHashMap<>();
    private static final Path FORM_PROGRESS_STORAGE_PATH = Paths.get("run", "druid_form_progress.properties");
    private static final Path HUMAN_HOTBAR_SNAPSHOT_STORAGE_PATH = Paths.get("run", "druid_human_hotbar_snapshots.properties");
    private static volatile boolean abilitySlotPreferencesLoaded;
    private static volatile boolean formProgressLoaded;
    private static volatile boolean humanHotbarSnapshotsLoaded;
    private static final float DUCK_UNDERWATER_BASE_SPEED = 13.2f;
    private static final float DUCK_UNDERWATER_DRAG = 0.008f;
    private static final float DUCK_UNDERWATER_SPRINT_MULTIPLIER = 1.18f;
    private static final int DUCK_OXYGEN_BONUS = 135;
    private static final int AQUATIC_OXYGEN_BONUS = 10000;

    private static final String DRUID_ITEM = "Druid_Totem";
    private static final String WARDEN_ITEM = "Life_Seed";
    private static final String WARDEN_GAIAS_TOUCH_ITEM = "Gaias_Touch";
    private static final String WARDEN_SPRING_OF_RENEWAL_ITEM = "Spring_Of_Renewal";
    private static final String WARDEN_NATURES_RESURGENCE_ITEM = "Natures_Resurgence";
    private static final String PROWLER_BITE_ITEM = "Tiger_Bite";
    private static final String PROWLER_POUNCE_ITEM = "Tiger_Pounce";
    private static final String PROWLER_STEALTH_ITEM = "Tiger_Stealth";
    private static final String GUARDIAN_GROUND_SLAM_ITEM = "Guardian_Ground_Slam";
    private static final String VERDANT_GUARDIAN_GROUND_SLAM_ITEM = "Verdant_Guardian_Ground_Slam";
    private static final String PRIMAL_GUARDIAN_GROUND_SLAM_ITEM = "Primal_Guardian_Ground_Slam";
    private static final String ELDER_GUARDIAN_GROUND_SLAM_ITEM = "Elder_Guardian_Ground_Slam";
    private static final String GUARDIAN_OAKENSHIELD_ITEM = "Guardian_Oakenshield";
    private static final String PRIMAL_GUARDIAN_OAKENSHIELD_ITEM = "Primal_Guardian_Oakenshield";
    private static final String ELDER_GUARDIAN_OAKENSHIELD_ITEM = "Elder_Guardian_Oakenshield";
    private static final String GUARDIAN_CHALLENGING_ROAR_ITEM = "Guardian_Challenging_Roar";
    private static final String ELDER_GUARDIAN_CHALLENGING_ROAR_ITEM = "Elder_Guardian_Challenging_Roar";
    private static final String ROOTLIGHT_SPIRIT_ITEM = "Rootlight_Spirit_Ability";
    private static final short HOTBAR_SLOT_ONE = 0;
    private static final short HOTBAR_SIZE = 10;

    private static final Map<String, String> ALLOWED_FORMS = new HashMap<>();
    private final AnimalArmorService animalArmorService = new AnimalArmorService();

    static {
        ALLOWED_FORMS.put("bear", "Druid_Bear");
        ALLOWED_FORMS.put("ram", "Druid_Ram");
        ALLOWED_FORMS.put("duck", "Duck");
        ALLOWED_FORMS.put("shark", "Shark_Hammerhead");
        ALLOWED_FORMS.put("hawk", "Hawk");
        ALLOWED_FORMS.put("tiger", "Tiger_Sabertooth");
        ALLOWED_FORMS.put("rabbit", "Rabbit");
        ALLOWED_FORMS.put("antelope", "Antelope");
        ALLOWED_FORMS.put("bluegill", "Bluegill");
    }

    public static class AbilityConfig { }

    private enum TieredForm {
        TIGER("tiger", new String[]{"Tiger_Claw", "Verdant_Tiger_Claw", "Primal_Tiger_Claw", "Elder_Tiger_Claw"}),
        BEAR("bear", new String[]{"Bear_Skin", "Verdant_Bear_Skin", "Primal_Bear_Skin", "Elder_Bear_Skin"}),
        SHARK("shark", new String[]{"Shark_Tooth", "Verdant_Shark_Tooth", "Primal_Shark_Tooth", "Elder_Shark_Tooth"}),
        RAM("ram", new String[]{"Ram_Horn", "Verdant_Ram_Horn", "Primal_Ram_Horn", "Elder_Ram_Horn"}),
        WARDEN("warden", new String[]{"Life_Seed", "Verdant_Life_Seed", "Primal_Life_Seed", "Elder_Life_Seed"});

        private final String formKey;
        private final String[] itemIdsByTier;

        TieredForm(String formKey, String[] itemIdsByTier) {
            this.formKey = formKey;
            this.itemIdsByTier = itemIdsByTier;
        }

        static TieredForm fromFormKey(String formKey) {
            for (TieredForm value : values()) {
                if (value.formKey.equals(formKey)) return value;
            }
            return null;
        }

        String itemForTier(int tier) {
            int index = Math.max(1, Math.min(4, tier)) - 1;
            return itemIdsByTier[index];
        }
    }

    private static final class DruidFormProgress {
        private int tigerTier = 1;
        private int bearTier = 1;
        private int sharkTier = 1;
        private int ramTier = 1;
        private int wardenTier = 1;

        int getTier(TieredForm form) {
            switch (form) {
                case TIGER: return tigerTier;
                case BEAR: return bearTier;
                case SHARK: return sharkTier;
                case RAM: return ramTier;
                case WARDEN: return wardenTier;
                default: return 1;
            }
        }

        boolean setTier(TieredForm form, int newTier) {
            int clamped = Math.max(1, Math.min(4, newTier));
            if (getTier(form) == clamped) return false;
            switch (form) {
                case TIGER: tigerTier = clamped; break;
                case BEAR: bearTier = clamped; break;
                case SHARK: sharkTier = clamped; break;
                case RAM: ramTier = clamped; break;
                case WARDEN: wardenTier = clamped; break;
                default: return false;
            }
            return true;
        }
    }

    public int getTier(Player player, String formName) {
        TieredForm tieredForm = TieredForm.fromFormKey(canonicalizeFormKey(formName.toLowerCase(Locale.ROOT)));
        if (tieredForm == null) return 1;
        return getProgress(player).getTier(tieredForm);
    }

    public boolean canUpgradeTier(Player player, String formName) {
        TieredForm tieredForm = TieredForm.fromFormKey(canonicalizeFormKey(formName.toLowerCase(Locale.ROOT)));
        if (tieredForm == null) return false;
        return getProgress(player).getTier(tieredForm) < 4;
    }

    public boolean upgradeTier(Player player, String formName) {
        TieredForm tieredForm = TieredForm.fromFormKey(canonicalizeFormKey(formName.toLowerCase(Locale.ROOT)));
        if (tieredForm == null) return false;
        DruidFormProgress progress = getProgress(player);
        int currentTier = progress.getTier(tieredForm);
        if (currentTier >= 4) return false;
        progress.setTier(tieredForm, currentTier + 1);
        saveFormProgress();
        if (isCurrentForm(player, tieredForm.formKey)) {
            animalArmorService.refreshActiveFormArmor(player, tieredForm.formKey, currentTier + 1);
        }
        return true;
    }

    public boolean persistShrineUpgradeResult(Player player, String craftedItemId) {
        ShrineUpgradeResult upgradeResult = resolveShrineUpgradeResult(craftedItemId);
        if (player == null || upgradeResult == null) {
            return false;
        }

        DruidFormProgress progress = getProgress(player);
        int previousTier = progress.getTier(upgradeResult.form());
        if (previousTier >= upgradeResult.tier()) {
            return false;
        }

        if (!progress.setTier(upgradeResult.form(), upgradeResult.tier())) {
            return false;
        }

        saveFormProgress();
        return true;
    }

    private boolean isCurrentForm(Player player, String formKey) {
        if (player == null || formKey == null) return false;
        try {
            String playerName = DruidPlayerCompat.getPlayerName(player);
            return formKey.equals(formKeyFromModel(activeForms.get(playerName)));
        } catch (Exception ignored) {
            return false;
        }
    }

    private ShrineUpgradeResult resolveShrineUpgradeResult(String craftedItemId) {
        if (craftedItemId == null || craftedItemId.isBlank()) {
            return null;
        }
        for (TieredForm tieredForm : TieredForm.values()) {
            for (int tier = 1; tier <= 4; tier++) {
                if (tieredForm.itemForTier(tier).equals(craftedItemId)) {
                    return new ShrineUpgradeResult(tieredForm, tier);
                }
            }
        }
        return null;
    }

    private record ShrineUpgradeResult(TieredForm form, int tier) {
    }

    public static boolean isDruidShrineRecipe(Object recipeObj) {
        if (!(recipeObj instanceof com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe recipe)) {
            return false;
        }
        BenchRequirement[] requirements = recipe.getBenchRequirement();
        if (requirements == null) {
            return false;
        }
        for (BenchRequirement requirement : requirements) {
            if (requirement != null && "Druid_Shrine".equals(requirement.id)) {
                return true;
            }
        }
        return false;
    }

    public static String getCraftedRecipePrimaryOutputItemId(Object recipeObj) {
        if (!(recipeObj instanceof com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe recipe)) {
            return null;
        }
        MaterialQuantity primaryOutput = recipe.getPrimaryOutput();
        if (primaryOutput != null && primaryOutput.getItemId() != null && !primaryOutput.getItemId().isBlank()) {
            return primaryOutput.getItemId();
        }
        MaterialQuantity[] outputs = recipe.getOutputs();
        if (outputs == null) {
            return null;
        }
        for (MaterialQuantity output : outputs) {
            if (output != null && output.getItemId() != null && !output.getItemId().isBlank()) {
                return output.getItemId();
            }
        }
        return null;
    }

    public boolean shapeshift(Player player, String formName) {
        String cleanName = formName.toLowerCase(Locale.ROOT);
        String canonicalName = canonicalizeFormKey(cleanName);
        if (!ALLOWED_FORMS.containsKey(canonicalName)) {
            return false;
        }

        if (!isHoldingValidItem(player)) {
            sendPlayerMessage(player, "You must focus through a Druid Totem to channel this form.");
            return false;
        }

        syncTierProgressFromInventory(player);
        String targetModelId = ALLOWED_FORMS.get(canonicalName);
        if ("kweebec_rootling".equals(cleanName)) {
            targetModelId = "Kweebec_Rootling";
        } else if ("polarbear".equals(cleanName)) {
            targetModelId = "Bear_Polar";
        } else if ("hyena".equals(cleanName)) {
            targetModelId = "Hyena";
        } else if ("crow".equals(cleanName)) {
            targetModelId = "Crow";
        } else if ("bat".equals(cleanName)) {
            targetModelId = "Bat";
        } else if ("owl".equals(cleanName)) {
            targetModelId = "Owl_Brown";
        } else if ("parrot".equals(cleanName)) {
            targetModelId = "Parrot";
        } else if ("pigeon".equals(cleanName)) {
            targetModelId = "Pigeon";
        } else if ("pterodactyl".equals(cleanName)) {
            targetModelId = "Pterodactyl";
        } else if ("goat".equals(cleanName)) {
            targetModelId = "Goat";
        } else if ("moose".equals(cleanName)) {
            targetModelId = "Moose_Bull";
        } else if ("warthog".equals(cleanName)) {
            targetModelId = "Warthog";
        } else if ("bison".equals(cleanName)) {
            targetModelId = "Bison";
        } else if ("trilodon".equals(cleanName)) {
            targetModelId = "Trillodon";
        } else if ("eel_moray".equals(cleanName)) {
            targetModelId = "Eel_Moray";
        } else if ("clownfish".equals(cleanName)) {
            targetModelId = "Clownfish";
        } else if ("piranha".equals(cleanName)) {
            targetModelId = "Piranha";
        } else if ("pike".equals(cleanName)) {
            targetModelId = "Pike";
        } else if ("trout_rainbow".equals(cleanName)) {
            targetModelId = "Trout_Rainbow";
        } else if ("tang".equals(cleanName)) {
            targetModelId = "Tang_Blue";
        } else if ("snapjaw".equals(cleanName)) {
            targetModelId = "Snapjaw";
        } else if ("horse".equals(cleanName)) {
            targetModelId = "Horse";
        } else if ("horse_skeleton".equals(cleanName)) {
            targetModelId = "Horse_Skeleton";
        } else if ("snowleopard".equals(cleanName)) {
            targetModelId = "Leopard_Snow";
        } else if ("wolf".equals(cleanName)) {
            targetModelId = "Wolf_Black";
        } else if ("deer_stag".equals(cleanName)) {
            targetModelId = "Model_Deer_Stag";
        } else if ("camel".equals(cleanName)) {
            targetModelId = "Camel";
        } else if ("frostgill".equals(cleanName)) {
            targetModelId = "Frostgill";
        }
        if (!"bluegill".equals(canonicalName)) {
            return transform(player, targetModelId, canonicalName);
        }

        if (transform(player, targetModelId, canonicalName)) {
            return true;
        }
        if (!"Fish_Bluegill".equals(targetModelId)) {
            return transform(player, "Fish_Bluegill", canonicalName);
        }
        return false;
    }

    public boolean shapeshift(Player player, String formName, FormId formId) {
        if (formId == FormId.FORM_WARDEN) {
            if (!isHoldingValidItem(player)) {
                sendPlayerMessage(player, "You must focus through a Druid Totem to channel this form.");
                return false;
            }

            syncTierProgressFromInventory(player);
            String requestedWardenSkinKey = formName == null ? "" : formName.trim().toLowerCase(Locale.ROOT);
            String targetModelId = switch (requestedWardenSkinKey) {
                case "kweebec_elder" -> "Kweebec_Elder";
                case "kweebec_sapling_treesinger" -> "Kweebec_Sapling_Treesinger";
                default -> "Kweebec_Rootling";
            };
            return transform(player, targetModelId, "warden");
        }
        return shapeshift(player, formName);
    }

    public static FormId getActiveFormId(Player player) {
        if (player == null) {
            return null;
        }
        try {
            String playerName = DruidPlayerCompat.getPlayerName(player);
            if (playerName == null || playerName.isBlank()) {
                return null;
            }
            String activeModelId = activeForms.get(playerName);
            if (activeModelId == null || activeModelId.isBlank()) {
                return null;
            }
            String activeFormKey = new ShapeshiftHandler().formKeyFromModel(activeModelId);
            if (activeFormKey == null || activeFormKey.isBlank()) {
                return null;
            }
            return FormRuntimeBridge.resolveFormIdForAnimal(activeFormKey);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String canonicalizeFormKey(String key) {
        if ("guardian".equals(key)) {
            return "bear";
        }
        if ("prowler".equals(key)) {
            return "tiger";
        }
        if ("stalker".equals(key)) {
            return "shark";
        }
        if ("forager".equals(key)) {
            return "ram";
        }
        if ("travel".equals(key)) {
            return "antelope";
        }
        if ("flight".equals(key)) {
            return "hawk";
        }
        if ("springer".equals(key)) {
            return "rabbit";
        }
        if ("aquatic".equals(key)) {
            return "bluegill";
        }
        if ("kweebec_rootling".equals(key)) {
            return "ram";
        }
        if ("polarbear".equals(key)) {
            return "bear";
        }
        if ("hyena".equals(key)) {
            return "tiger";
        }
        if ("crow".equals(key)) {
            return "hawk";
        }
        if ("bat".equals(key)) {
            return "hawk";
        }
        if ("owl".equals(key)) {
            return "hawk";
        }
        if ("parrot".equals(key)) {
            return "hawk";
        }
        if ("pigeon".equals(key)) {
            return "hawk";
        }
        if ("pterodactyl".equals(key)) {
            return "hawk";
        }
        if ("fox".equals(key)) {
            return "rabbit";
        }
        if ("spider".equals(key)) {
            return "rabbit";
        }
        if ("frog".equals(key)) {
            return "rabbit";
        }
        if ("cat".equals(key)) {
            return "rabbit";
        }
        if ("goat".equals(key)) {
            return "ram";
        }
        if ("moose".equals(key)) {
            return "ram";
        }
        if ("warthog".equals(key)) {
            return "ram";
        }
        if ("bison".equals(key)) {
            return "ram";
        }
        if ("trilodon".equals(key)) {
            return "ram";
        }
        if ("eel_moray".equals(key)) {
            return "bluegill";
        }
        if ("clownfish".equals(key)) {
            return "bluegill";
        }
        if ("piranha".equals(key)) {
            return "shark";
        }
        if ("pike".equals(key)) {
            return "bluegill";
        }
        if ("trout_rainbow".equals(key)) {
            return "bluegill";
        }
        if ("tang".equals(key)) {
            return "bluegill";
        }
        if ("frostgill".equals(key)) {
            return "bluegill";
        }
        if ("snapjaw".equals(key)) {
            return "shark";
        }
        if ("horse".equals(key)) {
            return "antelope";
        }
        if ("horse_skeleton".equals(key)) {
            return "antelope";
        }
        if ("deer_stag".equals(key)) {
            return "antelope";
        }
        if ("camel".equals(key)) {
            return "antelope";
        }
        if ("snowleopard".equals(key)) {
            return "tiger";
        }
        if ("wolf".equals(key)) {
            return "tiger";
        }
        return key;
    }

    private String formKeyFromModel(String modelId) {
        if (modelId == null) return null;
        return switch (modelId) {
            case "Druid_Bear" -> "bear";
            case "Bear_Polar" -> "bear";
            case "Druid_Ram" -> "ram";
            case "Kweebec_Rootling" -> "warden";
            case "Kweebec_Elder" -> "warden";
            case "Kweebec_Sapling_Treesinger" -> "warden";
            case "Goat" -> "ram";
            case "Moose_Bull" -> "ram";
            case "Warthog" -> "ram";
            case "Bison" -> "ram";
            case "Trillodon" -> "ram";
            case "Duck" -> "duck";
            case "Shark_Hammerhead" -> "shark";
            case "Eel_Moray" -> "bluegill";
            case "Clownfish" -> "bluegill";
            case "Piranha" -> "shark";
            case "Pike" -> "bluegill";
            case "Trout_Rainbow" -> "bluegill";
            case "Tang_Blue" -> "bluegill";
            case "Frostgill" -> "bluegill";
            case "Snapjaw" -> "shark";
            case "Horse" -> "antelope";
            case "Horse_Skeleton" -> "antelope";
            case "Model_Deer_Stag" -> "antelope";
            case "Camel" -> "antelope";
            case "Hawk" -> "hawk";
            case "Crow" -> "hawk";
            case "Bat" -> "hawk";
            case "Owl_Brown" -> "hawk";
            case "Parrot" -> "hawk";
            case "Pigeon" -> "hawk";
            case "Pterodactyl" -> "hawk";
            case "Fox" -> "rabbit";
            case "Spider" -> "rabbit";
            case "Frog_Green" -> "rabbit";
            case "Cat" -> "rabbit";
            case "Tiger_Sabertooth" -> "tiger";
            case "Hyena" -> "tiger";
            case "Leopard_Snow" -> "tiger";
            case "Wolf_Black" -> "tiger";
            case "Rabbit" -> "rabbit";
            case "Antelope" -> "antelope";
            case "Bluegill", "Fish_Bluegill" -> "bluegill";
            default -> null;
        };
    }

    private static int clampTier(int tier) {
        return Math.max(1, Math.min(4, tier));
    }

    public boolean transform(Player player, String targetModelID, String shortName) {
        String playerName = DruidPlayerCompat.getPlayerName(player);
        String currentForm = activeForms.get(playerName);
        String previousForm = formKeyFromModel(currentForm);
        boolean enteringAnimalFromHuman = currentForm == null;
        shortName = canonicalizeFormKey(shortName.toLowerCase(Locale.ROOT));
        boolean enteringAquaticForm = isAquaticFormKey(shortName);
        if (currentForm != null && currentForm.equals(targetModelID)) {
            restoreHuman(player);
            return true;
        }

        if ("tiger".equals(previousForm) && !"tiger".equals(shortName)) {
            ProwlerStealthService.cleanupPlayer(player, "form-change");
            ProwlerPounceAbilityService.cleanupPlayer(player, "form-change");
        }
        if ("bear".equals(previousForm) && !"bear".equals(shortName)) {
            GuardianOakenshieldCooldownService.clearActive(player);
        }
        if ("warden".equals(previousForm) && !"warden".equals(shortName)) {
            DruidBuffDebuffHud.remove(player);
        }

        if (currentForm != null) {
            maintenanceActive.put(playerName, false);
            if ("ram".equals(previousForm)) {
                toggleHumanoidFlag(player, true);
            }
            if ("duck".equals(previousForm) && !"duck".equals(shortName)) {
                setDuckOxygenBonus(playerName, player, false);
            }
            if (isAquaticFormKey(previousForm) && !enteringAquaticForm) {
                setAquaticOxygenBonus(playerName, player, false);
            }
        }

        playPoofEffect(player);

        if (swapModel(player, targetModelID)) {
            if ("Horse".equals(targetModelID) || "Camel".equals(targetModelID) || "Horse_Skeleton".equals(targetModelID)) {
                applyHorseAttachments(player, targetModelID);
            }
            activeForms.put(playerName, targetModelID);
            String formLabel = FormRuntimeBridge.resolveFormLabelForAnimal(shortName);
            if (formLabel == null || formLabel.isBlank()) {
                formLabel = shortName;
            }
            sendPlayerMessage(player, "You have shapeshifted into the " + formLabel + " form!");

            updateCapabilities(player, shortName);
            swapAbilityItems(player, shortName, enteringAnimalFromHuman, previousForm);
            if ("ram".equals(shortName)) {
                toggleHumanoidFlag(player, false);
            }

            if (animalArmorService.isTrackedForm(shortName)) {
                int requestedTier = detectActiveFormTier(player, shortName);
                animalArmorService.onFormChanged(player, previousForm, shortName, clampTier(requestedTier));
            }

            TieredForm tieredForm = TieredForm.fromFormKey(shortName);
            maintenanceActive.put(playerName, tieredForm != null);
            if (tieredForm != null) {
                startFormMaintenance(player, shortName);
            }
            if ("duck".equals(shortName)) {
                setDuckOxygenBonus(playerName, player, true);
                startDuckMobilityMaintenance(player);
            }
            if (enteringAquaticForm) {
                setAquaticOxygenBonus(playerName, player, true);
                startAquaticOxygenMaintenance(player);
            }
            return true;
        }

        System.out.println("[Druid] Failed to shapeshift " + playerName + " into model: " + targetModelID);
        return false;
    }

    private void applyHorseAttachments(Player player, String modelAssetId) {
        try {
            Class<?> modelAssetClass = Class.forName("com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset");
            Class<?> modelClass = Class.forName("com.hypixel.hytale.server.core.asset.type.model.config.Model");

            Method getMapMethod = modelAssetClass.getMethod("getAssetMap");
            Object assetMap = getMapMethod.invoke(null);

            Method getAssetMethod = null;
            for (Method m : assetMap.getClass().getMethods()) {
                if (m.getName().equals("getAsset") && m.getParameterCount() == 1) {
                    getAssetMethod = m;
                    break;
                }
            }
            if (getAssetMethod == null) return;
            getAssetMethod.setAccessible(true);

            Object resolvedAsset = getAssetMethod.invoke(assetMap, modelAssetId);
            if (resolvedAsset == null) resolvedAsset = getAssetMethod.invoke(assetMap, "Hytale:" + modelAssetId);
            if (resolvedAsset == null) resolvedAsset = getAssetMethod.invoke(assetMap, "druid:" + modelAssetId);
            if (resolvedAsset == null) resolvedAsset = getAssetMethod.invoke(assetMap, "Druid:" + modelAssetId);
            if (resolvedAsset == null) return;

            Method generateRandomAttachmentIds = modelAssetClass.getMethod("generateRandomAttachmentIds");
            Object attachmentIdMap = generateRandomAttachmentIds.invoke(resolvedAsset);
            if (!(attachmentIdMap instanceof Map)) return;

            Method createScaledModel = modelClass.getMethod("createScaledModel", modelAssetClass, float.class, Map.class);
            Object assembledModel = createScaledModel.invoke(null, resolvedAsset, 1.0f, attachmentIdMap);
            if (assembledModel == null) return;

            injectRawModel(player, assembledModel);
        } catch (Exception ignored) {
        }
    }

    public void restoreHuman(Player player) {
        String playerName = DruidPlayerCompat.getPlayerName(player);
        String previousForm = formKeyFromModel(activeForms.get(playerName));
        if ("tiger".equals(previousForm)) {
            ProwlerStealthService.cleanupPlayer(player, "restore-human");
            ProwlerPounceAbilityService.cleanupPlayer(player, "restore-human");
        }
        if ("bear".equals(previousForm)) {
            GuardianOakenshieldCooldownService.clearActive(player);
        }
        if ("warden".equals(previousForm)) {
            DruidBuffDebuffHud.remove(player);
        }
        maintenanceActive.put(playerName, false);
        playPoofEffect(player);

        updateCapabilities(player, "human");

        if (swapModel(player, "Player")) {
            activeForms.remove(playerName);
            System.out.println("[Druid] " + playerName + " is Human again.");
            sendPlayerMessage(player, "You have returned to your human form.");

            refreshPlayerSkin(player);
            swapAbilityItems(player, "human", false, previousForm);
            animalArmorService.onRestoreToHuman(player);
            setDuckOxygenBonus(playerName, player, false);
            setAquaticOxygenBonus(playerName, player, false);

            toggleHumanoidFlag(player, true);
        }
    }

    public void handlePlayerConnect(PlayerConnectEvent event) {
        if (event == null) return;
        try {
            Player player = event.getPlayer();
            if (player != null) {
                syncTierProgressFromInventory(player);
                UUID playerId = safePlayerUuid(player);
                if (!isFormActive(player)) {
                    setDuckOxygenBonus(DruidPlayerCompat.getPlayerName(player), player, false);
                    setAquaticOxygenBonus(DruidPlayerCompat.getPlayerName(player), player, false);
                    restoreHumanStateOnLogin(player);
                    restorePendingHumanHotbarSnapshot(player, "login");
                }
                boolean transformed = isFormActive(player);
                animalArmorService.recoverHumanArmorOnLogin(player, transformed);
                sanitizeAnimalFormItemOnLogin(player);

                if (playerId != null) {
                    if (areLoginContainersReady(player)) {
                        pendingLoginRestoreRetry.remove(playerId);
                        queuedLoginRestoreRetry.remove(playerId);
                    } else {
                        pendingLoginRestoreRetry.add(playerId);
                        queuedLoginRestoreRetry.remove(playerId);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    public void handlePlayerReady(PlayerReadyEvent event) {
        if (event == null) return;
        try {
            Player player = event.getPlayer();
            if (player == null) return;

            syncTierProgressFromInventory(player);
            UUID playerId = safePlayerUuid(player);
            if (playerId == null || !pendingLoginRestoreRetry.contains(playerId)) return;

            runPendingRelogRestore(player, playerId, true);
        } catch (Exception ignored) {
        }
    }

    public void handlePlayerDisconnect(PlayerDisconnectEvent event) {
        if (event == null) return;
        String username = getPlayerName(event.getPlayerRef());
        String displayName = getDisconnectDisplayName(event);
        if (username != null || displayName != null) {
            boolean wasTransformed = (username != null && activeForms.containsKey(username))
                    || (displayName != null && activeForms.containsKey(displayName));
            if (wasTransformed) {
                Player player = getDisconnectPlayer(event);
                if (player != null) {
                    restorePendingHumanHotbarSnapshot(player, "disconnect");
                }
            }

            if (username != null) {
                maintenanceActive.remove(username);
                duckOxygenBonusApplied.remove(username);
                aquaticOxygenBonusApplied.remove(username);
                activeForms.remove(username);
            }
            if (displayName != null && !displayName.equals(username)) {
                maintenanceActive.remove(displayName);
                duckOxygenBonusApplied.remove(displayName);
                aquaticOxygenBonusApplied.remove(displayName);
                activeForms.remove(displayName);
            }

            if (!wasTransformed && username != null) {
                animalArmorService.clearPlayerState(username);
            }
        }
    }

    private String getPlayerName(com.hypixel.hytale.server.core.universe.PlayerRef playerRef) {
        if (playerRef == null) return null;
        try {
            return playerRef.getUsername();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String getDisconnectDisplayName(PlayerDisconnectEvent event) {
        if (event == null) return null;
        try {
            Method getPlayer = event.getClass().getMethod("getPlayer");
            Object playerObj = getPlayer.invoke(event);
            if (playerObj instanceof Player player) {
                return DruidPlayerCompat.getPlayerName(player);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Player getDisconnectPlayer(PlayerDisconnectEvent event) {
        if (event == null) return null;
        try {
            Method getPlayer = event.getClass().getMethod("getPlayer");
            Object playerObj = getPlayer.invoke(event);
            return playerObj instanceof Player ? (Player) playerObj : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void restoreHumanStateOnLogin(Player player) {
        if (player == null) return;
        try {
            updateCapabilities(player, "human");
        } catch (Exception ignored) {
        }
        try {
            swapModel(player, "Player");
        } catch (Exception ignored) {
        }
        try {
            refreshPlayerSkin(player);
        } catch (Exception ignored) {
        }
        try {
            toggleHumanoidFlag(player, true);
        } catch (Exception ignored) {
        }
    }

    private void restorePendingHumanHotbarSnapshot(Player player, String reason) {
        if (player == null) return;
        UUID playerUuid = safePlayerUuid(player);
        if (playerUuid == null) return;
        ensureHumanHotbarSnapshotsLoaded();
        if (!HUMAN_HOTBAR_SNAPSHOTS.containsKey(playerUuid)) return;

        try {
            Method getInventory = player.getClass().getMethod("getInventory");
            Object inventory = getInventory.invoke(player);
            if (inventory == null) return;

            Method getHotbar = inventory.getClass().getMethod("getHotbar");
            Object hotbar = getHotbar.invoke(inventory);
            if (hotbar == null) return;

            Method getStack = findGetStackMethod(hotbar);
            Method setStack = findSetStackMethod(hotbar);
            if (getStack == null || setStack == null) return;

            if (isLoginSnapshotRestore(reason) && !hasNonTotemDruidLoadoutInHotbar(hotbar, getStack)) {
                return;
            }

            restoreHumanHotbarSnapshot(playerUuid, hotbar, getStack, setStack);
        } catch (Exception ignored) {
        }
    }

    private boolean isLoginSnapshotRestore(String reason) {
        return reason != null && reason.startsWith("login");
    }

    private boolean hasNonTotemDruidLoadoutInHotbar(Object hotbar, Method getStack) {
        if (hotbar == null || getStack == null) {
            return false;
        }
        try {
            for (short slot = 0; slot < 9; slot++) {
                String itemKey = canonicalLoadoutItemKey(getItemIdFromStack(invokeGetStack(hotbar, getStack, slot)));
                if (itemKey != null && !DRUID_ITEM.equals(itemKey)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private void sanitizeAnimalFormItemOnLogin(Player player) {
        if (player == null) return;
        if (isFormActive(player)) return;

        try {
            Method getInventory = player.getClass().getMethod("getInventory");
            Object inventory = getInventory.invoke(player);
            if (inventory == null) return;

            Method getHotbar = inventory.getClass().getMethod("getHotbar");
            Object hotbar = getHotbar.invoke(inventory);
            if (hotbar == null) return;

            Method getStack = findGetStackMethod(hotbar);
            Method setStack = findSetStackMethod(hotbar);
            if (getStack == null || setStack == null) return;

            Class<?> itemStackClass = Class.forName("com.hypixel.hytale.server.core.inventory.ItemStack");
            Constructor<?> constructor = itemStackClass.getConstructor(String.class, int.class);
            Object totemItemStack = constructor.newInstance(DRUID_ITEM, 1);

            Short totemSlot = null;
            for (short slot = 0; slot < 9; slot++) {
                String itemKey = canonicalLoadoutItemKey(getItemIdFromStack(invokeGetStack(hotbar, getStack, slot)));
                if (itemKey != null && !DRUID_ITEM.equals(itemKey)) {
                    totemSlot = slot;
                    break;
                }
            }
            if (totemSlot == null) {
                return;
            }

            UUID playerUuid = safePlayerUuid(player);
            snapshotAbilitySlotPreferences(playerUuid, hotbar, getStack, null);
            boolean snapshotHadTotem = humanHotbarSnapshotContainsItem(playerUuid, DRUID_ITEM);
            clearDruidItemsFromInventory(inventory, hotbar, Collections.emptySet());
            restoreHumanHotbarSnapshot(playerUuid, hotbar, getStack, setStack);
            if (snapshotHadTotem) {
                return;
            }

            Short safeTotemSlot = resolveLoadoutSlot(
                    0,
                    DRUID_ITEM,
                    getAbilitySlotPreferences(playerUuid, "human"),
                    Collections.emptySet(),
                    hotbar,
                    getStack,
                    false
            );
            if (safeTotemSlot == null) {
                System.out.println("[DruidInventory] stage=loadout-skip-no-target-slot item=" + DRUID_ITEM);
                return;
            }
            invokeSetStack(hotbar, setStack, safeTotemSlot, totemItemStack);
        } catch (Exception ignored) {
        }
    }

    private void runPendingRelogRestore(Player player, UUID playerId, boolean allowOneShotWorldRetry) {
        if (player == null || playerId == null) return;
        if (!pendingLoginRestoreRetry.contains(playerId)) return;

        if (areLoginContainersReady(player)) {
            boolean transformed = isFormActive(player);
            animalArmorService.recoverHumanArmorOnLogin(player, transformed);
            if (!transformed) {
                restorePendingHumanHotbarSnapshot(player, "login-retry");
            }
            sanitizeAnimalFormItemOnLogin(player);
            pendingLoginRestoreRetry.remove(playerId);
            queuedLoginRestoreRetry.remove(playerId);
            return;
        }

        if (!allowOneShotWorldRetry) {
            pendingLoginRestoreRetry.remove(playerId);
            queuedLoginRestoreRetry.remove(playerId);
            return;
        }

        if (!queuedLoginRestoreRetry.add(playerId)) return;

        try {
            if (player.getWorld() != null) {
                player.getWorld().execute(() -> runPendingRelogRestore(player, playerId, false));
                return;
            }
        } catch (Exception ignored) {
        }

        pendingLoginRestoreRetry.remove(playerId);
        queuedLoginRestoreRetry.remove(playerId);
    }

    private boolean isFormActive(Player player) {
        if (player == null) return false;
        try {
            String playerName = DruidPlayerCompat.getPlayerName(player);
            return activeForms.containsKey(playerName);
        } catch (Exception ignored) {
            return false;
        }
    }

    private UUID safePlayerUuid(Player player) {
        if (player == null) return null;
        try {
            return player.getUuid();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean areLoginContainersReady(Player player) {
        if (player == null) return false;
        try {
            Method getInventory = player.getClass().getMethod("getInventory");
            Object inventory = getInventory.invoke(player);
            if (inventory == null) return false;

            Method getArmor = inventory.getClass().getMethod("getArmor");
            Object armor = getArmor.invoke(inventory);
            if (armor == null) return false;

            Method getHotbar = inventory.getClass().getMethod("getHotbar");
            Object hotbar = getHotbar.invoke(inventory);
            return hotbar != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private int detectActiveFormTier(Player player, String form) {
        TieredForm tieredForm = TieredForm.fromFormKey(canonicalizeFormKey(form.toLowerCase(Locale.ROOT)));
        if (tieredForm == null) return 1;
        int tierFromHeldItem = getTierFromHeldItem(player, tieredForm);
        if (tierFromHeldItem > 0) return tierFromHeldItem;
        return getProgress(player).getTier(tieredForm);
    }

    private int getTierFromHeldItem(Player player, TieredForm tieredForm) {
        try {
            Method getInventory = player.getClass().getMethod("getInventory");
            Object inventory = getInventory.invoke(player);
            if (inventory == null) return -1;

            try {
                Method getItemInHand = inventory.getClass().getMethod("getItemInHand");
                int held = detectTierFromItemStack(getItemInHand.invoke(inventory), tieredForm);
                if (held > 0) return held;
            } catch (Exception ignored) {
            }

            Method getHotbar = inventory.getClass().getMethod("getHotbar");
            Object hotbar = getHotbar.invoke(inventory);
            if (hotbar == null) return -1;

            Method getStack = findGetStackMethod(hotbar);
            if (getStack == null) return -1;
            int size = getContainerSize(hotbar);
            if (size <= 0) size = HOTBAR_SIZE;

            for (short slot = 0; slot < Math.min(size, HOTBAR_SIZE); slot++) {
                int detected = detectTierFromItemStack(invokeGetStack(hotbar, getStack, slot), tieredForm);
                if (detected > 0) return detected;
            }
            for (short slot = 1; slot <= Math.min(size, HOTBAR_SIZE); slot++) {
                int detected = detectTierFromItemStack(invokeGetStack(hotbar, getStack, slot), tieredForm);
                if (detected > 0) return detected;
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private int detectTierFromItemStack(Object itemStack, TieredForm form) {
        String itemId = getItemIdFromStack(itemStack);
        if (itemId == null) return -1;
        String lowerId = itemId.toLowerCase(Locale.ROOT);
        for (int tier = 4; tier >= 1; tier--) {
            if (lowerId.contains(form.itemForTier(tier).toLowerCase(Locale.ROOT))) {
                return tier;
            }
        }
        return -1;
    }

    private String getItemIdFromStack(Object itemStack) {
        if (itemStack == null) return null;
        try {
            Method getItemId = itemStack.getClass().getMethod("getItemId");
            Object rawItemId = getItemId.invoke(itemStack);
            return rawItemId instanceof String ? (String) rawItemId : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void swapAbilityItems(Player player, String targetForm, boolean enteringAnimalFromHuman, String previousForm) {
        try {
            ensureAbilitySlotPreferencesLoaded();
            String canonical = canonicalizeFormKey(targetForm.toLowerCase(Locale.ROOT));
            List<String> itemsToGive = resolveFormLoadoutItemIds(player, canonical);
            if (itemsToGive.isEmpty()) {
                itemsToGive = List.of(DRUID_ITEM);
            }

            Method getInventory = player.getClass().getMethod("getInventory");
            Object inventory = getInventory.invoke(player);

            Method getHotbar = inventory.getClass().getMethod("getHotbar");
            Object hotbarContainer = getHotbar.invoke(inventory);

            Class<?> itemStackClass = Class.forName("com.hypixel.hytale.server.core.inventory.ItemStack");
            Constructor<?> constructor = itemStackClass.getConstructor(String.class, int.class);

            Method getStack = findGetStackMethod(hotbarContainer);
            Method setItem = findSetStackMethod(hotbarContainer);
            if (getStack == null || setItem == null) return;

            UUID playerUuid = safePlayerUuid(player);
            snapshotAbilitySlotPreferences(playerUuid, hotbarContainer, getStack, previousForm);

            boolean restoringHuman = "human".equals(canonical);
            boolean snapshotHadTotem = restoringHuman
                    && humanHotbarSnapshotContainsItem(playerUuid, DRUID_ITEM);
            if (restoringHuman) {
                clearDruidItemsFromInventory(inventory, hotbarContainer, Collections.emptySet());
                restoreHumanHotbarSnapshot(playerUuid, hotbarContainer, getStack, setItem);
                if (snapshotHadTotem) {
                    return;
                }
            } else if (enteringAnimalFromHuman) {
                if (!createHumanHotbarSnapshot(playerUuid, hotbarContainer, getStack)
                        || !clearHumanHotbarForForm(hotbarContainer, getStack, setItem)) {
                    return;
                }
            }

            Map<String, Short> preferredSlots = getAbilitySlotPreferences(playerUuid, canonical);
            Map<Short, String> placedItems = new LinkedHashMap<>();
            for (int index = 0; index < itemsToGive.size() && index < HOTBAR_SIZE; index++) {
                String itemToGive = itemsToGive.get(index);
                Short targetSlot = resolveLoadoutSlot(
                        index,
                        itemToGive,
                        preferredSlots,
                        placedItems.keySet(),
                        hotbarContainer,
                        getStack,
                        !restoringHuman
                );
                if (targetSlot == null) {
                    System.out.println("[DruidInventory] stage=loadout-skip-no-target-slot item=" + itemToGive);
                    continue;
                }
                Object newItemStack = constructor.newInstance(itemToGive, 1);
                if (trySetItemVerified(hotbarContainer, setItem, getStack, targetSlot, newItemStack, itemToGive)) {
                    placedItems.put(targetSlot, itemToGive);
                }
            }

            clearDruidItemsFromInventory(inventory, hotbarContainer, placedItems.keySet());
            for (Map.Entry<Short, String> placedItem : placedItems.entrySet()) {
                Object newItemStack = constructor.newInstance(placedItem.getValue(), 1);
                trySetItemVerified(hotbarContainer, setItem, getStack, placedItem.getKey(), newItemStack, placedItem.getValue());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private List<String> resolveFormLoadoutItemIds(Player player, String canonicalForm) {
        if (canonicalForm == null || canonicalForm.isBlank() || "human".equals(canonicalForm)) {
            return List.of();
        }

        List<String> itemIds = new ArrayList<>();
        TieredForm tieredForm = TieredForm.fromFormKey(canonicalForm);
        if (tieredForm != null) {
            itemIds.add(tieredForm.itemForTier(getProgress(player).getTier(tieredForm)));
        } else {
            itemIds.add(DRUID_ITEM);
        }
        if ("tiger".equals(canonicalForm)) {
            itemIds.add(PROWLER_BITE_ITEM);
            itemIds.add(PROWLER_POUNCE_ITEM);
            itemIds.add(PROWLER_STEALTH_ITEM);
        }
        if ("bear".equals(canonicalForm) && tieredForm == TieredForm.BEAR) {
            int guardianTier = getProgress(player).getTier(tieredForm);
            String groundSlamItemId = guardianGroundSlamItemForTier(guardianTier);
            if (groundSlamItemId != null) {
                itemIds.add(groundSlamItemId);
            }
            String oakenshieldItemId = guardianOakenshieldItemForTier(guardianTier);
            if (oakenshieldItemId != null) {
                itemIds.add(oakenshieldItemId);
            }
            String challengingRoarItemId = guardianChallengingRoarItemForTier(guardianTier);
            if (challengingRoarItemId != null) {
                itemIds.add(challengingRoarItemId);
            }
        }
        if ("warden".equals(canonicalForm)) {
            itemIds.add(WARDEN_GAIAS_TOUCH_ITEM);
            itemIds.add(WARDEN_SPRING_OF_RENEWAL_ITEM);
            itemIds.add(WARDEN_NATURES_RESURGENCE_ITEM);
        }
        itemIds.add(ROOTLIGHT_SPIRIT_ITEM);
        return itemIds;
    }

    private String guardianGroundSlamItemForTier(int tier) {
        return switch (tier) {
            case 2 -> VERDANT_GUARDIAN_GROUND_SLAM_ITEM;
            case 3 -> PRIMAL_GUARDIAN_GROUND_SLAM_ITEM;
            case 4 -> ELDER_GUARDIAN_GROUND_SLAM_ITEM;
            default -> null;
        };
    }

    private String guardianOakenshieldItemForTier(int tier) {
        return switch (tier) {
            case 3 -> PRIMAL_GUARDIAN_OAKENSHIELD_ITEM;
            case 4 -> ELDER_GUARDIAN_OAKENSHIELD_ITEM;
            default -> null;
        };
    }

    private String guardianChallengingRoarItemForTier(int tier) {
        return tier >= 4 ? ELDER_GUARDIAN_CHALLENGING_ROAR_ITEM : null;
    }

    private Short resolveLoadoutSlot(
            int defaultIndex,
            String itemId,
            Map<String, Short> preferredSlots,
            Set<Short> usedSlots,
            Object hotbarContainer,
            Method getStack,
            boolean allowOccupiedSlot
    ) {
        String itemKey = canonicalLoadoutItemKey(itemId);
        boolean primaryItem = defaultIndex == 0;
        boolean movablePrimaryItem = "Tiger_Claw".equals(itemKey);
        if (!primaryItem || movablePrimaryItem) {
            Short preferredSlot = preferredSlots.get(itemKey);
            if (preferredSlot != null
                    && isValidAbilitySlot(preferredSlot)
                    && !usedSlots.contains(preferredSlot)
                    && (allowOccupiedSlot || isSlotAvailableForLoadout(hotbarContainer, getStack, preferredSlot))) {
                return preferredSlot;
            }
        }

        short defaultSlot = ROOTLIGHT_SPIRIT_ITEM.equals(canonicalLoadoutItemKey(itemId))
                ? (short) 8
                : (short) defaultIndex;
        if (isValidAbilitySlot(defaultSlot)
                && !usedSlots.contains(defaultSlot)
                && (allowOccupiedSlot || isSlotAvailableForLoadout(hotbarContainer, getStack, defaultSlot))) {
            return defaultSlot;
        }

        return findFirstAvailableLoadoutSlot(usedSlots, hotbarContainer, getStack, allowOccupiedSlot);
    }

    private Short findFirstAvailableLoadoutSlot(
            Set<Short> usedSlots,
            Object hotbarContainer,
            Method getStack,
            boolean allowOccupiedSlot
    ) {
        for (short slot = 0; slot < 9; slot++) {
            if (!usedSlots.contains(slot)
                    && (allowOccupiedSlot || isSlotAvailableForLoadout(hotbarContainer, getStack, slot))) {
                return slot;
            }
        }
        return null;
    }

    private boolean createHumanHotbarSnapshot(UUID playerUuid, Object hotbarContainer, Method getStack) {
        if (playerUuid == null || hotbarContainer == null || getStack == null) {
            return false;
        }
        ensureHumanHotbarSnapshotsLoaded();
        if (HUMAN_HOTBAR_SNAPSHOTS.containsKey(playerUuid)) {
            return true;
        }

        Map<Short, ItemStack> snapshot = new LinkedHashMap<>();
        try {
            for (short slot = 0; slot < 9; slot++) {
                Object currentItem = invokeGetStack(hotbarContainer, getStack, slot);
                if (currentItem == null) {
                    continue;
                }
                ItemStack item = cloneItemStack(currentItem);
                if (item == null) {
                    return false;
                }
                snapshot.put(slot, item);
            }
        } catch (Exception ignored) {
            return false;
        }

        Map<Short, ItemStack> existing = HUMAN_HOTBAR_SNAPSHOTS.putIfAbsent(
                playerUuid,
                Collections.unmodifiableMap(snapshot)
        );
        if (existing == null) {
            saveHumanHotbarSnapshots();
        }
        return true;
    }

    private boolean clearHumanHotbarForForm(Object hotbarContainer, Method getStack, Method setItem) {
        if (hotbarContainer == null || getStack == null || setItem == null) {
            return false;
        }

        boolean clearedAll = true;
        for (short slot = 0; slot < 9; slot++) {
            try {
                Object currentItem = invokeGetStack(hotbarContainer, getStack, slot);
                if (currentItem == null) {
                    continue;
                }
                String itemId = getItemIdFromStack(currentItem);
                invokeSetStack(hotbarContainer, setItem, slot, null);
                if (invokeGetStack(hotbarContainer, getStack, slot) != null) {
                    clearedAll = false;
                    continue;
                }
            } catch (Exception ignored) {
                clearedAll = false;
            }
        }
        return clearedAll;
    }

    private boolean humanHotbarSnapshotContainsItem(UUID playerUuid, String itemId) {
        if (playerUuid == null || itemId == null) {
            return false;
        }
        ensureHumanHotbarSnapshotsLoaded();
        Map<Short, ItemStack> snapshot = HUMAN_HOTBAR_SNAPSHOTS.get(playerUuid);
        if (snapshot == null) {
            return false;
        }
        String expectedItemKey = canonicalLoadoutItemKey(itemId);
        for (ItemStack item : snapshot.values()) {
            if (Objects.equals(expectedItemKey, canonicalLoadoutItemKey(item.getItemId()))) {
                return true;
            }
        }
        return false;
    }

    private void restoreHumanHotbarSnapshot(
            UUID playerUuid,
            Object hotbarContainer,
            Method getStack,
            Method setItem
    ) {
        if (playerUuid == null || hotbarContainer == null || getStack == null || setItem == null) {
            return;
        }

        ensureHumanHotbarSnapshotsLoaded();
        Map<Short, ItemStack> snapshot = HUMAN_HOTBAR_SNAPSHOTS.get(playerUuid);
        if (snapshot == null) {
            return;
        }

        boolean restoredAll = true;
        for (short slot = 0; slot < 9; slot++) {
            try {
                invokeSetStack(hotbarContainer, setItem, slot, null);
                if (invokeGetStack(hotbarContainer, getStack, slot) != null) {
                    restoredAll = false;
                }
            } catch (Exception ignored) {
                restoredAll = false;
            }
        }

        for (Map.Entry<Short, ItemStack> entry : snapshot.entrySet()) {
            ItemStack restoredItem = cloneItemStack(entry.getValue());
            if (restoredItem == null || !trySetItemVerified(
                    hotbarContainer,
                    setItem,
                    getStack,
                    entry.getKey(),
                    restoredItem,
                    restoredItem.getItemId()
            )) {
                restoredAll = false;
                continue;
            }
        }
        if (restoredAll) {
            HUMAN_HOTBAR_SNAPSHOTS.remove(playerUuid, snapshot);
            saveHumanHotbarSnapshots();
        }
    }

    private ItemStack cloneItemStack(Object itemStack) {
        if (!(itemStack instanceof ItemStack source)) {
            return null;
        }
        try {
            return new ItemStack(
                    source.getItemId(),
                    source.getQuantity(),
                    source.getDurability(),
                    source.getMaxDurability(),
                    source.getMetadata()
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isSlotAvailableForLoadout(Object hotbarContainer, Method getStack, short slot) {
        try {
            Object existingStack = invokeGetStack(hotbarContainer, getStack, slot);
            return existingStack == null || isDruidAbilityStack(existingStack);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void snapshotAbilitySlotPreferences(
            UUID playerUuid,
            Object hotbarContainer,
            Method getStack,
            String currentForm
    ) {
        if (playerUuid == null || hotbarContainer == null || getStack == null) return;
        ensureAbilitySlotPreferencesLoaded();

        boolean changed = false;
        Set<String> seenKeys = new HashSet<>();
        try {
            for (short slot = 0; slot < 9; slot++) {
                String itemId = getItemIdFromStack(invokeGetStack(hotbarContainer, getStack, slot));
                String itemKey = canonicalLoadoutItemKey(itemId);
                String formKey = ROOTLIGHT_SPIRIT_ITEM.equals(itemKey)
                        ? canonicalizeFormKey(currentForm)
                        : resolveLoadoutFormForItemId(itemId);
                if (formKey == null || itemKey == null || !seenKeys.add(formKey + ":" + itemKey)) {
                    continue;
                }

                Short previousSlot = ABILITY_SLOT_PREFERENCES
                        .computeIfAbsent(playerUuid, ignored -> new ConcurrentHashMap<>())
                        .computeIfAbsent(formKey, ignored -> new ConcurrentHashMap<>())
                        .put(itemKey, slot);
                if (previousSlot == null || previousSlot.shortValue() != slot) {
                    changed = true;
                }
            }
        } catch (Exception ignored) {
        }
        if (changed) {
            DruidAbilitySlotPreferenceStore.save(ABILITY_SLOT_PREFERENCES);
        }
    }

    private Map<String, Short> getAbilitySlotPreferences(UUID playerUuid, String formKey) {
        ensureAbilitySlotPreferencesLoaded();
        if (playerUuid == null || formKey == null || formKey.isBlank()) {
            return Collections.emptyMap();
        }

        Map<String, Map<String, Short>> preferencesByForm = ABILITY_SLOT_PREFERENCES.get(playerUuid);
        if (preferencesByForm == null) {
            return Collections.emptyMap();
        }

        Map<String, Short> preferences = preferencesByForm.get(formKey);
        return preferences == null ? Collections.emptyMap() : preferences;
    }

    private static void ensureAbilitySlotPreferencesLoaded() {
        if (abilitySlotPreferencesLoaded) {
            return;
        }

        synchronized (ABILITY_SLOT_PREFERENCES) {
            if (abilitySlotPreferencesLoaded) {
                return;
            }
            DruidAbilitySlotPreferenceStore.loadInto(ABILITY_SLOT_PREFERENCES);
            abilitySlotPreferencesLoaded = true;
        }
    }

    private static void ensureFormProgressLoaded() {
        if (formProgressLoaded) {
            return;
        }

        synchronized (PLAYER_PROGRESS) {
            if (formProgressLoaded) {
                return;
            }
            formProgressLoaded = true;
            if (!Files.exists(FORM_PROGRESS_STORAGE_PATH)) {
                return;
            }

            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(FORM_PROGRESS_STORAGE_PATH)) {
                properties.load(input);
            } catch (IOException exception) {
                System.out.println("[DruidPersistence] form-progress load skipped reason=" + exception.getClass().getSimpleName());
                return;
            }

            for (String key : properties.stringPropertyNames()) {
                int delimiter = key.indexOf('.');
                if (delimiter <= 0 || delimiter >= key.length() - 1) {
                    continue;
                }
                UUID playerUuid;
                try {
                    playerUuid = UUID.fromString(key.substring(0, delimiter));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                TieredForm tieredForm = TieredForm.fromFormKey(key.substring(delimiter + 1));
                Integer tier = parseInteger(properties.getProperty(key));
                if (tieredForm == null || tier == null) {
                    continue;
                }
                PLAYER_PROGRESS
                        .computeIfAbsent(playerUuid, ignored -> new DruidFormProgress())
                        .setTier(tieredForm, tier);
            }
        }
    }

    private static synchronized void saveFormProgress() {
        ensureFormProgressLoaded();
        Properties properties = new Properties();
        for (Map.Entry<UUID, DruidFormProgress> entry : PLAYER_PROGRESS.entrySet()) {
            UUID playerUuid = entry.getKey();
            DruidFormProgress progress = entry.getValue();
            if (playerUuid == null || progress == null) {
                continue;
            }
            for (TieredForm tieredForm : TieredForm.values()) {
                properties.setProperty(playerUuid + "." + tieredForm.formKey, Integer.toString(progress.getTier(tieredForm)));
            }
        }
        try {
            Files.createDirectories(FORM_PROGRESS_STORAGE_PATH.getParent());
            try (OutputStream output = Files.newOutputStream(FORM_PROGRESS_STORAGE_PATH)) {
                properties.store(output, "DruidForms form progress");
            }
        } catch (IOException exception) {
            System.out.println("[DruidPersistence] form-progress save failed reason=" + exception.getClass().getSimpleName());
        }
    }

    private static void ensureHumanHotbarSnapshotsLoaded() {
        if (humanHotbarSnapshotsLoaded) {
            return;
        }

        synchronized (HUMAN_HOTBAR_SNAPSHOTS) {
            if (humanHotbarSnapshotsLoaded) {
                return;
            }
            humanHotbarSnapshotsLoaded = true;
            if (!Files.exists(HUMAN_HOTBAR_SNAPSHOT_STORAGE_PATH)) {
                return;
            }

            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(HUMAN_HOTBAR_SNAPSHOT_STORAGE_PATH)) {
                properties.load(input);
            } catch (IOException exception) {
                System.out.println("[DruidPersistence] hotbar-snapshot load skipped reason=" + exception.getClass().getSimpleName());
                return;
            }

            Map<UUID, Map<Short, ItemStack>> loadedSnapshots = new HashMap<>();
            for (String key : properties.stringPropertyNames()) {
                if (!key.endsWith(".id")) {
                    continue;
                }
                String prefix = key.substring(0, key.length() - ".id".length());
                int slotDelimiter = prefix.lastIndexOf(".slot.");
                if (slotDelimiter <= 0) {
                    continue;
                }
                UUID playerUuid;
                short slot;
                try {
                    playerUuid = UUID.fromString(prefix.substring(0, slotDelimiter));
                    slot = Short.parseShort(prefix.substring(slotDelimiter + ".slot.".length()));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (slot < 0 || slot >= 9) {
                    continue;
                }
                ItemStack stack = decodeSnapshotItem(properties, prefix);
                if (stack == null) {
                    continue;
                }
                loadedSnapshots
                        .computeIfAbsent(playerUuid, ignored -> new LinkedHashMap<>())
                        .put(slot, stack);
            }

            for (Map.Entry<UUID, Map<Short, ItemStack>> entry : loadedSnapshots.entrySet()) {
                HUMAN_HOTBAR_SNAPSHOTS.putIfAbsent(entry.getKey(), Collections.unmodifiableMap(entry.getValue()));
            }
        }
    }

    private static synchronized void saveHumanHotbarSnapshots() {
        ensureHumanHotbarSnapshotsLoaded();
        Properties properties = new Properties();
        for (Map.Entry<UUID, Map<Short, ItemStack>> playerEntry : HUMAN_HOTBAR_SNAPSHOTS.entrySet()) {
            UUID playerUuid = playerEntry.getKey();
            Map<Short, ItemStack> snapshot = playerEntry.getValue();
            if (playerUuid == null || snapshot == null) {
                continue;
            }
            for (Map.Entry<Short, ItemStack> slotEntry : snapshot.entrySet()) {
                Short slot = slotEntry.getKey();
                ItemStack stack = slotEntry.getValue();
                if (slot == null || slot < 0 || slot >= 9 || stack == null) {
                    continue;
                }
                encodeSnapshotItem(properties, playerUuid + ".slot." + slot, stack);
            }
        }
        try {
            Files.createDirectories(HUMAN_HOTBAR_SNAPSHOT_STORAGE_PATH.getParent());
            try (OutputStream output = Files.newOutputStream(HUMAN_HOTBAR_SNAPSHOT_STORAGE_PATH)) {
                properties.store(output, "DruidForms human hotbar snapshots");
            }
        } catch (IOException exception) {
            System.out.println("[DruidPersistence] hotbar-snapshot save failed reason=" + exception.getClass().getSimpleName());
        }
    }

    private static void encodeSnapshotItem(Properties properties, String prefix, ItemStack stack) {
        if (properties == null || prefix == null || stack == null || stack.getItemId() == null) {
            return;
        }
        properties.setProperty(prefix + ".id", encodeText(stack.getItemId()));
        properties.setProperty(prefix + ".quantity", Integer.toString(stack.getQuantity()));
        properties.setProperty(prefix + ".durability", Double.toString(stack.getDurability()));
        properties.setProperty(prefix + ".maxDurability", Double.toString(stack.getMaxDurability()));
        BsonDocument metadata = stack.getMetadata();
        if (metadata != null && !metadata.isEmpty()) {
            properties.setProperty(prefix + ".metadata", encodeText(metadata.toJson()));
        }
    }

    private static ItemStack decodeSnapshotItem(Properties properties, String prefix) {
        if (properties == null || prefix == null) {
            return null;
        }
        String itemId = decodeText(properties.getProperty(prefix + ".id"));
        Integer quantity = parseInteger(properties.getProperty(prefix + ".quantity"));
        Double durability = parseDouble(properties.getProperty(prefix + ".durability"));
        Double maxDurability = parseDouble(properties.getProperty(prefix + ".maxDurability"));
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        BsonDocument metadata = null;
        String rawMetadata = decodeText(properties.getProperty(prefix + ".metadata"));
        if (rawMetadata != null && !rawMetadata.isBlank()) {
            try {
                metadata = BsonDocument.parse(rawMetadata);
            } catch (Exception ignored) {
                metadata = null;
            }
        }
        int safeQuantity = quantity == null ? 1 : Math.max(1, quantity);
        double safeDurability = durability == null ? 0.0 : durability;
        double safeMaxDurability = maxDurability == null ? 0.0 : maxDurability;
        try {
            return verifiedSnapshotItem(new ItemStack(itemId, safeQuantity, safeDurability, safeMaxDurability, metadata));
        } catch (Exception ignored) {
            try {
                return verifiedSnapshotItem(new ItemStack(itemId, safeQuantity, metadata));
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private static ItemStack verifiedSnapshotItem(ItemStack stack) {
        if (stack == null || stack.getItemId() == null || stack.getItemId().isBlank()) {
            return null;
        }
        try {
            return stack.isValid() ? stack : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String encodeText(String value) {
        if (value == null) {
            return null;
        }
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean isValidAbilitySlot(short slot) {
        return slot >= 0 && slot < 9;
    }

    private String resolveLoadoutFormForItemId(String itemId) {
        String itemKey = canonicalLoadoutItemKey(itemId);
        if (itemKey == null) return null;
        if (DRUID_ITEM.equals(itemKey)) return "human";
        if (WARDEN_ITEM.equals(itemKey)
                || WARDEN_GAIAS_TOUCH_ITEM.equals(itemKey)
                || WARDEN_SPRING_OF_RENEWAL_ITEM.equals(itemKey)
                || WARDEN_NATURES_RESURGENCE_ITEM.equals(itemKey)) {
            return "warden";
        }
        if (GUARDIAN_GROUND_SLAM_ITEM.equals(itemKey)) return "bear";
        if (GUARDIAN_OAKENSHIELD_ITEM.equals(itemKey)) return "bear";
        if (GUARDIAN_CHALLENGING_ROAR_ITEM.equals(itemKey)) return "bear";
        if ("Bear_Skin".equals(itemKey)) return "bear";
        if ("Tiger_Claw".equals(itemKey)) return "tiger";
        if (PROWLER_BITE_ITEM.equals(itemKey)) return "tiger";
        if (PROWLER_POUNCE_ITEM.equals(itemKey)) return "tiger";
        if (PROWLER_STEALTH_ITEM.equals(itemKey)) return "tiger";
        if ("Shark_Tooth".equals(itemKey)) return "shark";
        if ("Ram_Horn".equals(itemKey)) return "ram";
        return null;
    }

    private String canonicalLoadoutItemKey(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        if (itemId.contains(DRUID_ITEM)) return DRUID_ITEM;
        if (itemId.contains("Life_Seed")) return WARDEN_ITEM;
        if (itemId.contains("Gaias_Touch")) return WARDEN_GAIAS_TOUCH_ITEM;
        if (itemId.contains("Spring_Of_Renewal")) return WARDEN_SPRING_OF_RENEWAL_ITEM;
        if (itemId.contains("Natures_Resurgence")) return WARDEN_NATURES_RESURGENCE_ITEM;
        if (itemId.contains("Rootlight_Spirit_Ability")) return ROOTLIGHT_SPIRIT_ITEM;
        if (itemId.contains(GUARDIAN_GROUND_SLAM_ITEM)) return GUARDIAN_GROUND_SLAM_ITEM;
        if (itemId.contains(GUARDIAN_OAKENSHIELD_ITEM)) return GUARDIAN_OAKENSHIELD_ITEM;
        if (itemId.contains(GUARDIAN_CHALLENGING_ROAR_ITEM)) return GUARDIAN_CHALLENGING_ROAR_ITEM;
        if (itemId.contains("Bear_Skin")) return "Bear_Skin";
        if (itemId.contains("Tiger_Claw")) return "Tiger_Claw";
        if (itemId.contains(PROWLER_BITE_ITEM)) return PROWLER_BITE_ITEM;
        if (itemId.contains(PROWLER_POUNCE_ITEM)) return PROWLER_POUNCE_ITEM;
        if (itemId.contains(PROWLER_STEALTH_ITEM)) return PROWLER_STEALTH_ITEM;
        if (itemId.contains("Shark_Tooth")) return "Shark_Tooth";
        if (itemId.contains("Ram_Horn")) return "Ram_Horn";
        return null;
    }

    private boolean isHoldingValidItem(Player player) {
        try {
            Method getInventory = player.getClass().getMethod("getInventory");
            Object inventory = getInventory.invoke(player);
            if (inventory == null) return false;

            try {
                Method getItemInHand = inventory.getClass().getMethod("getItemInHand");
                Object itemStack = getItemInHand.invoke(inventory);
                if (isDruidAbilityStack(itemStack)) return true;
            } catch (Exception ignored) { }

            Method getHotbar = inventory.getClass().getMethod("getHotbar");
            Object hotbarContainer = getHotbar.invoke(inventory);
            if (hotbarContainer == null) return false;

            Method getStack = findGetStackMethod(hotbarContainer);
            if (getStack == null) return false;
            return hasDruidItemInHotbar(hotbarContainer, getStack);

        } catch (Exception e) {
            return false;
        }
    }

    private boolean isDruidAbilityStack(Object itemStack) {
        if (itemStack == null) return false;
        try {
            Method getItemId = itemStack.getClass().getMethod("getItemId");
            Object itemIdObj = getItemId.invoke(itemStack);
            if (!(itemIdObj instanceof String)) return false;
            String lowerId = ((String) itemIdObj).toLowerCase(Locale.ROOT);
            return isDruidAbilityItemId(lowerId);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isDruidAbilityItemId(String lowerId) {
        if (lowerId == null) return false;
        if (lowerId.contains("druid_totem")) return true;
        if (lowerId.contains("druid") && lowerId.contains("totem")) return true;
        if (lowerId.contains("life_seed")) return true;
        if (lowerId.contains("gaias_touch")) return true;
        if (lowerId.contains("spring_of_renewal")) return true;
        if (lowerId.contains("natures_resurgence")) return true;
        if (lowerId.contains("rootlight_spirit_ability")) return true;
        if (lowerId.contains(GUARDIAN_GROUND_SLAM_ITEM.toLowerCase(Locale.ROOT))) return true;
        if (lowerId.contains(GUARDIAN_OAKENSHIELD_ITEM.toLowerCase(Locale.ROOT))) return true;
        if (lowerId.contains(GUARDIAN_CHALLENGING_ROAR_ITEM.toLowerCase(Locale.ROOT))) return true;
        if (lowerId.contains(PROWLER_BITE_ITEM.toLowerCase(Locale.ROOT))) return true;
        if (lowerId.contains(PROWLER_POUNCE_ITEM.toLowerCase(Locale.ROOT))) return true;
        if (lowerId.contains(PROWLER_STEALTH_ITEM.toLowerCase(Locale.ROOT))) return true;
        for (TieredForm tieredForm : TieredForm.values()) {
            for (int tier = 1; tier <= 4; tier++) {
                String itemId = tieredForm.itemForTier(tier).toLowerCase(Locale.ROOT);
                if (lowerId.contains(itemId)) return true;
            }
        }
        return false;
    }

    private boolean isAnimalTransformationItemId(String lowerId) {
        if (lowerId == null) return false;
        for (TieredForm tieredForm : TieredForm.values()) {
            for (int tier = 1; tier <= 4; tier++) {
                String itemId = tieredForm.itemForTier(tier).toLowerCase(Locale.ROOT);
                if (lowerId.contains(itemId)) return true;
            }
        }
        return false;
    }

    private boolean isAnimalTransformationStack(Object itemStack) {
        if (itemStack == null) return false;
        try {
            Method getItemId = itemStack.getClass().getMethod("getItemId");
            Object itemIdObj = getItemId.invoke(itemStack);
            return itemIdObj instanceof String && isAnimalTransformationItemId(((String) itemIdObj).toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasDruidItemInHotbar(Object hotbarContainer, Method getStack) {
        try {
            for (short slot = 0; slot < HOTBAR_SIZE; slot++) {
                Object itemStack = invokeGetStack(hotbarContainer, getStack, slot);
                if (isDruidAbilityStack(itemStack)) return true;
            }

            for (short slot = 1; slot <= HOTBAR_SIZE; slot++) {
                Object itemStack = invokeGetStack(hotbarContainer, getStack, slot);
                if (isDruidAbilityStack(itemStack)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void clearDruidItemsFromHotbar(Object hotbarContainer, Method getStack, Method setItem, Class<?> itemStackClass, short keepSlot) {
        try {
            for (short slot = 0; slot < HOTBAR_SIZE; slot++) {
                if (slot == keepSlot) continue;
                Object itemStack = invokeGetStack(hotbarContainer, getStack, slot);
                if (itemStack == null) continue;
                Method getItemId = itemStack.getClass().getMethod("getItemId");
                Object itemIdObj = getItemId.invoke(itemStack);
                if (!(itemIdObj instanceof String)) continue;
                String lowerId = ((String) itemIdObj).toLowerCase(Locale.ROOT);
                if (!isDruidAbilityItemId(lowerId)) continue;
                try {
                    invokeSetStack(hotbarContainer, setItem, slot, null);
                } catch (Exception ignored) { }
            }

            for (short slot = 1; slot <= HOTBAR_SIZE; slot++) {
                if (slot == keepSlot) continue;
                Object itemStack = invokeGetStack(hotbarContainer, getStack, slot);
                if (itemStack == null) continue;
                Method getItemId = itemStack.getClass().getMethod("getItemId");
                Object itemIdObj = getItemId.invoke(itemStack);
                if (!(itemIdObj instanceof String)) continue;
                String lowerId = ((String) itemIdObj).toLowerCase(Locale.ROOT);
                if (!isDruidAbilityItemId(lowerId)) continue;
                try {
                    invokeSetStack(hotbarContainer, setItem, slot, null);
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    private void clearDruidItemsFromInventory(Object inventory, Object hotbarContainer, short keepHotbarSlot) {
        clearDruidItemsFromInventory(inventory, hotbarContainer, Collections.singleton(keepHotbarSlot));
    }

    private void clearDruidItemsFromInventory(Object inventory, Object hotbarContainer, Set<Short> keepHotbarSlots) {
        Set<Short> keepSlots = keepHotbarSlots == null ? Collections.emptySet() : keepHotbarSlots;
        for (Object container : collectItemContainers(inventory)) {
            Method getStack = findGetStackMethod(container);
            Method setStack = findSetStackMethod(container);
            if (getStack == null || setStack == null) continue;
            boolean isHotbar = (container == hotbarContainer);
            int size = getContainerSize(container);
            if (size <= 0) size = HOTBAR_SIZE;

            for (short slot = 0; slot < size; slot++) {
                if (isHotbar && keepSlots.contains(slot)) continue;
                if (shouldRemoveDruidItem(container, getStack, slot)) {
                    try {
                        invokeSetStack(container, setStack, slot, null);
                    } catch (Exception ignored) { }
                }
            }

            for (short slot = 1; slot <= size; slot++) {
                if (isHotbar && keepSlots.contains(slot)) continue;
                if (shouldRemoveDruidItem(container, getStack, slot)) {
                    try {
                        invokeSetStack(container, setStack, slot, null);
                    } catch (Exception ignored) { }
                }
            }
        }
    }

    private boolean shouldRemoveDruidItem(Object container, Method getStack, short slot) {
        try {
            Object itemStack = invokeGetStack(container, getStack, slot);
            if (itemStack == null) return false;
            Method getItemId = itemStack.getClass().getMethod("getItemId");
            Object itemIdObj = getItemId.invoke(itemStack);
            if (!(itemIdObj instanceof String)) return false;
            String lowerId = ((String) itemIdObj).toLowerCase(Locale.ROOT);
            return isDruidAbilityItemId(lowerId);
        } catch (Exception ignored) {
            return false;
        }
    }

    private Short findDruidItemSlot(Object hotbarContainer, Method getStack) {
        try {
            for (short slot = 0; slot < HOTBAR_SIZE; slot++) {
                Object itemStack = invokeGetStack(hotbarContainer, getStack, slot);
                if (isDruidAbilityStack(itemStack)) return slot;
            }
            for (short slot = 1; slot <= HOTBAR_SIZE; slot++) {
                Object itemStack = invokeGetStack(hotbarContainer, getStack, slot);
                if (isDruidAbilityStack(itemStack)) return slot;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private short getActiveHotbarSlotFallback(Object inventory) {
        try {
            Method getActiveSlot = inventory.getClass().getMethod("getActiveHotbarSlot");
            return (short) ((byte) getActiveSlot.invoke(inventory));
        } catch (Exception ignored) {
            return HOTBAR_SLOT_ONE;
        }
    }

    private boolean trySetItem(Object hotbarContainer, Method setItem, short slot, Object itemStack) {
        try {
            invokeSetStack(hotbarContainer, setItem, slot, itemStack);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean trySetItemVerified(Object hotbarContainer, Method setItem, Method getStack, short slot, Object itemStack, String expectedItemId) {
        try {
            invokeSetStack(hotbarContainer, setItem, slot, itemStack);
            Object readBack = invokeGetStack(hotbarContainer, getStack, slot);
            if (readBack == null) return false;
            Method getItemId = readBack.getClass().getMethod("getItemId");
            Object itemIdObj = getItemId.invoke(readBack);
            if (!(itemIdObj instanceof String)) return false;
            String lowerId = ((String) itemIdObj).toLowerCase(Locale.ROOT);
            return lowerId.contains(expectedItemId.toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
            return false;
        }
    }

    private Method findGetStackMethod(Object hotbarContainer) {
        Method fallback = null;
        for (Method m : hotbarContainer.getClass().getMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (m.getName().equals("getItemStackForSlot")) return m;
            if (fallback == null && m.getName().toLowerCase(Locale.ROOT).contains("getitemstack")) {
                fallback = m;
            }
        }
        return fallback;
    }

    private Method findSetStackMethod(Object hotbarContainer) {
        Method fallback = null;
        for (Method m : hotbarContainer.getClass().getMethods()) {
            if (m.getParameterCount() != 2) continue;
            if (m.getName().equals("setItemStackForSlot")) return m;
            if (fallback == null && m.getName().toLowerCase(Locale.ROOT).contains("setitemstack")) {
                fallback = m;
            }
        }
        return fallback;
    }

    private Object invokeGetStack(Object hotbarContainer, Method getStack, short slot) throws Exception {
        Class<?> paramType = getStack.getParameterTypes()[0];
        Object arg = coerceSlot(slot, paramType);
        return getStack.invoke(hotbarContainer, arg);
    }

    private void invokeSetStack(Object hotbarContainer, Method setStack, short slot, Object itemStack) throws Exception {
        Class<?> slotType = setStack.getParameterTypes()[0];
        Object slotArg = coerceSlot(slot, slotType);
        setStack.invoke(hotbarContainer, slotArg, itemStack);
    }

    private Object coerceSlot(short slot, Class<?> paramType) {
        if (paramType == short.class || paramType == Short.class) return slot;
        if (paramType == int.class || paramType == Integer.class) return (int) slot;
        if (paramType == byte.class || paramType == Byte.class) return (byte) slot;
        return slot;
    }

    private void sendPlayerMessage(Player player, String text) {
        try {
            Object playerRef = getPlayerRef(player);
            Class<?> formattedMsgClass = Class.forName("com.hypixel.hytale.protocol.FormattedMessage");
            Object component = formattedMsgClass.getConstructor().newInstance();
            formattedMsgClass.getField("rawText").set(component, text);

            Class<?> msgClass = Class.forName("com.hypixel.hytale.server.core.Message");
            Object message = msgClass.getConstructor(formattedMsgClass).newInstance(component);

            Method sendMessage = playerRef.getClass().getMethod("sendMessage", msgClass);
            sendMessage.invoke(playerRef, message);
        } catch (Exception e) { }
    }

    private void refreshPlayerSkin(Player player) {
        try {
            Object store = getEntityStore(player);
            Object ref = getInternalRef(player);

            Class<?> skinCompClass = Class.forName("com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent");
            Method getCompType = skinCompClass.getMethod("getComponentType");
            Object compType = getCompType.invoke(null);

            Method getComponent = store.getClass().getMethod("getComponent", Class.forName("com.hypixel.hytale.component.Ref"), Class.forName("com.hypixel.hytale.component.ComponentType"));
            Object skinComponent = getComponent.invoke(store, ref, compType);

            if (skinComponent != null) {
                Method setOutdated = skinCompClass.getMethod("setNetworkOutdated");
                setOutdated.invoke(skinComponent);
            }
        } catch (Exception e) { }
    }

    private void startFormMaintenance(Player player, String shortName) {
        if (player == null) return;
        final String normalizedForm = canonicalizeFormKey(shortName.toLowerCase(Locale.ROOT));
        if (TieredForm.fromFormKey(normalizedForm) == null) return;

        final String playerName = DruidPlayerCompat.getPlayerName(player);

        scheduler.schedule(() -> {
            if (!maintenanceActive.getOrDefault(playerName, false)) return;
            String activeForm = formKeyFromModel(activeForms.get(playerName));
            if (!normalizedForm.equals(activeForm)) return;
            if (player.getWorld() == null) {
                maintenanceActive.put(playerName, false);
                return;
            }

            try {
                player.getWorld().execute(() -> {
                    try {
                        String currentActiveForm = formKeyFromModel(activeForms.get(playerName));
                        if (!normalizedForm.equals(currentActiveForm)) return;
                        int requestedTier = detectActiveFormTier(player, normalizedForm);
                        animalArmorService.refreshActiveFormArmor(player, normalizedForm, clampTier(requestedTier));
                        if (normalizedForm.equals("shark")) {
                            modifyStat(player, "Oxygen", true, 10.0f);
                        }
                        if (maintenanceActive.getOrDefault(playerName, false)) {
                            startFormMaintenance(player, normalizedForm);
                        }
                    } catch (Exception e) { }
                });
            } catch (Exception e) { e.printStackTrace(); }
        }, 2000, TimeUnit.MILLISECONDS);
    }

    private void startDuckMobilityMaintenance(Player player) {
        if (player == null) return;
        final String playerName = DruidPlayerCompat.getPlayerName(player);

        scheduler.schedule(() -> {
            String activeForm = formKeyFromModel(activeForms.get(playerName));
            if (!"duck".equals(activeForm)) return;
            if (player.getWorld() == null) {
                return;
            }

            try {
                player.getWorld().execute(() -> {
                    try {
                        String currentActiveForm = formKeyFromModel(activeForms.get(playerName));
                        if (!"duck".equals(currentActiveForm)) return;
                        updateCapabilities(player, "duck");
                        if ("duck".equals(formKeyFromModel(activeForms.get(playerName)))) {
                            startDuckMobilityMaintenance(player);
                        }
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
        }, 700, TimeUnit.MILLISECONDS);
    }

    private void startAquaticOxygenMaintenance(Player player) {
        if (player == null) return;
        final String playerName = DruidPlayerCompat.getPlayerName(player);

        scheduler.schedule(() -> {
            String activeForm = formKeyFromModel(activeForms.get(playerName));
            if (!isAquaticFormKey(activeForm)) return;
            if (player.getWorld() == null) {
                return;
            }

            try {
                player.getWorld().execute(() -> {
                    try {
                        String currentActiveForm = formKeyFromModel(activeForms.get(playerName));
                        if (!isAquaticFormKey(currentActiveForm)) return;
                        modifyStat(player, "Oxygen", true, 10.0f);
                        if (isAquaticFormKey(formKeyFromModel(activeForms.get(playerName)))) {
                            startAquaticOxygenMaintenance(player);
                        }
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
        }, 2000, TimeUnit.MILLISECONDS);
    }

    private void updateCapabilities(Player player, String shortName) {
        try {
            Object movementManager = getMovementManager(player);
            if (movementManager == null) return;

            String normalizedAnimalKey = shortName.toLowerCase(Locale.ROOT);
            // Read-only bridge seam for future form-driven ability parity checks.
            FormId resolvedFormId = FormRuntimeBridge.resolveFormIdForAnimal(normalizedAnimalKey);
            FormAbilityProfile resolvedFormAbilityProfile = FormAbilityResolver.get(resolvedFormId);

            Method getSettings = movementManager.getClass().getMethod("getSettings");
            Object settings = getSettings.invoke(movementManager);

            Method getDefaultSettings = movementManager.getClass().getMethod("getDefaultSettings");
            Object defaultSettings = getDefaultSettings.invoke(movementManager);

            float baseSpeed = 5.5f;
            float jumpForce = 11.8f;
            float flySpeed = 10.32f;
            float dragCoefficient = 0.5f;
            float fallMomentumLoss = 0.1f;
            boolean canFly = false;
            boolean duckUnderwater = false;
            float aquaticSpeedMultiplier = 1.35f;
            if (resolvedFormId == FormId.FORM_AQUATIC && resolvedFormAbilityProfile != null) {
                float formOwnedMultiplier = resolvedFormAbilityProfile.moveSpeedMultiplier();
                if (formOwnedMultiplier > 0.0f) {
                    aquaticSpeedMultiplier = formOwnedMultiplier;
                }
            }

            switch (normalizedAnimalKey) {
                case "antelope":
                    float travelSpeedMultiplier = 1.75f;
                    if (resolvedFormId == FormId.FORM_TRAVEL && resolvedFormAbilityProfile != null) {
                        float formOwnedMultiplier = resolvedFormAbilityProfile.moveSpeedMultiplier();
                        if (formOwnedMultiplier > 0.0f) {
                            travelSpeedMultiplier = formOwnedMultiplier;
                        }
                    }
                    baseSpeed = 5.5f * travelSpeedMultiplier;
                    break;
                case "hawk":
                    baseSpeed = 5.5f * 1.0f;
                    flySpeed = 10.32f * 1.25f;
                    canFly = true;
                    break;
                case "duck":
                    baseSpeed = 5.5f * 0.8f;
                    flySpeed = 10.32f * 1.0f;
                    canFly = true;
                    if (isPlayerInWater(player)) {
                        duckUnderwater = true;
                        baseSpeed = DUCK_UNDERWATER_BASE_SPEED;
                        dragCoefficient = DUCK_UNDERWATER_DRAG;
                        canFly = false;
                    }
                    break;
                case "ram":
                    baseSpeed = 5.5f * 1.25f;
                    break;
                case "tiger":
                    baseSpeed = 5.5f * 1.5f;
                    jumpForce = 14.5f;
                    break;
                case "shark":
                    baseSpeed = 12.0f;
                    dragCoefficient = 0.01f;
                    break;
                case "bluegill":
                    baseSpeed = 12.0f * aquaticSpeedMultiplier;
                    dragCoefficient = 0.006f;
                    break;
                case "rabbit":
                    baseSpeed = 8.0f;
                    jumpForce = 17.0f;
                    dragCoefficient = 14.0f;
                    fallMomentumLoss = 1.0f;
                    break;
                case "bear":
                    baseSpeed = 6.0f;
                    break;
            }

            if (resolvedFormId == FormId.FORM_AQUATIC && !"bluegill".equals(normalizedAnimalKey)) {
                baseSpeed = 12.0f * aquaticSpeedMultiplier;
                dragCoefficient = 0.006f;
            }

            float tierMultiplier = getTierMultiplier(player, shortName);
            if (tierMultiplier != 1.0f) {
                baseSpeed *= tierMultiplier;
            }

            Object[] targets = {settings, defaultSettings};

            for (Object target : targets) {
                if (target == null) continue;
                if (!canFly) {
                    setField(target, "canFly", false);
                    setField(target, "verticalFlySpeed", -1.0f);
                } else {
                    setField(target, "canFly", true);
                    setField(target, "verticalFlySpeed", flySpeed);
                }
                setField(target, "baseSpeed", baseSpeed);
                setField(target, "jumpForce", jumpForce);
                setField(target, "horizontalFlySpeed", flySpeed);
                setField(target, "dragCoefficient", dragCoefficient);
                setField(target, "fallMomentumLoss", fallMomentumLoss);
                if ("duck".equals(normalizedAnimalKey)) {
                    if (duckUnderwater) {
                        setField(target, "sprintMultiplier", DUCK_UNDERWATER_SPRINT_MULTIPLIER);
                        setField(target, "sprintSpeedMultiplier", DUCK_UNDERWATER_SPRINT_MULTIPLIER);
                        setField(target, "sprintingSpeedMultiplier", DUCK_UNDERWATER_SPRINT_MULTIPLIER);
                    } else {
                        restoreFloatFieldFromDefault(target, defaultSettings, "sprintMultiplier");
                        restoreFloatFieldFromDefault(target, defaultSettings, "sprintSpeedMultiplier");
                        restoreFloatFieldFromDefault(target, defaultSettings, "sprintingSpeedMultiplier");
                    }
                }
            }

            if (!canFly) {
                forceStopFlying(player);
            }

            Object playerRef = getPlayerRef(player);
            Method getPacketHandler = playerRef.getClass().getMethod("getPacketHandler");
            Object packetHandler = getPacketHandler.invoke(playerRef);
            Method updateMethod = movementManager.getClass().getMethod("update", Class.forName("com.hypixel.hytale.server.core.io.PacketHandler"));
            updateMethod.invoke(movementManager, packetHandler);

        } catch (Exception e) {}
    }

    private void forceStopFlying(Player player) {
        try {
            Object movementStatesComponent = getMovementStatesComponent(player);
            if (movementStatesComponent != null) {
                Class<?> movementStatesClass = Class.forName("com.hypixel.hytale.protocol.MovementStates");

                Method getMovementStates = movementStatesComponent.getClass().getMethod("getMovementStates");
                Object movementStates = getMovementStates.invoke(movementStatesComponent);

                Object newMovementStates = (movementStates == null)
                        ? movementStatesClass.getConstructor().newInstance()
                        : movementStatesClass.getConstructor(movementStatesClass).newInstance(movementStates);

                movementStatesClass.getField("flying").setBoolean(newMovementStates, false);
                movementStatesClass.getField("gliding").setBoolean(newMovementStates, false);

                Method setMovementStates = movementStatesComponent.getClass().getMethod("setMovementStates", movementStatesClass);
                setMovementStates.invoke(movementStatesComponent, newMovementStates);
            }
        } catch (Exception ignored) {
        }

        sendFlightPacket(player, false);
    }

    private void sendFlightPacket(Player player, boolean isFlying) {
        try {
            Class<?> savedStatesClass = Class.forName("com.hypixel.hytale.protocol.SavedMovementStates");
            Constructor<?> savedStatesCtor = savedStatesClass.getConstructor(boolean.class);
            Object savedStates = savedStatesCtor.newInstance(isFlying);

            Class<?> setStatesClass = Class.forName("com.hypixel.hytale.protocol.packets.player.SetMovementStates");
            Constructor<?> setStatesCtor = setStatesClass.getConstructor(savedStatesClass);
            Object packet = setStatesCtor.newInstance(savedStates);

            sendPacket(player, packet);
            System.out.println("[Druid] Synced Flight State Packet: " + isFlying);
        } catch (Exception e) {
            System.out.println("[Druid] Packet Sync Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void playPoofEffect(Player player) {
        try {
            Object transform = getTransformComponent(player);
            Method getPos = transform.getClass().getMethod("getPosition");
            Object vec3d = getPos.invoke(transform);

            double x = (double) vec3d.getClass().getMethod("getX").invoke(vec3d);
            double y = (double) vec3d.getClass().getMethod("getY").invoke(vec3d);
            double z = (double) vec3d.getClass().getMethod("getZ").invoke(vec3d);

            Class<?> posClass = Class.forName("com.hypixel.hytale.protocol.Position");
            Object position = posClass.getConstructor(double.class, double.class, double.class)
                    .newInstance(x, y + 1.0, z);

            Class<?> particlePacketClass = Class.forName("com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem");
            Class<?> dirClass = Class.forName("com.hypixel.hytale.protocol.Direction");
            Class<?> colorClass = Class.forName("com.hypixel.hytale.protocol.Color");

            Object particlePacket = particlePacketClass.getConstructor(String.class, posClass, dirClass, float.class, colorClass)
                    .newInstance("Potion_Morph_Burst", position, null, 1.0f, null);

            sendPacket(player, particlePacket);

            Class<?> soundConfigClass = Class.forName("com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent");
            Method getAssetMap = soundConfigClass.getMethod("getAssetMap");
            Object assetMap = getAssetMap.invoke(null);
            Method getIndex = assetMap.getClass().getMethod("getIndex", Object.class);

            int soundIndex = (int) getIndex.invoke(assetMap, "SFX_Portal_Neutral_Teleport_Local");

            if (soundIndex != -1) {
                Class<?> soundPacketClass = Class.forName("com.hypixel.hytale.protocol.packets.world.PlaySoundEvent3D");
                Class<?> soundCatClass = Class.forName("com.hypixel.hytale.protocol.SoundCategory");

                Object[] enums = (Object[]) soundCatClass.getEnumConstants();
                Object targetCategory = (enums.length > 0) ? enums[0] : null;

                if (targetCategory != null) {
                    for (Object e : enums) {
                        String name = e.toString().toLowerCase(Locale.ROOT);
                        if (name.contains("sfx") || name.contains("effect") || name.contains("master")) {
                            targetCategory = e;
                            break;
                        }
                    }

                    Object soundPacket = soundPacketClass.getConstructor(int.class, soundCatClass, posClass, float.class, float.class)
                            .newInstance(soundIndex, targetCategory, position, 1.0f, 1.0f);

                    sendPacket(player, soundPacket);
                }
            }

        } catch (Exception e) {}
    }

    private void setDuckOxygenBonus(String playerName, Player player, boolean apply) {
        if (playerName == null || player == null) return;
        if (apply) {
            if (duckOxygenBonusApplied.add(playerName)) {
                modifyStat(player, "Oxygen", true, DUCK_OXYGEN_BONUS);
            }
            return;
        }
        if (duckOxygenBonusApplied.remove(playerName)) {
            modifyStat(player, "Oxygen", false, DUCK_OXYGEN_BONUS);
        }
    }

    private void setAquaticOxygenBonus(String playerName, Player player, boolean apply) {
        if (playerName == null || player == null) return;
        if (apply) {
            if (aquaticOxygenBonusApplied.add(playerName)) {
                modifyStat(player, "Oxygen", true, AQUATIC_OXYGEN_BONUS);
            }
            return;
        }
        if (aquaticOxygenBonusApplied.remove(playerName)) {
            modifyStat(player, "Oxygen", false, AQUATIC_OXYGEN_BONUS);
        }
    }

    private boolean isAquaticFormKey(String animalKey) {
        if (animalKey == null || animalKey.isBlank()) return false;
        return FormRuntimeBridge.resolveFormIdForAnimal(animalKey) == FormId.FORM_AQUATIC;
    }

    private boolean isPlayerInWater(Player player) {
        if (player == null) return false;
        try {
            Object movementStatesComponent = getMovementStatesComponent(player);
            if (movementStatesComponent == null) return false;

            Method getMovementStates = movementStatesComponent.getClass().getMethod("getMovementStates");
            Object movementStates = getMovementStates.invoke(movementStatesComponent);
            if (movementStates == null) return false;

            return getBooleanMovementState(movementStates, "swimming")
                    || getBooleanMovementState(movementStates, "underwater")
                    || getBooleanMovementState(movementStates, "inWater");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean getBooleanMovementState(Object stateObject, String fieldOrMethod) {
        if (stateObject == null || fieldOrMethod == null) return false;
        try {
            Field field = stateObject.getClass().getField(fieldOrMethod);
            return field.getBoolean(stateObject);
        } catch (Exception ignored) {
        }
        try {
            Method method = stateObject.getClass().getMethod("is" + Character.toUpperCase(fieldOrMethod.charAt(0)) + fieldOrMethod.substring(1));
            Object value = method.invoke(stateObject);
            return value instanceof Boolean && (Boolean) value;
        } catch (Exception ignored) {
        }
        try {
            Method method = stateObject.getClass().getMethod("get" + Character.toUpperCase(fieldOrMethod.charAt(0)) + fieldOrMethod.substring(1));
            Object value = method.invoke(stateObject);
            return value instanceof Boolean && (Boolean) value;
        } catch (Exception ignored) {
        }
        return false;
    }

    private void restoreFloatFieldFromDefault(Object target, Object defaultSource, String fieldName) {
        if (target == null || defaultSource == null || fieldName == null) return;
        Float defaultValue = readFloatField(defaultSource, fieldName);
        if (defaultValue != null) {
            setField(target, fieldName, defaultValue);
        }
    }

    private Float readFloatField(Object source, String fieldName) {
        if (source == null || fieldName == null) return null;
        try {
            Field field = source.getClass().getField(fieldName);
            Object value = field.get(source);
            if (value instanceof Number) return ((Number) value).floatValue();
        } catch (Exception ignored) {
        }
        try {
            Class<?> clazz = source.getClass();
            while (clazz != null) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object value = field.get(source);
                    if (value instanceof Number) return ((Number) value).floatValue();
                    break;
                } catch (NoSuchFieldException ex) {
                    clazz = clazz.getSuperclass();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void sendPacket(Player player, Object packet) {
        try {
            Object playerRef = getPlayerRef(player);
            Method getPacketHandler = playerRef.getClass().getMethod("getPacketHandler");
            Object handler = getPacketHandler.invoke(playerRef);
            Method write = handler.getClass().getMethod("writeNoCache", Class.forName("com.hypixel.hytale.protocol.ToClientPacket"));
            write.invoke(handler, packet);
        } catch (Exception e) {}
    }

    private void modifyStat(Player player, String statName, boolean increase, float amount) {
        try {
            Object statMap = getStatMap(player);
            int index = getStatIndex(statName);
            Method mod = statMap.getClass().getMethod("modifyStatValue", int.class, int.class);
            int val = increase ? (int)amount : -(int)amount;
            mod.invoke(statMap, index, val);
        } catch (Exception e) { maximizeStat(player, statName); }
    }

    private void maximizeStat(Player player, String statName) {
        try {
            Object statMap = getStatMap(player);
            int index = getStatIndex(statName);
            Method max = statMap.getClass().getMethod("maximizeStatValue", int.class);
            max.invoke(statMap, index);
        } catch (Exception e) {}
    }

    private void toggleHumanoidFlag(Player player, boolean enable) {
        try {
            Object store = getEntityStore(player);
            Object ref = getInternalRef(player);

            Class<?> tagCompClass = Class.forName("com.hypixel.hytale.server.core.modules.entity.component.TagComponent");
            Method getCompType = tagCompClass.getMethod("getComponentType");
            Object compType = getCompType.invoke(null);

            Method getComponent = store.getClass().getMethod("getComponent", Class.forName("com.hypixel.hytale.component.Ref"), Class.forName("com.hypixel.hytale.component.ComponentType"));
            Object tagComponent = getComponent.invoke(store, ref, compType);

            if (tagComponent != null) {
                Method getTags = tagCompClass.getMethod("getTags");
                Set<String> tags = (Set<String>) getTags.invoke(tagComponent);

                if (enable) {
                    tags.add("Humanoid");
                } else {
                    tags.remove("Humanoid");
                }

                Method setOutdated = tagCompClass.getMethod("setNetworkOutdated");
                setOutdated.invoke(tagComponent);
            }
        } catch (Exception e) { }
    }

    private Object getTransformComponent(Player player) throws Exception {
        Object store = getEntityStore(player);
        Object ref = getInternalRef(player);
        Class<?> compClass = Class.forName("com.hypixel.hytale.server.core.modules.entity.component.TransformComponent");
        Method getCompType = compClass.getMethod("getComponentType");
        Object compType = getCompType.invoke(null);
        Method getComponent = store.getClass().getMethod("getComponent", Class.forName("com.hypixel.hytale.component.Ref"), Class.forName("com.hypixel.hytale.component.ComponentType"));
        return getComponent.invoke(store, ref, compType);
    }

    private Object getStatMap(Player player) throws Exception {
        Object store = getEntityStore(player);
        Object ref = getInternalRef(player);
        Class<?> statMapClass = Class.forName("com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap");
        Method getCompType = statMapClass.getMethod("getComponentType");
        Object compType = getCompType.invoke(null);
        Method getComponent = store.getClass().getMethod("getComponent", Class.forName("com.hypixel.hytale.component.Ref"), Class.forName("com.hypixel.hytale.component.ComponentType"));
        return getComponent.invoke(store, ref, compType);
    }

    private int getStatIndex(String statName) throws Exception {
        Class<?> statTypeClass = Class.forName("com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType");
        Method getAssetMap = statTypeClass.getMethod("getAssetMap");
        Object assetMap = getAssetMap.invoke(null);
        Method getIndex = assetMap.getClass().getMethod("getIndex", Object.class);
        return (int) getIndex.invoke(assetMap, statName);
    }

    private Object getMovementManager(Player player) throws Exception {
        Object store = getEntityStore(player);
        Object ref = getInternalRef(player);
        Class<?> managerClass = Class.forName("com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager");
        Method getCompType = managerClass.getMethod("getComponentType");
        Object compType = getCompType.invoke(null);
        Method getComponent = store.getClass().getMethod("getComponent", Class.forName("com.hypixel.hytale.component.Ref"), Class.forName("com.hypixel.hytale.component.ComponentType"));
        return getComponent.invoke(store, ref, compType);
    }

    private Object getMovementStatesComponent(Player player) throws Exception {
        Object store = getEntityStore(player);
        Object ref = getInternalRef(player);
        Class<?> compClass = Class.forName("com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent");
        Method getCompType = compClass.getMethod("getComponentType");
        Object compType = getCompType.invoke(null);
        Method getComponent = store.getClass().getMethod("getComponent", Class.forName("com.hypixel.hytale.component.Ref"), Class.forName("com.hypixel.hytale.component.ComponentType"));
        return getComponent.invoke(store, ref, compType);
    }

    private Object getEntityStore(Player player) throws Exception {
        Object ref = getInternalRef(player);
        Method getStore = ref.getClass().getMethod("getStore");
        return getStore.invoke(ref);
    }

    private Object getInternalRef(Player player) throws Exception {
        Object playerRef = getPlayerRef(player);
        Method getRef = playerRef.getClass().getMethod("getReference");
        return getRef.invoke(playerRef);
    }

    private Object getPlayerRef(Object player) {
        try {
            Field f = getFieldDeep(player.getClass(), "playerRef");
            if (f != null) { f.setAccessible(true); return f.get(player); }
        } catch (Exception e) { }
        return null;
    }

    private boolean swapModel(Object player, String assetId) {
        try {
            Class<?> modelAssetClass = Class.forName("com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset");
            Class<?> modelClass = Class.forName("com.hypixel.hytale.server.core.asset.type.model.config.Model");
            Method getMapMethod = modelAssetClass.getMethod("getAssetMap");
            Object assetMap = getMapMethod.invoke(null);

            Method getAssetMethod = null;
            for(Method m : assetMap.getClass().getMethods()) {
                if(m.getName().equals("getAsset") && m.getParameterCount() == 1) {
                    getAssetMethod = m; break;
                }
            }
            getAssetMethod.setAccessible(true);

            Object rawAsset = getAssetMethod.invoke(assetMap, assetId);
            if (rawAsset == null) rawAsset = getAssetMethod.invoke(assetMap, "Hytale:" + assetId);
            if (rawAsset == null) rawAsset = getAssetMethod.invoke(assetMap, "druid:" + assetId);
            if (rawAsset == null) rawAsset = getAssetMethod.invoke(assetMap, "Druid:" + assetId);
            if (rawAsset == null) return false;

            Method createUnitScale = modelClass.getMethod("createUnitScaleModel", modelAssetClass);
            Object newModel = createUnitScale.invoke(null, rawAsset);

            return injectRawModel(player, newModel);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean injectRawModel(Object player, Object modelObject) {
        try {
            Object component = getModelComponent(player);
            Class<?> modelCompClass = Class.forName("com.hypixel.hytale.server.core.modules.entity.component.ModelComponent");

            Field modelField = modelCompClass.getDeclaredField("model");
            modelField.setAccessible(true);
            modelField.set(component, modelObject);

            Field outdatedField = modelCompClass.getDeclaredField("isNetworkOutdated");
            outdatedField.setAccessible(true);
            outdatedField.set(component, true);

            return true;
        } catch (Exception e) { return false; }
    }

    private Object getModelComponent(Object player) throws Exception {
        Object store = getEntityStore((Player)player);
        Object ref = getInternalRef((Player)player);
        Class<?> modelCompClass = Class.forName("com.hypixel.hytale.server.core.modules.entity.component.ModelComponent");
        Method getTypeMethod = modelCompClass.getMethod("getComponentType");
        Object compType = getTypeMethod.invoke(null);
        Method getCompMethod = store.getClass().getMethod("getComponent", Class.forName("com.hypixel.hytale.component.Ref"), Class.forName("com.hypixel.hytale.component.ComponentType"));
        return getCompMethod.invoke(store, ref, compType);
    }

    private void setField(Object object, String fieldName, Object value) {
        try {
            Field f = object.getClass().getField(fieldName);
            f.set(object, value);
        } catch (Exception e) {
            try {
                Class<?> clazz = object.getClass();
                while (clazz != null) {
                    try {
                        Field f = clazz.getDeclaredField(fieldName);
                        f.setAccessible(true);
                        f.set(object, value);
                        return;
                    } catch (NoSuchFieldException ex) { clazz = clazz.getSuperclass(); }
                }
            } catch (Exception ignored) { }
        }
    }

    private DruidFormProgress getProgress(Player player) {
        ensureFormProgressLoaded();
        return PLAYER_PROGRESS.computeIfAbsent(getPlayerUuid(player), ignored -> new DruidFormProgress());
    }

    private UUID getPlayerUuid(Player player) {
        try {
            Method getUuid = player.getClass().getMethod("getUuid");
            Object value = getUuid.invoke(player);
            if (value instanceof UUID) return (UUID) value;
        } catch (Exception ignored) {
        }
        try {
            Method getId = player.getClass().getMethod("getId");
            Object value = getId.invoke(player);
            if (value instanceof UUID) return (UUID) value;
        } catch (Exception ignored) {
        }
        String playerName = DruidPlayerCompat.getPlayerName(player);
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        return UUID.nameUUIDFromBytes(playerName.getBytes());
    }

    private float getTierMultiplier(Player player, String shortName) {
        TieredForm tieredForm = TieredForm.fromFormKey(canonicalizeFormKey(shortName.toLowerCase(Locale.ROOT)));
        if (tieredForm == null) return 1.0f;
        int tier = getProgress(player).getTier(tieredForm);
        switch (tier) {
            case 2: return 1.15f;
            case 3: return 1.3f;
            case 4: return 1.5f;
            default: return 1.0f;
        }
    }

    private void syncTierProgressFromInventory(Player player) {
        try {
            Method getInventory = player.getClass().getMethod("getInventory");
            Object inventory = getInventory.invoke(player);
            if (inventory == null) return;

            DruidFormProgress progress = getProgress(player);
            boolean changed = false;

            for (Object container : collectItemContainers(inventory)) {
                changed |= syncTierFromContainer(container, progress);
            }
            if (changed) {
                saveFormProgress();
            }
        } catch (Exception ignored) {
        }
    }

    private boolean syncTierFromContainer(Object container, DruidFormProgress progress) throws Exception {
        Method getStack = findGetStackMethod(container);
        int size = getContainerSize(container);
        boolean changed = false;

        if (getStack != null && size > 0) {
            for (short slot = 0; slot < size; slot++) {
                changed |= updateTierFromItemStack(invokeGetStack(container, getStack, slot), progress);
            }
            for (short slot = 1; slot <= size; slot++) {
                changed |= updateTierFromItemStack(invokeGetStack(container, getStack, slot), progress);
            }
            return changed;
        }

        Method getAll = findGetAllStacksMethod(container);
        if (getAll != null) {
            Object result = getAll.invoke(container);
            if (result instanceof Object[]) {
                for (Object itemStack : (Object[]) result) {
                    changed |= updateTierFromItemStack(itemStack, progress);
                }
            } else if (result instanceof Iterable) {
                for (Object itemStack : (Iterable<?>) result) {
                    changed |= updateTierFromItemStack(itemStack, progress);
                }
            }
            return changed;
        }

        if (getStack != null) {
            for (short slot = 0; slot < HOTBAR_SIZE; slot++) {
                changed |= updateTierFromItemStack(invokeGetStack(container, getStack, slot), progress);
            }
            for (short slot = 1; slot <= HOTBAR_SIZE; slot++) {
                changed |= updateTierFromItemStack(invokeGetStack(container, getStack, slot), progress);
            }
        }
        return changed;
    }

    private boolean updateTierFromItemStack(Object itemStack, DruidFormProgress progress) throws Exception {
        if (itemStack == null) return false;
        Method getItemId = itemStack.getClass().getMethod("getItemId");
        Object rawItemId = getItemId.invoke(itemStack);
        if (!(rawItemId instanceof String)) return false;

        String itemId = ((String) rawItemId).toLowerCase(Locale.ROOT);
        for (TieredForm tieredForm : TieredForm.values()) {
            for (int tier = 4; tier >= 1; tier--) {
                if (itemId.contains(tieredForm.itemForTier(tier).toLowerCase(Locale.ROOT))) {
                    return progress.getTier(tieredForm) < tier && progress.setTier(tieredForm, tier);
                }
            }
        }
        return false;
    }

    private List<Object> collectItemContainers(Object inventory) {
        List<Object> containers = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        try {
            Method getHotbar = inventory.getClass().getMethod("getHotbar");
            Object hotbar = getHotbar.invoke(inventory);
            if (hotbar != null && seen.add(hotbar)) containers.add(hotbar);
        } catch (Exception ignored) {
        }

        for (Method m : inventory.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            Class<?> rt = m.getReturnType();
            if (rt == null) continue;
            String typeName = rt.getName().toLowerCase(Locale.ROOT);
            if (!(typeName.contains("item") && typeName.contains("container"))) continue;
            try {
                Object container = m.invoke(inventory);
                if (container != null && seen.add(container)) {
                    containers.add(container);
                }
            } catch (Exception ignored) {
            }
        }

        return containers;
    }

    private int getContainerSize(Object container) {
        try {
            for (Method m : container.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                Class<?> rt = m.getReturnType();
                if (!(rt == int.class || rt == Integer.class || rt == short.class || rt == Short.class || rt == byte.class || rt == Byte.class)) continue;
                String name = m.getName().toLowerCase(Locale.ROOT);
                if (!(name.contains("size") || name.contains("count") || name.contains("slot"))) continue;
                Object val = m.invoke(container);
                if (val instanceof Number) return ((Number) val).intValue();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private Method findGetAllStacksMethod(Object container) {
        for (Method m : container.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            String name = m.getName().toLowerCase(Locale.ROOT);
            if (name.contains("getitemstacks") || name.contains("getitems") || name.contains("getcontents")) {
                return m;
            }
        }
        return null;
    }

    private Field getFieldDeep(Class<?> clazz, String name) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (Exception e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
}
