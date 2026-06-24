package me.druid.v1;

import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.MouseButtonEvent;
import com.hypixel.hytale.protocol.MouseButtonState;
import com.hypixel.hytale.protocol.MouseButtonType;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interaction.CancelInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import me.druid.v1.hud.DruidHyUiAnimalSelectorHud;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class DruidCommandInteractionCompat {
    private static final Map<String, String> ROOT_TO_COMMAND = createRootToCommandMap();
    private static final long DEDUPE_WINDOW_MILLIS = 175L;
    private static final Map<UUID, Long> LAST_DISPATCH_MILLIS_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> READY_PLAYERS = new ConcurrentHashMap<>();

    private DruidCommandInteractionCompat() {
    }

    static void handleMouseButton(PlayerMouseButtonEvent event) {
        if (event == null) return;

        MouseButtonEvent mouse = event.getMouseButton();
        if (mouse == null || mouse.state != MouseButtonState.Pressed) return;

        InteractionType interactionType = mapInteractionType(mouse.mouseButtonType);
        if (interactionType == null) return;

        Player player = event.getPlayer();
        if (player == null) return;

        Item eventItem = event.getItemInHand();
        String inventoryHeldItemId = resolveHeldItemIdFromInventory(player);
        Item resolvedItem = eventItem;

        String rootInteraction = resolveRootInteraction(event, interactionType);
        String command = resolveCommand(rootInteraction, resolvedItem, inventoryHeldItemId, interactionType);
        String fallbackItemId = resolvedItem != null && resolvedItem.getId() != null && !resolvedItem.getId().isBlank()
                ? resolvedItem.getId()
                : inventoryHeldItemId;
        if (command == null
                && interactionType == InteractionType.Secondary
                && shouldOpenRadialFallback(fallbackItemId)) {
            command = "shapeshift ui";
        }
        dispatchCommand(player, command, "mouse");
    }

    static void handleInteract(PlayerInteractEvent event) {
        if (event == null) return;
        Player player = event.getPlayer();
        if (player == null) return;

        String actionType = String.valueOf(event.getActionType());
        if (!isLikelySecondaryAction(actionType)) return;

        String eventItemId = resolveItemIdFromStack(event.getItemInHand());
        String inventoryHeldItemId = resolveHeldItemIdFromInventory(player);
        String command = resolveCommand(null, null,
                eventItemId != null ? eventItemId : inventoryHeldItemId,
                InteractionType.Secondary);
        if (command == null && shouldOpenRadialFallback(inventoryHeldItemId != null ? inventoryHeldItemId : eventItemId)) {
            command = "shapeshift ui";
        }

        dispatchCommand(player, command, "interact");
    }

    static boolean handleInboundPacket(PlayerRef playerRef, Packet packet) {
        if (!(packet instanceof SyncInteractionChains chains) || playerRef == null) {
            return false;
        }
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null || !Boolean.TRUE.equals(READY_PLAYERS.get(playerUuid))) {
            return false;
        }
        if (chains.updates == null || chains.updates.length == 0) {
            return false;
        }

        for (SyncInteractionChain chain : chains.updates) {
            if (chain == null) continue;
            InteractionType interactionType = chain.interactionType;
            if (interactionType != InteractionType.Secondary && interactionType != InteractionType.Primary) continue;

            String heldItemId = chain.itemInHandId;
            ProwlerPounceAbilityService.handleInteractionUpdate(playerUuid, chain);
            if (isInitialProwlerStealthPrimary(chain)) {
                Player player = DruidPermissions.getOnlinePlayer(playerUuid);
                if (player == null) {
                    System.out.println("[ProwlerStealth] blocked reason=player-unavailable");
                    cancelInteractionChain(playerRef, chain);
                    return true;
                }
                if (!ProwlerStealthService.tryToggle(player, chain.activeHotbarSlot)) {
                    cancelInteractionChain(playerRef, chain);
                    return true;
                }
                cancelInteractionChain(playerRef, chain);
                return true;
            }
            if (isInitialProwlerClawPrimary(chain)) {
                Player player = DruidPermissions.getOnlinePlayer(playerUuid);
                if (player != null) {
                    ProwlerStealthService.recordCombat(player);
                    ProwlerStealthService.breakStealth(player, "claw");
                }
            }
            if (isInitialProwlerBitePrimary(chain)) {
                Player player = DruidPermissions.getOnlinePlayer(playerUuid);
                if (player == null) {
                    System.out.println("[ProwlerBiteCooldown] blocked reason=player-unavailable");
                    cancelInteractionChain(playerRef, chain);
                    return true;
                }
                if (!ProwlerBiteCooldownService.tryStart(player, chain.activeHotbarSlot)) {
                    cancelInteractionChain(playerRef, chain);
                    return true;
                }
                ProwlerStealthService.recordCombat(player);
                ProwlerStealthService.breakStealth(player, "bite");
                continue;
            }
            if (isInitialProwlerPouncePrimary(chain)) {
                Player player = DruidPermissions.getOnlinePlayer(playerUuid);
                if (player == null) {
                    System.out.println("[ProwlerPounce] blocked reason=player-unavailable");
                    cancelInteractionChain(playerRef, chain);
                    return true;
                }
                boolean wasStealthedAtPounceStart = ProwlerStealthService.isStealthed(player);
                if (!ProwlerPounceAbilityService.tryStart(
                        player,
                        chain.activeHotbarSlot,
                        chain.chainId,
                        wasStealthedAtPounceStart
                )) {
                    cancelInteractionChain(playerRef, chain);
                    return true;
                }
                ProwlerStealthService.recordCombat(player);
                ProwlerStealthService.breakStealth(player, "pounce");
                continue;
            }
            if (isInitialGuardianGroundSlamPrimary(chain)) {
                Player player = DruidPermissions.getOnlinePlayer(playerUuid);
                if (player != null) {
                    GuardianGroundSlamCooldownService.onTriggered(player, chain.activeHotbarSlot, chain.itemInHandId);
                }
                continue;
            }
            if (isInitialGuardianOakenshieldPrimary(chain)) {
                Player player = DruidPermissions.getOnlinePlayer(playerUuid);
                if (player != null) {
                    GuardianOakenshieldCooldownService.onTriggered(player, chain.activeHotbarSlot, chain.itemInHandId);
                }
                continue;
            }
            if (isInitialGuardianChallengingRoarPrimary(chain)) {
                Player player = DruidPermissions.getOnlinePlayer(playerUuid);
                if (player == null) {
                    System.out.println("[GuardianChallengingRoar] blocked reason=player-unavailable");
                    cancelInteractionChain(playerRef, chain);
                    return true;
                }
                if (!GuardianChallengingRoarAbilityService.tryStart(player, chain.activeHotbarSlot)) {
                    cancelInteractionChain(playerRef, chain);
                    return true;
                }
                continue;
            }

            String command = resolveCommand(null, null, heldItemId, interactionType);
            if (command == null
                    && interactionType == InteractionType.Secondary
                    && shouldOpenRadialFallback(heldItemId)) {
                command = "shapeshift ui";
            }
            if (command != null) {
                dispatchCommand(playerRef, command, "packet");
                break;
            }
        }

        return false;
    }

    static void markPlayerReady(UUID playerUuid) {
        if (playerUuid == null) return;
        READY_PLAYERS.put(playerUuid, true);
    }

    static void clearPlayerReady(UUID playerUuid) {
        if (playerUuid == null) return;
        READY_PLAYERS.remove(playerUuid);
        LAST_DISPATCH_MILLIS_BY_PLAYER.remove(playerUuid);
        DruidHyUiAnimalSelectorHud.close(playerUuid);
    }

    private static boolean isLikelySecondaryAction(String actionType) {
        if (actionType == null || actionType.isBlank()) return false;
        String normalized = actionType.toLowerCase();
        if (normalized.contains("secondary")) return true;
        if (normalized.contains("use")) return true;
        return !normalized.contains("primary") && !normalized.contains("attack");
    }

    private static void dispatchCommand(Player player, String command, String source) {
        if (player == null || command == null || command.isBlank()) return;
        UUID playerUuid = safePlayerUuid(player);
        long now = System.currentTimeMillis();
        if (playerUuid != null) {
            Long previous = LAST_DISPATCH_MILLIS_BY_PLAYER.get(playerUuid);
            if (previous != null && (now - previous) < DEDUPE_WINDOW_MILLIS) {
                return;
            }
            LAST_DISPATCH_MILLIS_BY_PLAYER.put(playerUuid, now);
        }

        var playerRef = DruidPlayerCompat.getPlayerRef(player);
        if (playerRef == null) return;
        dispatchCommand(playerRef, command, source);
    }

    private static void dispatchCommand(PlayerRef playerRef, String command, String source) {
        if (playerRef == null || command == null || command.isBlank()) return;
        UUID playerUuid = playerRef.getUuid();
        long now = System.currentTimeMillis();
        if (playerUuid != null) {
            Long previous = LAST_DISPATCH_MILLIS_BY_PLAYER.get(playerUuid);
            if (previous != null && (now - previous) < DEDUPE_WINDOW_MILLIS) {
                return;
            }
            LAST_DISPATCH_MILLIS_BY_PLAYER.put(playerUuid, now);
        }

        try {
            CommandManager.get().handleCommand(playerRef, command);
        } catch (Exception e) {
            System.out.println("[DruidCommandCompat] command dispatch failed source=" + source
                    + " command=" + command + " reason=" + e.getMessage());
        }
    }

    private static String resolveCommand(String rootInteraction, Item item, String fallbackItemId, InteractionType interactionType) {
        String normalizedRoot = normalizeRootInteractionId(rootInteraction);
        String command = normalizedRoot == null ? null : ROOT_TO_COMMAND.get(normalizedRoot);
        if (command != null) return command;

        String itemId = item == null ? null : item.getId();
        if ((itemId == null || itemId.isBlank()) && fallbackItemId != null && !fallbackItemId.isBlank()) {
            itemId = fallbackItemId;
        }
        if (itemId == null) return null;

        if ("Druid_Totem".equals(itemId) && interactionType == InteractionType.Secondary) return "shapeshift ui";
        if ("Life_Seed".equals(itemId)
                || "Elder_Life_Seed".equals(itemId)
                || "Primal_Life_Seed".equals(itemId)
                || "Verdant_Life_Seed".equals(itemId)) {
            return interactionType == InteractionType.Primary ? "wardenlifeseed" : null;
        }
        if ("Gaias_Touch".equals(itemId)) {
            return interactionType == InteractionType.Primary ? "wardengaiastouch" : null;
        }
        if ("Spring_Of_Renewal".equals(itemId)) {
            return interactionType == InteractionType.Primary ? "wardenspringofrenewal" : null;
        }
        if ("Natures_Resurgence".equals(itemId)) {
            return interactionType == InteractionType.Primary ? "wardennaturesresurgence" : null;
        }
        if ("Rootlight_Spirit_Ability".equals(itemId)) {
            return interactionType == InteractionType.Primary ? "rootlightspirit" : null;
        }
        return null;
    }

    private static boolean shouldOpenRadialFallback(String itemId) {
        return DruidControlItemMatcher.matches(itemId);
    }

    private static boolean isInitialProwlerBitePrimary(SyncInteractionChain chain) {
        return chain != null
                && chain.initial
                && chain.interactionType == InteractionType.Primary
                && ProwlerBiteCooldownService.BITE_ITEM_ID.equals(chain.itemInHandId);
    }

    private static boolean isInitialProwlerPouncePrimary(SyncInteractionChain chain) {
        return chain != null
                && chain.initial
                && chain.interactionType == InteractionType.Primary
                && ProwlerPounceAbilityService.POUNCE_ITEM_ID.equals(chain.itemInHandId);
    }

    private static boolean isInitialProwlerStealthPrimary(SyncInteractionChain chain) {
        return chain != null
                && chain.initial
                && chain.interactionType == InteractionType.Primary
                && ProwlerStealthService.STEALTH_ITEM_ID.equals(chain.itemInHandId);
    }

    private static boolean isInitialProwlerClawPrimary(SyncInteractionChain chain) {
        return chain != null
                && chain.initial
                && chain.interactionType == InteractionType.Primary
                && chain.itemInHandId != null
                && chain.itemInHandId.contains("Tiger_Claw");
    }

    private static boolean isInitialGuardianGroundSlamPrimary(SyncInteractionChain chain) {
        return chain != null
                && chain.initial
                && chain.interactionType == InteractionType.Primary
                && GuardianGroundSlamCooldownService.isGroundSlamItemId(chain.itemInHandId);
    }

    private static boolean isInitialGuardianOakenshieldPrimary(SyncInteractionChain chain) {
        return chain != null
                && chain.initial
                && chain.interactionType == InteractionType.Primary
                && GuardianOakenshieldCooldownService.isOakenshieldItemId(chain.itemInHandId);
    }

    private static boolean isInitialGuardianChallengingRoarPrimary(SyncInteractionChain chain) {
        return chain != null
                && chain.initial
                && chain.interactionType == InteractionType.Primary
                && GuardianChallengingRoarAbilityService.isChallengingRoarItemId(chain.itemInHandId);
    }

    private static void cancelInteractionChain(PlayerRef playerRef, SyncInteractionChain chain) {
        if (playerRef == null || chain == null || playerRef.getPacketHandler() == null) {
            return;
        }
        try {
            playerRef.getPacketHandler().writeNoCache(new CancelInteractionChain(chain.chainId, chain.forkedId));
        } catch (Exception exception) {
            System.out.println("[DruidInteractionCompat] cancel failed reason="
                    + exception.getClass().getSimpleName());
        }
    }

    private static String normalizeRootInteractionId(String rootInteraction) {
        if (rootInteraction == null || rootInteraction.isBlank()) return null;
        String normalized = rootInteraction;
        if (normalized.charAt(0) == '*') {
            normalized = normalized.substring(1);
        }
        int interactionSuffix = normalized.indexOf("_Interactions_");
        if (interactionSuffix > 0) {
            normalized = normalized.substring(0, interactionSuffix);
        }
        return normalized;
    }

    private static InteractionType mapInteractionType(MouseButtonType buttonType) {
        if (buttonType == MouseButtonType.Left) return InteractionType.Primary;
        if (buttonType == MouseButtonType.Right) return InteractionType.Secondary;
        return null;
    }

    private static String resolveRootInteraction(PlayerMouseButtonEvent event, InteractionType interactionType) {
        var item = event.getItemInHand();
        if (item == null) return null;
        Map<InteractionType, String> interactions = item.getInteractions();
        if (interactions == null || interactions.isEmpty()) return null;
        return interactions.get(interactionType);
    }

    private static String resolveHeldItemIdFromInventory(Player player) {
        if (player == null) return null;
        try {
            Object inventory = player.getClass().getMethod("getInventory").invoke(player);
            if (inventory == null) return null;
            Object held = inventory.getClass().getMethod("getItemInHand").invoke(inventory);
            if (held == null) return null;
            Object itemId = held.getClass().getMethod("getItemId").invoke(held);
            return itemId instanceof String ? (String) itemId : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static UUID safePlayerUuid(Player player) {
        if (player == null) return null;
        try {
            return player.getUuid();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safePlayerName(PlayerRef playerRef) {
        if (playerRef == null) return "unknown";
        try {
            String username = playerRef.getUsername();
            if (username != null && !username.isBlank()) return username;
        } catch (Exception ignored) {
        }
        UUID uuid = playerRef.getUuid();
        return uuid == null ? "unknown" : uuid.toString();
    }

    private static String resolveItemIdFromStack(Object itemStack) {
        if (itemStack == null) return null;
        try {
            Object itemId = itemStack.getClass().getMethod("getItemId").invoke(itemStack);
            return itemId instanceof String ? (String) itemId : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isPlayerTransformed(Player player) {
        if (player == null) return false;
        String playerName = DruidPlayerCompat.getPlayerName(player);
        return isTransformedPlayerName(playerName);
    }

    private static boolean isPlayerRefTransformed(PlayerRef playerRef) {
        if (playerRef == null) return false;
        String playerName = null;
        try {
            playerName = playerRef.getUsername();
        } catch (Exception ignored) {
        }
        return isTransformedPlayerName(playerName);
    }

    private static boolean isTransformedPlayerName(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        String activeModelId = ShapeshiftHandler.activeForms.get(playerName);
        return activeModelId != null && !activeModelId.isBlank();
    }

    private static Map<String, String> createRootToCommandMap() {
        Map<String, String> mapping = new HashMap<>();
        mapping.put("Druid_Open_Radial_Menu", "shapeshift ui");
        mapping.put("Druid_Sword_Ability1_Radial_Menu", "shapeshift ui");
        mapping.put("Druid_Mace_Ability1_Radial_Menu", "shapeshift ui");
        mapping.put("Druid_Daggers_Ability1_Radial_Menu", "shapeshift ui");
        mapping.put("Druid_Warden_Life_Seed_Primary", "wardenlifeseed");
        mapping.put("Druid_Warden_Gaias_Touch_Primary", "wardengaiastouch");
        mapping.put("Druid_Warden_Spring_Of_Renewal_Primary", "wardenspringofrenewal");
        mapping.put("Druid_Warden_Natures_Resurgence_Primary", "wardennaturesresurgence");
        mapping.put("Druid_Rootlight_Spirit_Primary", "rootlightspirit");
        return mapping;
    }
}
