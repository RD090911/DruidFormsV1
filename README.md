# Druid Forms

## 2.5.6 Stability Patch

- Fixed a critical issue where logging out while shapeshifted and restarting the server could leave players in human form with a shapeshift hotbar, causing human hotbar items to be lost.
- Added persistent recovery for human hotbar snapshots after disconnects and server restarts.
- Fixed Druid Shrine upgrade persistence for tiered class abilities. Successful shrine upgrades now save immediately and survive reloads and server restarts.
- Upgrade progress is raise-only and should not be downgraded by stale or default state.
- Known limitation: placing normal items directly into active form or class ability slots during form-to-form switching is still not fully supported.
