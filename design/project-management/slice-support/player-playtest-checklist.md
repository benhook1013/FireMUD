# Player Playtest Checklist

This document is the high-signal human playtest checklist for the currently implemented FireMUD feature surface.

It is intentionally different from:

- automated smoke scripts in `dev-tools/`;
- narrow slice-specific proof docs such as [look-smoke-tests.md](./look-smoke-tests.md);
- creator playtest process notes in [playtesting-feedback.md](./playtesting-feedback.md).

Use this checklist when you want to sit down as a tester and verify that the currently shipped player-facing systems feel coherent from the outside.

## How To Use This Checklist

- Treat this as a highlights checklist, not a full regression matrix.
- Run it against the environment you actually care about:
  - local source-built stack;
  - local image smoke stack;
  - preview;
  - staging/playtest realm.
- Capture anything that is:
  - broken;
  - inconsistent between text and first-party flows;
  - obviously confusing or misleading as player UX;
  - correct but awkward enough that it likely needs design follow-up.
- When possible, record:
  - exact commands or clicks;
  - exact error text;
  - whether the problem is text-only, first-party-only, or both;
  - whether reconnect/retry changes the result.

## Environment Preconditions

Before the manual pass:

- confirm the stack is healthy enough for gameplay admission;
- ensure you have at least:
  - one normal player account;
  - one second account if you want to check multi-user presence/chat;
  - access to any non-production or playtest realm you intend to verify;
- know whether you are testing:
  - text/Telnet flow;
  - direct WebSocket flow;
  - first-party bootstrap/browser flow.

If you need a canonical bootstrap/smoke proof first, use:

- `dev-tools/verify-fresh-bootstrap.sh`
- `dev-tools/verify-restart-state.sh`
- `SMOKE_IMAGE_TAG=<tag> dev-tools/verify-smoke-images.sh`

## 1. Core Admission And Lobby

- Verify login succeeds with the expected account and does not leave you in a half-authenticated state.
- Verify failed login is explicit and does not drop you into gameplay accidentally.
- Run `WORLDS` and confirm:
  - at least one visible world appears;
  - hidden/non-granted worlds do not appear.
- If the world has multiple realms, run `REALMS <world>` and confirm:
  - the production/default realm appears correctly;
  - any granted non-production realm appears only when expected;
  - unauthorized realms are not leaked.
- Run `CHARS <world> [realm]` and confirm:
  - the roster matches the selected realm;
  - the creation policy is explicit rather than implied by an empty list;
  - switching realms can change the roster in a believable way.
- Run `PLAY <world> [realm] [character]` and confirm:
  - successful play enters gameplay cleanly;
  - stale or invalid selection fails clearly;
  - first admission to the public production realm works if expected;
  - non-production realms still require explicit access.

## 2. First-Party Bootstrap And Connect Flow

Use this when testing the browser/first-party route rather than plain text only.

- Confirm bootstrap discovery shows the same worlds/realms/characters as the text lobby path for the same account.
- Confirm connect succeeds only for a currently valid selection.
- Refresh/retry the connect flow and confirm:
  - a valid retry still works;
  - a stale selection fails clearly rather than silently routing somewhere else.
- If possible, let the selector age or change the target under it and confirm stale-scope behavior fails closed.

## 3. Room Entry And World Browsing

- Immediately after `PLAY`, verify the initial room/entry state looks coherent.
- Run `LOOK` and confirm:
  - room name, prose, exits, and visible entities are present;
  - the output shape is stable and readable;
  - repeated `LOOK` is consistent.
- If room movement is available, move through at least a couple of exits and confirm:
  - movement succeeds when valid;
  - invalid movement fails clearly;
  - post-move room view updates correctly.
- If world/realm differences are expected, compare production vs isolated/playtest realm behavior at room-entry level.

## 4. Presence, Activity, And Session State

- Run `WHO` and confirm:
  - it shows only the current game instance;
  - gods/admins, if present, are grouped correctly;
  - activity markers such as `(AFK)` or `(idle)` look believable.
- Toggle `AFK`, `AFK ON`, and `AFK OFF` if available and confirm `WHO` reflects the change.
- Leave the session idle long enough for auto-AFK behavior if practical and confirm the transition is believable.
- Run `LOGOUT` and confirm:
  - the session exits cleanly;
  - reconnecting afterward behaves like a fresh or expected resumed path, not a broken zombie session.

## 5. Reconnect And Replacement Session Behavior

- While in gameplay, disconnect unexpectedly if practical and confirm reconnect behavior is coherent.
- Reconnect and confirm:
  - the correct account/world/realm/character binding is restored when allowed;
  - stale or invalid reconnect attempts fail clearly.
- Start a replacement session from another client if practical and confirm:
  - the losing session is terminated or displaced cleanly;
  - the new session becomes authoritative;
  - presence and recent-seen behavior remain believable.

## 6. Inventory, Containers, Equipment, And Stack Handling

- Inspect current inventory and confirm the presentation is readable and stable.
- Pick up, drop, put, and take items if the environment supports it.
- Confirm container behavior is coherent:
  - moving items into containers works;
  - taking them back out works;
  - room/inventory/container location changes are obvious.
- If equipment is available, wear/remove items and confirm the result is visible and believable.
- If duplicate or stackable items are available, verify:
  - normal unambiguous stack operations work naturally;
  - ambiguous stack operations fail with an explicit selector requirement;
  - the surfaced selector is understandable enough for a player to retry successfully.

## 7. Communication And Social

Use a second player account/client when possible.

- Verify `say` reaches the room correctly.
- Verify `whisper` reaches only the intended target.
- Verify `tell` works across the intended gameplay scope.
- Verify `HELP` works for:
  - built-in commands;
  - any authored/configured commands you expect to be visible.
- Verify `FRIENDS` shows believable online/offline presence for connected accounts.
- If privacy differences are configured, confirm hidden/private behavior suppresses character/world detail appropriately.

## 8. Text Versus First-Party Parity

Where both paths exist, compare:

- admission and world/realm/character selection;
- room entry and `LOOK`;
- `WHO`;
- `FRIENDS`;
- logout/reconnect behavior.

Flag any case where one client path:

- exposes data the other does not;
- routes to a different target for the same choice;
- renders a clearly inconsistent state;
- hides an error the other client shows explicitly.

## 9. Realm And Playtest-Specific Checks

Use this when validating a non-production or isolated realm.

- Confirm the realm appears only for the right testers/accounts.
- Confirm `CHARS` reflects the realm-local roster rather than leaking the production roster incorrectly.
- Confirm inventory/equipment state behaves like the selected realm policy implies.
- Confirm returning to the production realm does not silently show isolated/playtest state.
- If the playtest realm is meant to be isolated, confirm progression or loadout changes do not obviously bleed back into production behavior.

## 10. Feedback Capture

For each problem, record:

- path: text, WebSocket, first-party, or all;
- exact world/realm/character used;
- exact command sequence or click flow;
- expected behavior;
- actual behavior;
- whether retry/reconnect changed the outcome.

If you are running a creator playtest cycle, pair this checklist with [playtesting-feedback.md](./playtesting-feedback.md).

## Current Known Limits Of This Checklist

- It is a player highlights checklist, not a substitute for targeted slice verification.
- It does not cover operator-only control-plane validation.
- It does not try to prove discussion-gated durability or scripting families that are not yet implemented.
- It will need expansion as `07.x`, `10.x`, and later frontend-specific slices become real.
