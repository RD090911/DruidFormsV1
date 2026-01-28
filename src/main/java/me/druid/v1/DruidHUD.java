package me.druid.v1;

import com.hypixel.hytale.server.core.ui.CustomUIHud;
import com.hypixel.hytale.server.core.ui.UICommandBuilder;
import com.hypixel.hytale.server.core.component.Ref;
import com.hypixel.hytale.server.core.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.ui.component.Style;

public class DruidHUD extends CustomUIHud {

    private final String activeForm;

    public DruidHUD(String activeForm) {
        this.activeForm = activeForm;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder ui, Store<EntityStore> store) {
        // Load the visual layout file (We will fix the file location in Step 2)
        ui.append("druid_hud.ui");

        // Match the Form Name to the UI ID
        String targetWrapper = null;

        if ("DruidBearForm".equals(activeForm))            targetWrapper = "#WrapperBear";
        else if ("DruidSabretoothForm".equals(activeForm)) targetWrapper = "#WrapperSabretooth";
        else if ("DruidSharkForm".equals(activeForm))      targetWrapper = "#WrapperShark";
        else if ("DruidAntelopeForm".equals(activeForm))   targetWrapper = "#WrapperAntelope";
        else if ("DruidHawkForm".equals(activeForm))       targetWrapper = "#WrapperHawk";
        else if ("DruidRamForm".equals(activeForm))        targetWrapper = "#WrapperRam";
        else if ("DruidJackalopeForm".equals(activeForm))  targetWrapper = "#WrapperRabbit";
        else if ("DruidDuckForm".equals(activeForm))       targetWrapper = "#WrapperDuck";

        // Turn on the Yellow Highlight
        if (targetWrapper != null) {
            ui.modify(targetWrapper, (component) -> {
                Style style = new Style();
                style.setBackground("#FFD700"); // Gold/Yellow
                style.setBackgroundAlpha(0.6f); // See-through glow
                style.setBorderColor("#FFFFFF");
                style.setBorderThickness(2);
                component.setStyle(style);
            });
        }
    }
}