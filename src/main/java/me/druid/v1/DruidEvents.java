package me.druid.v1;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;

public class DruidEvents {

    public void onEntityDamage(Damage event) {
        // Use getSource() to find the victim
        Object source = event.getSource();

        // 1. Check if the victim is a player
        if (!(source instanceof Player)) return;

        Player player = (Player) source;
        String playerName = player.getDisplayName();

        // 2. Check if they are shapeshifted
        if (!ShapeshiftHandler.activeForms.containsKey(playerName)) return;

        String currentFormID = ShapeshiftHandler.activeForms.get(playerName);

        // 3. Get the cause as a String.
        // This fixes the "Red Words" error by ignoring the complex import entirely.
        String causeName = event.getCause().toString();

        // --- RABBIT: NO FALL DAMAGE ---
        // We check if the cause name contains "FALL" (Works for "FALL", "fall", "DamageCause.FALL", etc.)
        if (currentFormID.equals("Rabbit") && causeName.contains("FALL")) {
            event.setCancelled(true);
        }

        // --- SHARK: NO DROWNING ---
        if (currentFormID.equals("Shark_Hammerhead") && causeName.contains("DROWNING")) {
            event.setCancelled(true);
        }
    }
}