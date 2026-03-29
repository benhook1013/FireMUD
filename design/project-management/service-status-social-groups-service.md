# Social & Groups Service Status

## Current Coverage

- Core social domain structures are present for chat, guilds, friends, mail, and voice-token concepts.
- The current `SAY` slice integrates with Social & Groups at the present slice level.
- REST/gRPC surfaces, persistence, and moderation-oriented hooks exist in the current service implementation.

## Current Role In The Platform

- Owns social graph, guild, mail, and chat delivery responsibilities outside the immediate gameplay ingress.
- Serves as the downstream social/chat system for gameplay-originated room chat and future richer social channels.
- Provides moderation-relevant chat data to Logging & Admin flows.

## Partial / Stubbed / Deferred Areas

- Some real-time delivery and richer presence/channel behavior remain future expansion work.
- The current gameplay-connected chat path should still be treated as an early slice rather than a complete social platform.
- Integration confidence for richer social behavior is still lighter than the core CRUD/domain model.

## Planning Notes

- Treat future Social & Groups work as follow-on slices after more of the core play loop is built out.
- Expect a later communication-focused slice to move beyond room-local `SAY` semantics and introduce explicit speech-mode plus audience-scope handling for features such as tells, whispers, shouts, and map/region-aware propagation.
