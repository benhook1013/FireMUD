# Shipped Game Profiles Idea

Temporary reminder and discussion seed for a separate product/content-design pass. This idea is intentionally outstanding and has not yet been turned into canonical FireMUD design or a delivery tracker.

## Intended Exploration Venue

Work through the broad product and content questions with ChatGPT first, using the available ChatGPT allowance rather than spending Codex implementation tokens on open-ended ideation. Bring settled conclusions back to the repository only when they are concrete enough to shape canonical design or implementation work.

## Current Direction

FireMUD should ship with authored games or game profiles that demonstrate the soft-configured platform and provide reusable content for creators.

Current candidates:

1. A standard fantasy MUD with warriors, wizards, archers, and related familiar archetypes.
   - This is the primary FireMUD game, not a disposable basic example.
   - It can start with a small playable surface and grow toward the fuller intended game.
2. A D&D-like fantasy rules/game profile.
   - Exact mechanics, scope, originality, and naming remain undecided.
   - It does not have to be part of v1.
3. An eldritch-horror game/profile.
   - Tone, mechanics, content boundaries, and release timing remain undecided.
   - It does not have to be part of v1.

## Current Product Reasoning

- Shipped games provide credible examples of what the platform can create.
- Their assets, NPCs, items, abilities, encounters, and other authored content can become reusable building blocks in the game designer.
- A creator should be able to start from the standard game profile, reuse its assets, or copy/import and modify the primary MUD.
- There is no established need for a separate simplistic import-only game when the standard game can provide the useful starting point.

## Questions For The Separate Discussion

1. Which profile, if any, must ship in v1?
2. What is the smallest genuinely playable boundary for the standard fantasy MUD?
3. Are the other profiles complete games, optional content packs, rulesets, templates, or demonstrations?
4. Which authored assets are shared globally, copied into a game, referenced as immutable library content, or forked for modification?
5. How should upgrades to a shipped game or reusable asset library interact with creator-modified copies?
6. Which game definitions and assets belong in the main repository, and which management or roadmap material needs a separate home?
7. What terminology and original mechanics keep the D&D-like profile distinct from protected branded material?
8. How much breadth is useful before the primary player journey and game-design workflow are proven end to end?

## Desired Handback

Return from the external discussion with:

- a clear purpose for each shipped profile;
- a v1 versus later decision;
- the standard game's minimum playable content boundary;
- an asset reuse, copy, and modification model;
- the intended repository/design ownership;
- a small set of implementation-facing decisions rather than an open-ended lore backlog.

## Status

Outstanding. External ChatGPT exploration is intended before deciding whether this becomes canonical game-template design, a content roadmap, or implementation tracking.
