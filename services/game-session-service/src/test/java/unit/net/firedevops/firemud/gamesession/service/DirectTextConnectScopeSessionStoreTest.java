package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.Test;

class DirectTextConnectScopeSessionStoreTest {
  private final DirectTextConnectScopeSessionStore store =
      DirectTextConnectScopeSessionStore.inMemoryForTest();

  @Test
  void resolvesOpaqueScopeOnlyForTheIssuingAccountAndTransportSession() {
    SessionContext caller = session(7L, 41L);
    Instant now = Instant.now();
    Instant expiry = now.plusSeconds(3600);
    store.replaceWorldScopes(
        caller,
        "1",
        "demo-world",
        List.of(scopedRealm(caller, "demo-world", "scope-secret", expiry)));

    assertThat(store.publicProductionScope(caller, "1", now))
        .hasValueSatisfying(scope -> assertThat(scope.connectScopeId()).isEqualTo("scope-secret"));
    assertThat(store.publicProductionScope(session(8L, 41L), "1", now)).isEmpty();
    assertThat(store.publicProductionScope(session(7L, 42L), "1", now)).isEmpty();
  }

  @Test
  void refusesExpiredAndAmbiguousPublicScopes() {
    SessionContext caller = session(7L, 41L);
    Instant now = Instant.now();
    store.replaceWorldScopes(
        caller,
        "demo-world",
        "demo-world",
        List.of(scopedRealm(caller, "production", "expired-scope", now.minusSeconds(1))));

    assertThat(store.publicProductionScope(caller, "demo-world", now)).isEmpty();

    store.replaceWorldScopes(
        caller,
        "demo-world",
        "demo-world",
        List.of(
            scopedRealm(caller, "production-a", "scope-a", now.plusSeconds(3600)),
            scopedRealm(caller, "production-b", "scope-b", now.plusSeconds(3600))));

    assertThat(store.publicProductionScope(caller, "demo-world", now)).isEmpty();
  }

  @Test
  void clearsPriorScopeBeforeReplacingTheWorldBinding() {
    SessionContext caller = session(7L, 41L);
    Instant now = Instant.now();
    store.replaceWorldScopes(
        caller,
        "1",
        "demo-world",
        List.of(scopedRealm(caller, "production", "scope-secret", now.plusSeconds(3600))));

    store.clearWorldScopes(caller, "1");

    assertThat(store.publicProductionScope(caller, "1", now)).isEmpty();
  }

  @Test
  void reusesJoinRequestIdForScopeRetryAndStartsANewIdAfterFreshRealms() {
    SessionContext caller = session(7L, 41L);
    Instant now = Instant.now();
    Instant expiry = now.plusSeconds(3600);
    store.replaceWorldScopes(
        caller,
        "demo-world",
        "demo-world",
        List.of(scopedRealm(caller, "production", "scope-first", expiry)));

    DirectTextConnectScopeSessionStore.JoinScope firstAttempt =
        store.publicProductionScopeForJoin(caller, "demo-world", now).orElseThrow();
    DirectTextConnectScopeSessionStore.JoinScope retryAttempt =
        store.publicProductionScopeForJoin(caller, "demo-world", now).orElseThrow();

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
        store.publicProductionScopeForJoin(caller, "demo-world", now).orElseThrow();

    assertThat(newAttempt.scope().connectScopeId()).isEqualTo("scope-second");
    assertThat(newAttempt.requestId()).isNotEqualTo(firstAttempt.requestId());
  }

  @Test
  void clearingTransportLobbyRemovesScopeAndBoundJoinIdentityBeforeReuse() {
    SessionContext caller = session(7L, 41L);
    Instant now = Instant.now();
    store.replaceWorldScopes(
        caller,
        "demo-world",
        "demo-world",
        List.of(scopedRealm(caller, "production", "old-scope", now.plusSeconds(600))));
    String oldRequestId =
        store.publicProductionScopeForJoin(caller, "demo-world", now).orElseThrow().requestId();

    store.clearSession(caller.sessionId());

    assertThat(store.publicProductionScopeForJoin(caller, "demo-world", now)).isEmpty();
    assertThat(store.publicProductionScope(caller, "demo-world", now)).isEmpty();
    store.replaceWorldScopes(
        caller,
        "demo-world",
        "demo-world",
        List.of(scopedRealm(caller, "production", "new-scope", now.plusSeconds(600))));
    DirectTextConnectScopeSessionStore.JoinScope newAttempt =
        store.publicProductionScopeForJoin(caller, "demo-world", now).orElseThrow();
    assertThat(newAttempt.scope().connectScopeId()).isEqualTo("new-scope");
    assertThat(newAttempt.requestId()).isNotEqualTo(oldRequestId);
  }

