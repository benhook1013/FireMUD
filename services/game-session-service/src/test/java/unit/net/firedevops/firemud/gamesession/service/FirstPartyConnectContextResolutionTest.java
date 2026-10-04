package unit.net.firedevops.firemud.gamesession.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextResolution;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.SerializationException;

class FirstPartyConnectContextResolutionTest {
  private static final String ACCOUNT_ID = "b8d093f7-cb70-40ed-9fac-3c82d4bf28f1";

  @Test
  void unreadableRetainedContextIsInvalidAndDoesNotUsePersistedFallback() {
    FirstPartyConnectContextRegistry registry = mock(FirstPartyConnectContextRegistry.class);
    when(registry.find(91L)).thenThrow(new SerializationException("old record shape"));

    FirstPartyConnectContextResolution resolution =
        FirstPartyConnectContextResolution.resolve(91L, persistedContext(), registry);

    assertTrue(resolution.invalid());
    assertEquals(Optional.empty(), resolution.connectContext());
  }

  @Test
  void wrongTypeRetainedContextIsInvalidAndDoesNotUsePersistedFallback() {
    FirstPartyConnectContextRegistry registry = mock(FirstPartyConnectContextRegistry.class);
    when(registry.find(91L)).thenThrow(new ClassCastException("incompatible retained value"));

    FirstPartyConnectContextResolution resolution =
        FirstPartyConnectContextResolution.resolve(91L, persistedContext(), registry);

    assertTrue(resolution.invalid());
    assertEquals(Optional.empty(), resolution.connectContext());
  }

  @Test
  void absentRetainedContextUsesCompletePersistedFallback() {
    FirstPartyConnectContextRegistry registry = mock(FirstPartyConnectContextRegistry.class);
    when(registry.find(91L)).thenReturn(Optional.empty());

    FirstPartyConnectContextResolution resolution =
        FirstPartyConnectContextResolution.resolve(91L, persistedContext(), registry);

    assertFalse(resolution.invalid());
    assertEquals(
        Optional.of(
            new FirstPartyConnectContext(
                ACCOUNT_ID, 22L, "demo", "production", 41L, 17L, "scope-1", null, "req-1", null)),
        resolution.connectContext());
  }

  private static SessionContext persistedContext() {
    return new SessionContext(
        91L,
        22L,
        ACCOUNT_ID,
        "first-party:91",
        0L,
        null,
        0L,
        null,
        null,
        null,
        41L,
        "demo",
        "production",
        17L,
        null,
        "scope-1",
        "req-1");
  }
}
