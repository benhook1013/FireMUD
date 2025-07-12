# 🎮 Game Customization Options

This brief document summarizes the ways a hosted game can change its look and feel without modifying FireMUD source code.

## Theme and Branding

- Each tenant provides a Material‑UI theme file that overrides default colors and fonts.
- Logos and favicon assets are loaded at runtime based on the `tenantId`.
- Optional layout tweaks can be enabled via feature flags in the Game Design Service.

## World Configuration

- Game worlds are defined entirely through the Game Design Service. Creators can add rooms, items and NPCs using the web editor or by importing JSON files.
- Published versions are stored per tenant so multiple games can coexist on the same infrastructure. Minor fixes can be rolled out as **script-only patch versions** linked to a `baseVersionId` so worlds do not need to restart.

## Scripting Hooks

- Custom scripts drive dynamic events and NPC behaviour.
- Scripts are versioned alongside other game data and can be hot reloaded by the Automation & Scripting Service. Designers may publish a `scriptPatchVersion` like `v42-script.3` to update automation without republishing all assets.

These options allow extensive personalization while keeping the underlying platform maintainable.