  @Test
  void storesExactPublicOrdinalSnapshotAndBoundsAccountScopeExpiry() {
    SessionContext caller = session(41L, 7L);
    Instant now = Instant.now();
    DirectTextConnectScopeSessionStore.WorldOrdinalTarget target =
        new DirectTextConnectScopeSessionStore.WorldOrdinalTarget(
            1, "demo-world", 22L, 13L, "target-fingerprint");
    store.replaceWorldSnapshot(caller.sessionId(), 0L, "catalog-fingerprint", List.of(target), now);

    assertThat(store.worldsSnapshot(caller, now))
        .hasValueSatisfying(
            snapshot -> {
              assertThat(snapshot.catalogFingerprint()).isEqualTo("catalog-fingerprint");
              assertThat(snapshot.ordinalTargets()).containsExactly(target);
              assertThat(snapshot.expiresAt()).isBeforeOrEqualTo(now.plusSeconds(301));
            });

    store.replaceWorldScopes(
        caller,
        "1",
        "demo-world",
        List.of(scopedRealm(caller, "production", "scope-secret", now.plusSeconds(86_400))));
    DirectTextConnectScopeSessionStore.JoinScope selected =
        store.publicProductionScopeForJoin(caller, "1", now).orElseThrow();

    assertThat(selected.scope().expiresAt()).isBeforeOrEqualTo(now.plusSeconds(901));
    assertThat(selected.scope().playerContext().getAccountId()).isEqualTo("7");
    assertThat(selected.scope().playerContext().getSessionId()).isEqualTo("41");
  }

  @Test
  void storesRealmsResponseSnapshotOnlyForTheIssuingAccountAndBeforeExpiry() {
    SessionContext caller = session(41L, 7L);
    Instant now = Instant.now();
    DirectTextConnectScopeSessionStore.RealmOrdinalTarget target =
        new DirectTextConnectScopeSessionStore.RealmOrdinalTarget(
            1, "production", 22L, 29L, 3L, "realm-target-fingerprint");
    store.replaceRealmSnapshot(
        caller, "demo", "demo", "realm-catalog-fingerprint", List.of(target), List.of(), now);

    assertThat(store.realmsSnapshot(caller, "demo", now))
        .hasValueSatisfying(
            snapshot -> {
              assertThat(snapshot.worldSlug()).isEqualTo("demo");
              assertThat(snapshot.catalogFingerprint()).isEqualTo("realm-catalog-fingerprint");
              assertThat(snapshot.ordinalTargets()).containsExactly(target);
              assertThat(snapshot.expiresAt()).isBeforeOrEqualTo(now.plusSeconds(301));
            });
    assertThat(store.realmsSnapshot(session(41L, 8L), "demo", now)).isEmpty();
    assertThat(store.realmsSnapshot(session(42L, 7L), "demo", now)).isEmpty();
    assertThat(store.realmsSnapshot(caller, "demo", now.plusSeconds(301))).isEmpty();
  }

  @Test
  void rejectsInvalidTransportIdsAndScopesBoundToAnotherIdentity() {
    assertThatThrownBy(() -> store.clearSession(0L)).isInstanceOf(IllegalArgumentException.class);

    SessionContext caller = session(7L, 41L);
    DirectTextConnectScopeSessionStore.ScopedRealm mismatchedScope =
        scopedRealm(session(8L, 41L), "production", "scope-secret", Instant.now().plusSeconds(60));
    assertThatThrownBy(
            () -> store.replaceWorldScopes(caller, "demo", "demo", List.of(mismatchedScope)))
        .isInstanceOf(DirectTextConnectScopeSessionStore.ConflictingIdentityException.class);
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
