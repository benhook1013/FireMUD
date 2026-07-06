package net.firedevops.firemud.gamesession.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SessionContextTest {

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
            "room-1",
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
}
