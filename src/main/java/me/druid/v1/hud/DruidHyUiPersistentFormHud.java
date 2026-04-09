package me.druid.v1.hud;

import au.ellie.hyui.builders.Alignment;
import au.ellie.hyui.builders.GroupBuilder;
import au.ellie.hyui.builders.HudBuilder;
import au.ellie.hyui.builders.HyUIAnchor;
import au.ellie.hyui.builders.HyUIHud;
import au.ellie.hyui.builders.HyUIPatchStyle;
import au.ellie.hyui.builders.HyUIStyle;
import au.ellie.hyui.builders.ImageBuilder;
import au.ellie.hyui.builders.LabelBuilder;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import me.druid.v1.ShapeshiftHandler;
import me.druid.v1.forms.FormRuntimeBridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DruidHyUiPersistentFormHud {
    private static final ConcurrentHashMap<UUID, HyUIHud> HUD_BY_PLAYER = new ConcurrentHashMap<>();
    private static final String BEAR_FORM = "Bear";
    private static final String TIGER_FORM = "Tiger";
    private static final String HAWK_FORM = "Hawk";
    private static final String SHARK_FORM = "Shark";
    private static final String RAM_FORM = "Ram";
    private static final String AQUATIC_FORM = "Aquatic";
    private static final String RABBIT_FORM = "Rabbit";
    private static final String ANTELOPE_FORM = "Antelope";
    private static final String BEAR_ICON_TEXTURE_PATH = "forms/bear.png";
    private static final String BEAR_ICON_CLASSPATH_PATH = "Common/UI/Custom/forms/bear.png";
    private static final String TIGER_ICON_TEXTURE_PATH = "forms/tiger.png";
    private static final String TIGER_ICON_CLASSPATH_PATH = "Common/UI/Custom/forms/tiger.png";
    private static final String HAWK_ICON_TEXTURE_PATH = "forms/hawk.png";
    private static final String HAWK_ICON_CLASSPATH_PATH = "Common/UI/Custom/forms/hawk.png";
    private static final String SHARK_ICON_TEXTURE_PATH = "forms/shark.png";
    private static final String SHARK_ICON_CLASSPATH_PATH = "Common/UI/Custom/forms/shark.png";
    private static final String RAM_ICON_TEXTURE_PATH = "forms/ram.png";
    private static final String RAM_ICON_CLASSPATH_PATH = "Common/UI/Custom/forms/ram.png";
    private static final String AQUATIC_ICON_TEXTURE_PATH = "forms/aquatic.png";
    private static final String AQUATIC_ICON_CLASSPATH_PATH = "Common/UI/Custom/forms/aquatic.png";
    private static final String RABBIT_ICON_TEXTURE_PATH = "forms/rabbit.png";
    private static final String RABBIT_ICON_CLASSPATH_PATH = "Common/UI/Custom/forms/rabbit.png";
    private static final String ANTELOPE_ICON_TEXTURE_PATH = "forms/antelope.png";
    private static final String ANTELOPE_ICON_CLASSPATH_PATH = "Common/UI/Custom/forms/antelope.png";
    private static final List<String> FORM_ORDER = List.of(
            "Bear",
            "Tiger",
            "Shark",
            "Ram",
            "Aquatic",
            "Hawk",
            "Rabbit",
            "Antelope"
    );

    private DruidHyUiPersistentFormHud() {
    }

    public static void attachOrRefresh(Player player) {
        if (player == null) return;

        PlayerRef playerRef = resolvePlayerRef(player);
        if (playerRef == null) return;

        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) return;

        String activeForm = resolveActiveFormName(player);
        HudBuilder builder = createHudBuilder(playerRef, activeForm);

        try {
            HyUIHud existingHud = HUD_BY_PLAYER.get(playerUuid);
            if (existingHud == null) {
                HyUIHud hud = builder.show();
                HUD_BY_PLAYER.put(playerUuid, hud);
                System.out.println("[DruidHyUI] Persistent form HUD shown for " + playerUuid + " -> " + activeForm);
            } else {
                builder.updateExisting(existingHud);
            }
        } catch (Exception e) {
            System.out.println("[DruidHyUI] Persistent form HUD update failed: " + e.getMessage());
        }
    }

    public static void detach(UUID playerUuid) {
        if (playerUuid == null) return;
        HyUIHud hud = HUD_BY_PLAYER.remove(playerUuid);
        if (hud == null) return;
        try {
            hud.remove();
            System.out.println("[DruidHyUI] Persistent form HUD removed for " + playerUuid);
        } catch (Exception e) {
            System.out.println("[DruidHyUI] Persistent form HUD remove failed: " + e.getMessage());
        }
    }

    private static HudBuilder createHudBuilder(PlayerRef playerRef, String activeForm) {
        HyUIAnchor rootAnchor = new HyUIAnchor()
                .setLeft(0)
                .setRight(0)
                .setBottom(154)
                .setHeight(30);

        HyUIAnchor rowAnchor = new HyUIAnchor()
                .setTop(0)
                .setWidth(980)
                .setHeight(30);

        HyUIStyle labelStyle = new HyUIStyle()
                .setFontSize(14f)
                .setRenderBold(true)
                .setTextColor("#FFFFFFFF")
                .setHorizontalAlignment(Alignment.Center);

        GroupBuilder row = GroupBuilder.group()
                .withRawId("druidPersistentFormHudRow")
                .withAnchor(rowAnchor)
                .withLayoutMode("Center")
                .addChild(createAntelopeSlot(activeForm, labelStyle))
                .addChild(
                        LabelBuilder.label()
                                .withRawId("druidPersistentFormHudSeparator1")
                                .withAnchor(new HyUIAnchor().setWidth(24).setHeight(30))
                                .withStyle(labelStyle)
                                .withText("")
                )
                .addChild(createHawkSlot(activeForm, labelStyle))
                .addChild(
                        LabelBuilder.label()
                                .withRawId("druidPersistentFormHudSeparator2")
                                .withAnchor(new HyUIAnchor().setWidth(24).setHeight(30))
                                .withStyle(labelStyle)
                                .withText("")
                )
                .addChild(createBearSlot(activeForm, labelStyle))
                .addChild(
                        LabelBuilder.label()
                                .withRawId("druidPersistentFormHudSeparator3")
                                .withAnchor(new HyUIAnchor().setWidth(24).setHeight(30))
                                .withStyle(labelStyle)
                                .withText("")
                )
                .addChild(createTigerSlot(activeForm, labelStyle))
                .addChild(
                        LabelBuilder.label()
                                .withRawId("druidPersistentFormHudSeparator4")
                                .withAnchor(new HyUIAnchor().setWidth(24).setHeight(30))
                                .withStyle(labelStyle)
                                .withText("")
                )
                .addChild(createSharkSlot(activeForm, labelStyle))
                .addChild(
                        LabelBuilder.label()
                                .withRawId("druidPersistentFormHudSeparator5")
                                .withAnchor(new HyUIAnchor().setWidth(24).setHeight(30))
                                .withStyle(labelStyle)
                                .withText("")
                )
                .addChild(createRamSlot(activeForm, labelStyle))
                .addChild(
                        LabelBuilder.label()
                                .withRawId("druidPersistentFormHudSeparator6")
                                .withAnchor(new HyUIAnchor().setWidth(24).setHeight(30))
                                .withStyle(labelStyle)
                                .withText("")
                )
                .addChild(createAquaticSlot(activeForm, labelStyle))
                .addChild(
                        LabelBuilder.label()
                                .withRawId("druidPersistentFormHudSeparator7")
                                .withAnchor(new HyUIAnchor().setWidth(24).setHeight(30))
                                .withStyle(labelStyle)
                                .withText("")
                )
                .addChild(createRabbitSlot(activeForm, labelStyle));

        GroupBuilder root = GroupBuilder.group()
                .withRawId("druidPersistentFormHudRoot")
                .withAnchor(rootAnchor)
                .withLayoutMode("Center")
                .addChild(row);

        return HudBuilder.hudForPlayer(playerRef)
                .fromFile("Pages/EllieAU_HyUI_Placeholder.ui")
                .addElement(root);
    }

    private static GroupBuilder createBearSlot(String activeForm, HyUIStyle labelStyle) {
        boolean bearHighlighted = BEAR_FORM.equalsIgnoreCase(activeForm);
        GroupBuilder bearSlot = GroupBuilder.group()
                .withRawId("druidPersistentFormHudBearSlot")
                .withAnchor(new HyUIAnchor().setWidth(56).setHeight(30))
                .withLayoutMode("Center")
                .withBackground(new HyUIPatchStyle().setColor("#00000000"))
                .withOutlineColor("#00000000")
                .withOutlineSize(0f);

        String resolvedTexturePath = resolveBearTexturePath();
        if (resolvedTexturePath != null) {
            GroupBuilder bearOverlay = GroupBuilder.group()
                    .withRawId("druidPersistentFormHudBearOverlay")
                    .withAnchor(new HyUIAnchor().setWidth(68).setHeight(68));

            if (bearHighlighted) {
                bearOverlay.addChild(createSquareHighlight("druidPersistentFormHudBearHighlight"));
            }

            bearOverlay.addChild(
                    GroupBuilder.group()
                            .withRawId("druidPersistentFormHudBearIconLayer")
                            .withAnchor(new HyUIAnchor().setWidth(68).setHeight(68))
                            .withLayoutMode("Center")
                            .addChild(
                                    ImageBuilder.image()
                                            .withRawId("druidPersistentFormHudBearIcon")
                                            .withAnchor(new HyUIAnchor().setLeft(-1).setWidth(56).setHeight(56))
                                            .withImage(resolvedTexturePath)
                            )
            );

            bearSlot.addChild(bearOverlay);
            return bearSlot;
        }

        bearSlot.addChild(
                LabelBuilder.label()
                        .withRawId("druidPersistentFormHudBearFallback")
                        .withAnchor(new HyUIAnchor().setLeft(0).setRight(0).setTop(0).setHeight(30))
                        .withStyle(labelStyle)
                        .withText(bearHighlighted ? "[BEAR]" : "Bear")
        );
        return bearSlot;
    }

    private static GroupBuilder createTigerSlot(String activeForm, HyUIStyle labelStyle) {
        boolean tigerHighlighted = TIGER_FORM.equalsIgnoreCase(activeForm);
        GroupBuilder tigerSlot = GroupBuilder.group()
                .withRawId("druidPersistentFormHudTigerSlot")
                .withAnchor(new HyUIAnchor().setWidth(56).setHeight(30))
                .withLayoutMode("Center")
                .withBackground(new HyUIPatchStyle().setColor("#00000000"))
                .withOutlineColor("#00000000")
                .withOutlineSize(0f);

        String resolvedTexturePath = resolveTigerTexturePath();
        if (resolvedTexturePath != null) {
            GroupBuilder tigerOverlay = GroupBuilder.group()
                    .withRawId("druidPersistentFormHudTigerOverlay")
                    .withAnchor(new HyUIAnchor().setWidth(68).setHeight(68));

            if (tigerHighlighted) {
                tigerOverlay.addChild(createSquareHighlight("druidPersistentFormHudTigerHighlight"));
            }

            tigerOverlay.addChild(
                    GroupBuilder.group()
                            .withRawId("druidPersistentFormHudTigerIconLayer")
                            .withAnchor(new HyUIAnchor().setWidth(68).setHeight(68))
                            .withLayoutMode("Center")
                            .addChild(
                                    ImageBuilder.image()
                                            .withRawId("druidPersistentFormHudTigerIcon")
                                            .withAnchor(new HyUIAnchor().setLeft(-1).setWidth(56).setHeight(56))
                                            .withImage(resolvedTexturePath)
                            )
            );

            tigerSlot.addChild(tigerOverlay);
            return tigerSlot;
        }

        tigerSlot.addChild(
                LabelBuilder.label()
                        .withRawId("druidPersistentFormHudTigerFallback")
                        .withAnchor(new HyUIAnchor().setLeft(0).setRight(0).setTop(0).setHeight(30))
                        .withStyle(labelStyle)
                        .withText(tigerHighlighted ? "[TIGER]" : "Tiger")
        );
        return tigerSlot;
    }

    private static GroupBuilder createHawkSlot(String activeForm, HyUIStyle labelStyle) {
        boolean hawkHighlighted = HAWK_FORM.equalsIgnoreCase(activeForm);
        GroupBuilder hawkSlot = GroupBuilder.group()
                .withRawId("druidPersistentFormHudHawkSlot")
                .withAnchor(new HyUIAnchor().setWidth(56).setHeight(30))
                .withLayoutMode("Center")
                .withBackground(new HyUIPatchStyle().setColor("#00000000"))
                .withOutlineColor("#00000000")
                .withOutlineSize(0f);

        String resolvedTexturePath = resolveHawkTexturePath();
        if (resolvedTexturePath != null) {
            GroupBuilder hawkOverlay = GroupBuilder.group()
                    .withRawId("druidPersistentFormHudHawkOverlay")
                    .withAnchor(new HyUIAnchor().setWidth(68).setHeight(68));

            if (hawkHighlighted) {
                hawkOverlay.addChild(createSquareHighlight("druidPersistentFormHudHawkHighlight"));
            }

            hawkOverlay.addChild(
                    GroupBuilder.group()
                            .withRawId("druidPersistentFormHudHawkIconLayer")
                            .withAnchor(new HyUIAnchor().setWidth(68).setHeight(68))
                            .withLayoutMode("Center")
                            .addChild(
                                    ImageBuilder.image()
                                            .withRawId("druidPersistentFormHudHawkIcon")
                                            .withAnchor(new HyUIAnchor().setLeft(-2).setWidth(56).setHeight(56))
                                            .withImage(resolvedTexturePath)
                            )
            );

            hawkSlot.addChild(hawkOverlay);
            return hawkSlot;
        }

        hawkSlot.addChild(
                LabelBuilder.label()
                        .withRawId("druidPersistentFormHudHawkFallback")
                        .withAnchor(new HyUIAnchor().setLeft(0).setRight(0).setTop(0).setHeight(30))
                        .withStyle(labelStyle)
                        .withText(hawkHighlighted ? "[HAWK]" : "Hawk")
        );
        return hawkSlot;
    }

    private static GroupBuilder createSharkSlot(String activeForm, HyUIStyle labelStyle) {
        boolean sharkHighlighted = SHARK_FORM.equalsIgnoreCase(activeForm);
        GroupBuilder sharkSlot = GroupBuilder.group()
                .withRawId("druidPersistentFormHudSharkSlot")
                .withAnchor(new HyUIAnchor().setWidth(56).setHeight(30))
                .withLayoutMode("Center")
                .withBackground(new HyUIPatchStyle().setColor("#00000000"))
                .withOutlineColor("#00000000")
                .withOutlineSize(0f);

        String resolvedTexturePath = resolveSharkTexturePath();
        if (resolvedTexturePath != null) {
            GroupBuilder sharkOverlay = GroupBuilder.group()
                    .withRawId("druidPersistentFormHudSharkOverlay")
                    .withAnchor(new HyUIAnchor().setWidth(68).setHeight(68));

            if (sharkHighlighted) {
                sharkOverlay.addChild(createSquareHighlight("druidPersistentFormHudSharkHighlight"));
            }

            sharkOverlay.addChild(
                    GroupBuilder.group()
                            .withRawId("druidPersistentFormHudSharkIconLayer")
                            .withAnchor(new HyUIAnchor().setWidth(68).setHeight(68))
                            .withLayoutMode("Center")
                            .addChild(
                                    ImageBuilder.image()
                                            .withRawId("druidPersistentFormHudSharkIcon")
                                            .withAnchor(new HyUIAnchor().setLeft(-1).setWidth(56).setHeight(56))
                                            .withImage(resolvedTexturePath)
                            )
            );

            sharkSlot.addChild(sharkOverlay);
            return sharkSlot;
        }

        sharkSlot.addChild(
                LabelBuilder.label()
                        .withRawId("druidPersistentFormHudSharkFallback")
                        .withAnchor(new HyUIAnchor().setLeft(0).setRight(0).setTop(0).setHeight(30))
                        .withStyle(labelStyle)
                        .withText(sharkHighlighted ? "[SHARK]" : "Shark")
        );
        return sharkSlot;
    }

    private static GroupBuilder createRamSlot(String activeForm, HyUIStyle labelStyle) {
        boolean ramHighlighted = RAM_FORM.equalsIgnoreCase(activeForm);
        GroupBuilder ramSlot = GroupBuilder.group()
                .withRawId("druidPersistentFormHudRamSlot")
                .withAnchor(new HyUIAnchor().setWidth(56).setHeight(30))
                .withLayoutMode("Center")
                .withBackground(new HyUIPatchStyle().setColor("#00000000"))
                .withOutlineColor("#00000000")
                .withOutlineSize(0f);

        String resolvedTexturePath = resolveRamTexturePath();
        if (resolvedTexturePath != null) {
            GroupBuilder ramOverlay = GroupBuilder.group()
                    .withRawId("druidPersistentFormHudRamOverlay")
                    .withAnchor(new HyUIAnchor().setWidth(68).setHeight(68));

            if (ramHighlighted) {
                ramOverlay.addChild(createSquareHighlight("druidPersistentFormHudRamHighlight"));
            }

            ramOverlay.addChild(
                    GroupBuilder.group()
                            .withRawId("druidPersistentFormHudRamIconLayer")
                            .withAnchor(new HyUIAnchor().setWidth(68).setHeight(68))
                            .withLayoutMode("Center")
                            .addChild(
                                    ImageBuilder.image()
                                            .withRawId("druidPersistentFormHudRamIcon")
                                            .withAnchor(new HyUIAnchor().setLeft(-2).setWidth(56).setHeight(56))
                                            .withImage(resolvedTexturePath)
                            )
            );

            ramSlot.addChild(ramOverlay);
            return ramSlot;
        }

        ramSlot.addChild(
                LabelBuilder.label()
                        .withRawId("druidPersistentFormHudRamFallback")
                        .withAnchor(new HyUIAnchor().setLeft(0).setRight(0).setTop(0).setHeight(30))
                        .withStyle(labelStyle)
                        .withText(ramHighlighted ? "[RAM]" : "Ram")
        );
        return ramSlot;
    }

    private static GroupBuilder createAquaticSlot(String activeForm, HyUIStyle labelStyle) {
        boolean aquaticHighlighted = AQUATIC_FORM.equalsIgnoreCase(activeForm);
        GroupBuilder aquaticSlot = GroupBuilder.group()
                .withRawId("druidPersistentFormHudAquaticSlot")
                .withAnchor(new HyUIAnchor().setWidth(56).setHeight(30))
                .withLayoutMode("Center")
                .withBackground(new HyUIPatchStyle().setColor("#00000000"))
                .withOutlineColor("#00000000")
                .withOutlineSize(0f);

        String resolvedTexturePath = resolveAquaticTexturePath();
        if (resolvedTexturePath != null) {
            GroupBuilder aquaticOverlay = GroupBuilder.group()
                    .withRawId("druidPersistentFormHudAquaticOverlay")
                    .withAnchor(new HyUIAnchor().setWidth(68).setHeight(68));

            if (aquaticHighlighted) {
                aquaticOverlay.addChild(createSquareHighlight("druidPersistentFormHudAquaticHighlight"));
            }

            aquaticOverlay.addChild(
                    GroupBuilder.group()
                            .withRawId("druidPersistentFormHudAquaticIconLayer")
                            .withAnchor(new HyUIAnchor().setWidth(68).setHeight(68))
                            .withLayoutMode("Center")
                            .addChild(
                                    ImageBuilder.image()
                                            .withRawId("druidPersistentFormHudAquaticIcon")
                                            .withAnchor(new HyUIAnchor().setLeft(-1).setWidth(56).setHeight(56))
                                            .withImage(resolvedTexturePath)
                            )
            );

            aquaticSlot.addChild(aquaticOverlay);
            return aquaticSlot;
        }

        aquaticSlot.addChild(
                LabelBuilder.label()
                        .withRawId("druidPersistentFormHudAquaticFallback")
                        .withAnchor(new HyUIAnchor().setLeft(0).setRight(0).setTop(0).setHeight(30))
                        .withStyle(labelStyle)
                        .withText(aquaticHighlighted ? "[AQUATIC]" : "Aquatic")
        );
        return aquaticSlot;
    }

    private static GroupBuilder createRabbitSlot(String activeForm, HyUIStyle labelStyle) {
        boolean rabbitHighlighted = RABBIT_FORM.equalsIgnoreCase(activeForm);
        GroupBuilder rabbitSlot = GroupBuilder.group()
                .withRawId("druidPersistentFormHudRabbitSlot")
                .withAnchor(new HyUIAnchor().setWidth(56).setHeight(30))
                .withLayoutMode("Center")
                .withBackground(new HyUIPatchStyle().setColor("#00000000"))
                .withOutlineColor("#00000000")
                .withOutlineSize(0f);

        String resolvedTexturePath = resolveRabbitTexturePath();
        if (resolvedTexturePath != null) {
            GroupBuilder rabbitOverlay = GroupBuilder.group()
                    .withRawId("druidPersistentFormHudRabbitOverlay")
                    .withAnchor(new HyUIAnchor().setWidth(68).setHeight(68));

            if (rabbitHighlighted) {
                rabbitOverlay.addChild(createSquareHighlight("druidPersistentFormHudRabbitHighlight"));
            }

            rabbitOverlay.addChild(
                    GroupBuilder.group()
                            .withRawId("druidPersistentFormHudRabbitIconLayer")
                            .withAnchor(new HyUIAnchor().setWidth(68).setHeight(68))
                            .withLayoutMode("Center")
                            .addChild(
                                    ImageBuilder.image()
                                            .withRawId("druidPersistentFormHudRabbitIcon")
                                            .withAnchor(new HyUIAnchor().setLeft(-1).setWidth(56).setHeight(56))
                                            .withImage(resolvedTexturePath)
                            )
            );

            rabbitSlot.addChild(rabbitOverlay);
            return rabbitSlot;
        }

        rabbitSlot.addChild(
                LabelBuilder.label()
                        .withRawId("druidPersistentFormHudRabbitFallback")
                        .withAnchor(new HyUIAnchor().setLeft(0).setRight(0).setTop(0).setHeight(30))
                        .withStyle(labelStyle)
                        .withText(rabbitHighlighted ? "[RABBIT]" : "Rabbit")
        );
        return rabbitSlot;
    }

    private static GroupBuilder createAntelopeSlot(String activeForm, HyUIStyle labelStyle) {
        boolean antelopeHighlighted = ANTELOPE_FORM.equalsIgnoreCase(activeForm);
        GroupBuilder antelopeSlot = GroupBuilder.group()
                .withRawId("druidPersistentFormHudAntelopeSlot")
                .withAnchor(new HyUIAnchor().setWidth(56).setHeight(30))
                .withLayoutMode("Center")
                .withBackground(new HyUIPatchStyle().setColor("#00000000"))
                .withOutlineColor("#00000000")
                .withOutlineSize(0f);

        String resolvedTexturePath = resolveAntelopeTexturePath();
        if (resolvedTexturePath != null) {
            GroupBuilder antelopeOverlay = GroupBuilder.group()
                    .withRawId("druidPersistentFormHudAntelopeOverlay")
                    .withAnchor(new HyUIAnchor().setWidth(68).setHeight(68));

            if (antelopeHighlighted) {
                antelopeOverlay.addChild(createSquareHighlight("druidPersistentFormHudAntelopeHighlight"));
            }

            antelopeOverlay.addChild(
                    GroupBuilder.group()
                            .withRawId("druidPersistentFormHudAntelopeIconLayer")
                            .withAnchor(new HyUIAnchor().setWidth(68).setHeight(68))
                            .withLayoutMode("Center")
                            .addChild(
                                    ImageBuilder.image()
                                            .withRawId("druidPersistentFormHudAntelopeIcon")
                                            .withAnchor(new HyUIAnchor().setLeft(-1).setWidth(56).setHeight(56))
                                            .withImage(resolvedTexturePath)
                            )
            );

            antelopeSlot.addChild(antelopeOverlay);
            return antelopeSlot;
        }

        antelopeSlot.addChild(
                LabelBuilder.label()
                        .withRawId("druidPersistentFormHudAntelopeFallback")
                        .withAnchor(new HyUIAnchor().setLeft(0).setRight(0).setTop(0).setHeight(30))
                        .withStyle(labelStyle)
                        .withText(antelopeHighlighted ? "[ANTELOPE]" : "Antelope")
        );
        return antelopeSlot;
    }

    private static GroupBuilder createSquareHighlight(String rawId) {
        return GroupBuilder.group()
                .withRawId(rawId)
                .withAnchor(new HyUIAnchor().setLeft(2).setTop(2).setWidth(64).setHeight(64))
                .withBackground(new HyUIPatchStyle().setColor("#26FFB347"))
                .withOutlineColor("#FFFFB347")
                .withOutlineSize(3.5f);
    }

    private static String resolveBearTexturePath() {
        ClassLoader classLoader = DruidHyUiPersistentFormHud.class.getClassLoader();
        URL resource = classLoader == null ? null : classLoader.getResource(BEAR_ICON_CLASSPATH_PATH);
        if (resource != null) {
            System.out.println("[DruidHyUI] Bear icon texture path resolved: " + BEAR_ICON_TEXTURE_PATH
                    + " (classpath: " + BEAR_ICON_CLASSPATH_PATH + ")");
            return BEAR_ICON_TEXTURE_PATH;
        }
        System.out.println("[DruidHyUI] Bear icon texture missing on classpath: " + BEAR_ICON_CLASSPATH_PATH
                + " (falling back to Bear text)");
        return null;
    }

    private static String resolveTigerTexturePath() {
        ClassLoader classLoader = DruidHyUiPersistentFormHud.class.getClassLoader();
        URL resource = classLoader == null ? null : classLoader.getResource(TIGER_ICON_CLASSPATH_PATH);
        if (resource != null) {
            System.out.println("[DruidHyUI] Tiger icon texture path resolved: " + TIGER_ICON_TEXTURE_PATH
                    + " (classpath: " + TIGER_ICON_CLASSPATH_PATH + ")");
            return TIGER_ICON_TEXTURE_PATH;
        }
        System.out.println("[DruidHyUI] Tiger icon texture missing on classpath: " + TIGER_ICON_CLASSPATH_PATH
                + " (falling back to Tiger text)");
        return null;
    }

    private static String resolveHawkTexturePath() {
        ClassLoader classLoader = DruidHyUiPersistentFormHud.class.getClassLoader();
        URL resource = classLoader == null ? null : classLoader.getResource(HAWK_ICON_CLASSPATH_PATH);
        if (resource != null) {
            System.out.println("[DruidHyUI] Hawk icon texture path resolved: " + HAWK_ICON_TEXTURE_PATH
                    + " (classpath: " + HAWK_ICON_CLASSPATH_PATH + ")");
            return HAWK_ICON_TEXTURE_PATH;
        }
        System.out.println("[DruidHyUI] Hawk icon texture missing on classpath: " + HAWK_ICON_CLASSPATH_PATH
                + " (falling back to Hawk text)");
        return null;
    }

    private static String resolveSharkTexturePath() {
        ClassLoader classLoader = DruidHyUiPersistentFormHud.class.getClassLoader();
        URL resource = classLoader == null ? null : classLoader.getResource(SHARK_ICON_CLASSPATH_PATH);
        if (resource != null) {
            System.out.println("[DruidHyUI] Shark icon texture path resolved: " + SHARK_ICON_TEXTURE_PATH
                    + " (classpath: " + SHARK_ICON_CLASSPATH_PATH + ")");
            return SHARK_ICON_TEXTURE_PATH;
        }
        System.out.println("[DruidHyUI] Shark icon texture missing on classpath: " + SHARK_ICON_CLASSPATH_PATH
                + " (falling back to Shark text)");
        return null;
    }

    private static String resolveRamTexturePath() {
        ClassLoader classLoader = DruidHyUiPersistentFormHud.class.getClassLoader();
        URL resource = classLoader == null ? null : classLoader.getResource(RAM_ICON_CLASSPATH_PATH);
        if (resource != null) {
            System.out.println("[DruidHyUI] Ram icon texture path resolved: " + RAM_ICON_TEXTURE_PATH
                    + " (classpath: " + RAM_ICON_CLASSPATH_PATH + ")");
            return RAM_ICON_TEXTURE_PATH;
        }
        System.out.println("[DruidHyUI] Ram icon texture missing on classpath: " + RAM_ICON_CLASSPATH_PATH
                + " (falling back to Ram text)");
        return null;
    }

    private static String resolveAquaticTexturePath() {
        ClassLoader classLoader = DruidHyUiPersistentFormHud.class.getClassLoader();
        URL resource = classLoader == null ? null : classLoader.getResource(AQUATIC_ICON_CLASSPATH_PATH);
        if (resource != null) {
            System.out.println("[DruidHyUI] Aquatic icon texture path resolved: " + AQUATIC_ICON_TEXTURE_PATH
                    + " (classpath: " + AQUATIC_ICON_CLASSPATH_PATH + ")");
            return AQUATIC_ICON_TEXTURE_PATH;
        }
        System.out.println("[DruidHyUI] Aquatic icon texture missing on classpath: " + AQUATIC_ICON_CLASSPATH_PATH
                + " (falling back to Aquatic text)");
        return null;
    }

    private static String resolveRabbitTexturePath() {
        ClassLoader classLoader = DruidHyUiPersistentFormHud.class.getClassLoader();
        URL resource = classLoader == null ? null : classLoader.getResource(RABBIT_ICON_CLASSPATH_PATH);
        if (resource != null) {
            System.out.println("[DruidHyUI] Rabbit icon texture path resolved: " + RABBIT_ICON_TEXTURE_PATH
                    + " (classpath: " + RABBIT_ICON_CLASSPATH_PATH + ")");
            return RABBIT_ICON_TEXTURE_PATH;
        }
        System.out.println("[DruidHyUI] Rabbit icon texture missing on classpath: " + RABBIT_ICON_CLASSPATH_PATH
                + " (falling back to Rabbit text)");
        return null;
    }

    private static String resolveAntelopeTexturePath() {
        ClassLoader classLoader = DruidHyUiPersistentFormHud.class.getClassLoader();
        URL resource = classLoader == null ? null : classLoader.getResource(ANTELOPE_ICON_CLASSPATH_PATH);
        if (resource != null) {
            System.out.println("[DruidHyUI] Antelope icon texture path resolved: " + ANTELOPE_ICON_TEXTURE_PATH
                    + " (classpath: " + ANTELOPE_ICON_CLASSPATH_PATH + ")");
            return ANTELOPE_ICON_TEXTURE_PATH;
        }
        System.out.println("[DruidHyUI] Antelope icon texture missing on classpath: " + ANTELOPE_ICON_CLASSPATH_PATH
                + " (falling back to Antelope text)");
        return null;
    }

    private static String resolveActiveFormName(Player player) {
        String modelId;
        try {
            modelId = ShapeshiftHandler.activeForms.get(player.getDisplayName());
        } catch (Exception ignored) {
            modelId = null;
        }
        return displayNameFromModelId(modelId);
    }

    private static String displayNameFromModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) return "Human";
        String lower = modelId.toLowerCase(Locale.ROOT);
        String animalKey = resolveAnimalKeyFromModelIdLower(lower);
        if (animalKey == null) return "Human";

        // Canonical read path: resolve label through the form bridge first.
        String formLabel = FormRuntimeBridge.resolveFormLabelForAnimal(animalKey);
        String currentAnimalLabel = resolveAnimalLabelFromModelIdLower(lower);

        // Preserve existing player-facing output until form labels are intentionally surfaced.
        if (formLabel != null && currentAnimalLabel != null && formLabel.equalsIgnoreCase(currentAnimalLabel)) {
            return formLabel;
        }

        if (currentAnimalLabel == null) {
            if (formLabel != null && !formLabel.isBlank()) return formLabel;
            return "Human";
        }
        return currentAnimalLabel;
    }

    private static String resolveAnimalLabelFromModelIdLower(String lowerModelId) {
        if (lowerModelId == null || lowerModelId.isBlank()) return null;
        if (lowerModelId.contains("bear")) return "Bear";
        if (lowerModelId.contains("tiger") || lowerModelId.contains("sabertooth")) return "Tiger";
        if (lowerModelId.contains("shark")) return "Shark";
        if (lowerModelId.contains("ram")) return "Ram";
        if (lowerModelId.contains("duck")) return "Duck";
        if (lowerModelId.contains("hawk")) return "Hawk";
        if (lowerModelId.contains("rabbit") || lowerModelId.contains("jackalope")) return "Rabbit";
        if (lowerModelId.contains("antelope")) return "Antelope";
        return null;
    }

    private static String resolveAnimalKeyFromModelIdLower(String lowerModelId) {
        if (lowerModelId == null || lowerModelId.isBlank()) return null;
        if (lowerModelId.contains("bear")) return "bear";
        if (lowerModelId.contains("tiger") || lowerModelId.contains("sabertooth")) return "tiger";
        if (lowerModelId.contains("shark")) return "shark";
        if (lowerModelId.contains("bluegill")) return "bluegill";
        if (lowerModelId.contains("ram")) return "ram";
        if (lowerModelId.contains("duck")) return "duck";
        if (lowerModelId.contains("hawk")) return "hawk";
        if (lowerModelId.contains("rabbit") || lowerModelId.contains("jackalope")) return "rabbit";
        if (lowerModelId.contains("antelope")) return "antelope";
        return null;
    }

    private static PlayerRef resolvePlayerRef(Player player) {
        try {
            Method getPlayerRef = player.getClass().getMethod("getPlayerRef");
            Object value = getPlayerRef.invoke(player);
            if (value instanceof PlayerRef) return (PlayerRef) value;
        } catch (Exception ignored) {
        }

        try {
            Field playerRef = getFieldDeep(player.getClass(), "playerRef");
            if (playerRef != null) {
                playerRef.setAccessible(true);
                Object value = playerRef.get(player);
                if (value instanceof PlayerRef) return (PlayerRef) value;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Field getFieldDeep(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }

}
