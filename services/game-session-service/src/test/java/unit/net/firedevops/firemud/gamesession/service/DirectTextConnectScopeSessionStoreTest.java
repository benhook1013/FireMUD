package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.Test;

class DirectTextConnectScopeSessionStoreTest {
  private final DirectTextConnectScopeSessionStore store = new DirectTextConnectScopeSessionStore();

  @Test
  void resolvesOpaqueScopeOnlyForTheIssuingAccountAndTransportSession() {
    SessionContext caller = session(7L, 41L);
    Instant expiry = Instant.parse("2030-01-01T00:00:00Z");
    store.replaceWorldScopes(
        caller,
        "1",
        "demo-world",
        List.of(scopedRealm(caller, "demo-world", "scope-secret", expiry)));

    assertThat(store.publicProductionScope(caller, "1", Instant.parse("2029-01-01T00:00:00Z")))
        .hasValueSatisfying(scope -> assertThat(scope.connectScopeId()).isEqualTo("scope-secret"));
    assertThat(
            store.publicProductionScope(
                session(8L, 41L), "1", Instant.parse("2029-01-01T00:00:00Z")))
        .isEmpty();
    assertThat(
            store.publicProductionScope(
                session(7L, 42L), "1", Instant.parse("2029-01-01T00:00:00Z")))
        .isEmpty();
  }

  @Test
  void refusesExpiredAndAmbiguousPublicScopes() {
    SessionContext caller = session(7L, 41L);
    store.replaceWorldScopes(
        caller,
        "demo-world",
        "demo-world",
        List.of(
            scopedRealm(
                caller, "production", "expired-scope", Instant.parse("2029-01-01T00:00:00Z"))));

    assertThat(
            store.publicProductionScope(
                caller, "demo-world", Instant.parse("2030-01-01T00:00:00Z")))
        .isEmpty();

    store.replaceWorldScopes(
        caller,
        "demo-world",
        "demo-world",
        List.of(
            scopedRealm(caller, "production-a", "scope-a", Instant.parse("2031-01-01T00:00:00Z")),
            scopedRealm(caller, "production-b", "scope-b", Instant.parse("2031-01-01T00:00:00Z"))));

    assertThat(
            store.publicProductionScope(
                caller, "demo-world", Instant.parse("2030-01-01T00:00:00Z")))
        .isEmpty();
  }

  @Test
  void clearsPriorScopeBeforeReplacingTheWorldBinding() {
    SessionContext caller = session(7L, 41L);
    store.replaceWorldScopes(
        caller,
        "1",
        "demo-world",
        List.of(
            scopedRealm(
                caller, "production", "scope-secret", Instant.parse("2031-01-01T00:00:00Z"))));

    store.clearWorldScopes(caller, "1");

    assertThat(store.publicProductionScope(caller, "1", Instant.parse("2030-01-01T00:00:00Z")))
        .isEmpty();
  }

  @Test
  void reusesJoinRequestIdForScopeRetryAndStartsANewIdAfterFreshRealms() {
    SessionContext caller = session(7L, 41L);
    Instant expiry = Instant.parse("2031-01-01T00:00:00Z");
    store.replaceWorldScopes(
        caller,
        "demo-world",
        "demo-world",
        List.of(scopedRealm(caller, "production", "scope-first", expiry)));

    DirectTextConnectScopeSessionStore.JoinScope firstAttempt =
        store
            .publicProductionScopeForJoin(
                caller, "demo-world", Instant.parse("2030-01-01T00:00:00Z"))
            .orElseThrow();
    DirectTextConnectScopeSessionStore.JoinScope retryAttempt =
        store
            .publicProductionScopeForJoin(
                caller, "demo-world", Instant.parse("2030-01-01T00:00:00Z"))
            .orElseThrow();

    assertThat(firstAttempt.requestId()).isEqualTo(retryAttempt.requestId());
    assertThat(firstAttempt.scope().connectScopeId())
        .isEqualTo(retryAttempt.scope().connectScopeId());

    store.clearWorldScopes(caller, "demo-world");
    store.replaceWorldScopes(
        caller,
        "demo-world",
        "demo-world",
        List.of(scopedRealm(caller, "production", "scope-second", expiry)));
    DirectTextConnectScopeSessionStore.JoinScope newAttempt =
        store
            .publicProductionScopeForJoin(
                caller, "demo-world", Instant.parse("2030-01-01T00:00:00Z"))
            .orElseThrow();

    assertThat(newAttempt.scope().connectScopeId()).isEqualTo("scope-second");
    assertThat(newAttempt.requestId()).isNotEqualTo(firstAttempt.requestId());
  }

  private static SessionContext session(long sessionId, long accountId) {
    return new SessionContext(sessionId, 22L, accountId, 7001L, 9L, "jwt");
  }

  private static DirectTextConnectScopeSessionStore.ScopedRealm scopedRealm(
      SessionContext caller, String realmSlug, String scopeId, Instant expiry) {
    PlayerExecutionContext playerContext =
        PlayerExecutionContext.newBuilder()
            .setAccountId(Long.toString(caller.accountId()))
            .setSessionId(Long.toString(caller.sessionId()))
            .setTenantId("22")
            .setRealmId("4c4b57d8-e3a2-48fe-9977-e7df0fdce901")
            .setPlayableStateNamespaceId("42d234a2-7487-4dda-a7e5-a3831214328e")
            .setPlayableStateScope("SHARED")
            .setGameInstanceId("9")
            .build();
    return new DirectTextConnectScopeSessionStore.ScopedRealm(
        realmSlug, true, scopeId, expiry, playerContext);
  }
}
