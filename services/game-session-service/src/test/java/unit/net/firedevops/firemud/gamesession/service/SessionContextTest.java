package net.firedevops.firemud.gamesession.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SessionContextTest {

  @Test
  void preservesLegacyAndSyntheticRuntimeRoomIdsVerbatim() {
    SessionContext legacyNumeric =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 41L, "1", "jwt");
    SessionContext legacyPrefixed =
        new SessionContext(
            1L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 41L, "room-1", "jwt");
    SessionContext legacyUppercasePrefixed =
        new SessionContext(
            1L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 41L, "ROOM-1", "jwt");
    SessionContext syntheticProbe =
        new SessionContext(
            1L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 41L, "readiness-room-1", "jwt");

    assertEquals("1", legacyNumeric.roomInstanceId());
    assertEquals("room-1", legacyPrefixed.roomInstanceId());
    assertEquals("ROOM-1", legacyUppercasePrefixed.roomInstanceId());
    assertEquals("readiness-room-1", syntheticProbe.roomInstanceId());
  }

  @Test
  void hasPartialPersistedFirstPartyConnectContextDetectsMissingConnectRequest() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            123L,
            "first-party:123",
            0L,
            null,
            0L,
            null,
            null,
            null,
            41L,
            "sandbox",
            "preview",
            1L,
            null,
            "scope-persisted",
            null);

    assertTrue(context.hasPartialPersistedFirstPartyConnectContext());
    assertTrue(context.persistedFirstPartyConnectContext().isEmpty());
  }

  @Test
  void hasPartialPersistedFirstPartyConnectContextIgnoresStandardGameplaySession() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            123L,
            "demo@example.com",
            7001L,
            "Emberline",
            41L,
            "R-1",
            "jwt",
            null,
            41L,
            "sandbox",
            "preview",
            1L,
            "ISOLATED",
            null,
            null);

    assertFalse(context.hasPartialPersistedFirstPartyConnectContext());
  }

  @Test
  void hasPartialPersistedFirstPartyConnectContextDetectsMissingConnectScope() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            123L,
            "first-party:123",
            0L,
            null,
            0L,
            null,
            null,
            null,
            41L,
            "sandbox",
            "preview",
            1L,
            null,
            null,
            "request-123");

    assertTrue(context.hasPartialPersistedFirstPartyConnectContext());
    assertTrue(context.persistedFirstPartyConnectContext().isEmpty());
  }

  @Test
  void hasPartialPersistedFirstPartyConnectContextRequiresPositiveAccountIdentity() {
    SessionContext context =
        new SessionContext(
            1L,
            22L,
            0L,
            "first-party:123",
            0L,
            null,
            0L,
            null,
            null,
            null,
            41L,
            "sandbox",
            "preview",
            1L,
            null,
            "scope-persisted",
            "request-123");

    assertFalse(context.hasPartialPersistedFirstPartyConnectContext());
    assertTrue(context.persistedFirstPartyConnectContext().isEmpty());
  }

  @Test
  void hasGameplayRegionBindingRequiresGameInstanceCharacterAndRoom() {
    SessionContext complete =
        new SessionContext(
            1L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 41L, "R-1", "jwt");
    SessionContext missingRoom =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 41L, null, "jwt");
    SessionContext missingCharacter =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 41L, "R-1", "jwt");

    assertTrue(complete.hasGameplayRegionBinding());
    assertFalse(missingRoom.hasGameplayRegionBinding());
    assertFalse(missingCharacter.hasGameplayRegionBinding());
  }

  @Test
  void hasGameplayBindingTreatsPartialGameplayShellsAsBound() {
    SessionContext gameOnly =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 41L, null, "jwt");
    SessionContext characterOnly =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 0L, null, "jwt");
    SessionContext roomOnly =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, "R-1", "jwt");
    SessionContext blank =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 0L, null, "jwt");

    assertTrue(gameOnly.hasGameplayBinding());
    assertTrue(characterOnly.hasGameplayBinding());
    assertTrue(roomOnly.hasGameplayBinding());
    assertFalse(blank.hasGameplayBinding());
  }

  @Test
  void hasGameplayIdentityRequiresPositiveGameInstanceAndCharacter() {
    SessionContext complete =
        new SessionContext(
            1L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 41L, "R-1", "jwt");
    SessionContext missingGameInstance =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 7001L, "Emberline", 0L, "R-1", "jwt");
    SessionContext missingCharacter =
        new SessionContext(1L, 22L, 123L, "demo@example.com", 0L, null, 41L, "R-1", "jwt");

    assertTrue(complete.hasGameplayIdentity());
    assertFalse(missingGameInstance.hasGameplayIdentity());
    assertFalse(missingCharacter.hasGameplayIdentity());
  }
}
