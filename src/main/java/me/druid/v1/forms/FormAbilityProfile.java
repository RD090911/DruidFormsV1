package me.druid.v1.forms;

public final class FormAbilityProfile {
    private final boolean canFly;
    private final boolean aquatic;
    private final float moveSpeedMultiplier;
    private final boolean healOnHit;
    private final boolean poisonOnHit;
    private final float armorBonus;
    private final boolean jumpBoost;
    private final boolean resourceBonus;

    public FormAbilityProfile(
            boolean canFly,
            boolean aquatic,
            float moveSpeedMultiplier,
            boolean healOnHit,
            boolean poisonOnHit,
            float armorBonus,
            boolean jumpBoost,
            boolean resourceBonus
    ) {
        this.canFly = canFly;
        this.aquatic = aquatic;
        this.moveSpeedMultiplier = moveSpeedMultiplier;
        this.healOnHit = healOnHit;
        this.poisonOnHit = poisonOnHit;
        this.armorBonus = armorBonus;
        this.jumpBoost = jumpBoost;
        this.resourceBonus = resourceBonus;
    }

    public boolean canFly() {
        return canFly;
    }

    public boolean aquatic() {
        return aquatic;
    }

    public float moveSpeedMultiplier() {
        return moveSpeedMultiplier;
    }

    public boolean healOnHit() {
        return healOnHit;
    }

    public boolean poisonOnHit() {
        return poisonOnHit;
    }

    public float armorBonus() {
        return armorBonus;
    }

    public boolean jumpBoost() {
        return jumpBoost;
    }

    public boolean resourceBonus() {
        return resourceBonus;
    }
}
