package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.ObjectMapper;

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
        22L,
        "demo-world",
        List.of(scopedRealm(caller, "demo-world", "scope-secret", expiry)));

    assertThat(store.publicProductionScope(caller, 22L, "demo-world", now))
        .hasValueSatisfying(scope -> assertThat(scope.connectScopeId()).isEqualTo("scope-secret"));
    assertThat(store.publicProductionScope(session(8L, 41L), 22L, "demo-world", now)).isEmpty();
    assertThat(store.publicProductionScope(session(7L, 42L), 22L, "demo-world", now)).isEmpty();
  }

  @Test
  void refusesExpiredAndAmbiguousPublicScopes() {
    SessionContext caller = session(7L, 41L);
    Instant now = Instant.now();
    store.replaceWorldScopes(
        caller,
        "demo-world",
        22L,
        "demo-world",
        List.of(scopedRealm(caller, "production", "expired-scope", now.minusSeconds(1))));

    assertThat(store.publicProductionScope(caller, 22L, "demo-world", now)).isEmpty();

    store.replaceWorldScopes(
        caller,
        "demo-world",
        22L,
        "demo-world",
        List.of(
            scopedRealm(caller, "production-a", "scope-a", now.plusSeconds(3600)),
            scopedRealm(caller, "production-b", "scope-b", now.plusSeconds(3600))));

    assertThat(store.publicProductionScope(caller, 22L, "demo-world", now)).isEmpty();
  }

  @Test
  void clearsPriorScopeBeforeReplacingTheWorldBinding() {
    SessionContext caller = session(7L, 41L);
    Instant now = Instant.now();
    store.replaceWorldScopes(
        caller,
        "1",
        22L,
        "demo-world",
        List.of(scopedRealm(caller, "production", "scope-secret", now.plusSeconds(3600))));

    store.clearWorldScopes(caller, 22L, "demo-world");

    assertThat(store.publicProductionScope(caller, 22L, "demo-world", now)).isEmpty();
  }

  @Test
  void reusesJoinRequestIdForScopeRetryAndStartsANewIdAfterFreshRealms() {
    SessionContext caller = session(7L, 41L);
    Instant now = Instant.now();
    Instant expiry = now.plusSeconds(3600);
    store.replaceRealmSnapshot(
        caller,
        "demo-world",
        22L,
        "demo-world",
        "realms-catalog",
        List.of(),
        List.of(scopedRealm(caller, "production", "scope-first", expiry)),
        now);

    DirectTextConnectScopeSessionStore.JoinScope firstAttempt =
        store
            .publicProductionScopeForJoin(caller, "demo-world", 22L, "demo-world", now)
            .orElseThrow();
    DirectTextConnectScopeSessionStore.JoinScope retryAttempt =
        store
            .publicProductionScopeForJoin(caller, "demo-world", 22L, "demo-world", now)
            .orElseThrow();

    assertThat(firstAttempt.requestId()).isEqualTo(retryAttempt.requestId());
    assertThat(firstAttempt.scope().connectScopeId())
        .isEqualTo(retryAttempt.scope().connectScopeId());

    store.clearWorldScopes(caller, 22L, "demo-world");
    store.replaceRealmSnapshot(
        caller,
        "demo-world",
        22L,
        "demo-world",
        "realms-catalog",
        List.of(),
        List.of(scopedRealm(caller, "production", "scope-second", expiry)),
        now);
    DirectTextConnectScopeSessionStore.JoinScope newAttempt =
        store
            .publicProductionScopeForJoin(caller, "demo-world", 22L, "demo-world", now)
            .orElseThrow();

    assertThat(newAttempt.scope().connectScopeId()).isEqualTo("scope-second");
    assertThat(newAttempt.requestId()).isNotEqualTo(firstAttempt.requestId());
  }

  @Test
  void clearingTransportLobbyRemovesScopeAndBoundJoinIdentityBeforeReuse() {
    SessionContext caller = session(7L, 41L);
    Instant now = Instant.now();
    store.replaceRealmSnapshot(
        caller,
        "demo-world",
        22L,
        "demo-world",
        "realms-catalog",
        List.of(),
        List.of(scopedRealm(caller, "production", "old-scope", now.plusSeconds(600))),
        now);
    String oldRequestId =
        store
            .publicProductionScopeForJoin(caller, "demo-world", 22L, "demo-world", now)
            .orElseThrow()
            .requestId();

    store.clearSession(caller.sessionId());

    assertThat(store.publicProductionScopeForJoin(caller, "demo-world", 22L, "demo-world", now))
        .isEmpty();
    assertThat(store.publicProductionScope(caller, 22L, "demo-world", now)).isEmpty();
    store.replaceRealmSnapshot(
        caller,
        "demo-world",
        22L,
        "demo-world",
        "realms-catalog",
        List.of(),
        List.of(scopedRealm(caller, "production", "new-scope", now.plusSeconds(600))),
        now);
    DirectTextConnectScopeSessionStore.JoinScope newAttempt =
        store
            .publicProductionScopeForJoin(caller, "demo-world", 22L, "demo-world", now)
            .orElseThrow();
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

    store.replaceRealmSnapshot(
        caller,
        "1",
        22L,
        "demo-world",
        "realms-catalog",
        List.of(),
        List.of(scopedRealm(caller, "production", "scope-secret", now.plusSeconds(86_400))),
        now);
    DirectTextConnectScopeSessionStore.JoinScope selected =
        store.publicProductionScopeForJoin(caller, "1", 22L, "demo-world", now).orElseThrow();

    assertThat(selected.scope().expiresAt()).isBeforeOrEqualTo(now.plusSeconds(901));
    assertThat(selected.scope().playerContext().getAccountId()).isEqualTo("7");
    assertThat(selected.scope().playerContext().getSessionId()).isEqualTo("41");
  }

  @Test
  @SuppressWarnings("unchecked")
  void readsLegacyLobbyJsonAcrossProductionStoreInstances() throws Exception {
    SessionContext caller = session(41L, 7L);
    Instant now = Instant.now();
    long expiresAt = now.plusSeconds(600).toEpochMilli();
    String worldKey = "22:ZGVtby13b3JsZA";
    PlayerExecutionContext playerContext =
        PlayerExecutionContext.newBuilder()
            .setAccountId(Long.toString(caller.accountId()))
            .setSessionId(Long.toString(caller.sessionId()))
            .setTenantId(Long.toString(caller.tenantId()))
            .setRealmId("4c4b57d8-e3a2-48fe-9977-e7df0fdce901")
            .setPlayableStateNamespaceId("42d234a2-7487-4dda-a7e5-a3831214328e")
            .setPlayableStateScope("SHARED")
            .setGameInstanceId("9")
            .build();
    String legacyJson =
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "sessionId", caller.sessionId(),
                    "accountId", caller.accountId(),
                    "worldsExpiresAtEpochMs", expiresAt,
                    "catalogFingerprint", "catalog-fingerprint-v13",
                    "ordinalTargets", List.of(),
                    "scopesByWorld",
                        Map.of(
                            worldKey,
                            List.of(
                                Map.of(
                                    "realmSlug",
                                    "production",
                                    "publicProductionRealm",
                                    true,
                                    "connectScopeId",
                                    "account-connect-scope-legacy",
                                    "expiresAtEpochMs",
                                    expiresAt,
                                    "playerContextBase64",
                                    Base64.getEncoder().encodeToString(playerContext.toByteArray()),
                                    "joinRequestId",
                                    "join-request-already-bound"))),
                    "worldBySelector", Map.of("1", worldKey),
                    "realmsByWorld",
                        Map.of(
                            worldKey,
                            Map.of(
                                "tenantId",
                                22L,
                                "worldSlug",
                                "demo-world",
                                "requestedWorldSelector",
                                "1",
                                "catalogFingerprint",
                                "realm-catalog-fingerprint-v13",
                                "expiresAtEpochMs",
                                expiresAt,
                                "ordinalTargets",
                                List.of()))));
    AtomicReference<String> sharedRedisValue = new AtomicReference<>(legacyJson);
    StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
    ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
    Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    Mockito.when(valueOperations.get(Mockito.anyString()))
        .thenAnswer(invocation -> sharedRedisValue.get());
    Mockito.doAnswer(
            invocation -> {
              Object[] arguments =
                  java.util.Arrays.copyOfRange(
                      invocation.getArguments(), 2, invocation.getArguments().length);
              String expectedValue = "1".equals(arguments[0]) ? (String) arguments[1] : null;
              if (!Objects.equals(sharedRedisValue.get(), expectedValue)) {
                return 0L;
              }
              sharedRedisValue.set("1".equals(arguments[2]) ? (String) arguments[3] : null);
              return 1L;
            })
        .when(redisTemplate)
        .execute(Mockito.any(RedisScript.class), Mockito.anyList(), Mockito.any(Object[].class));

    DirectTextConnectScopeSessionStore firstInstance =
        new DirectTextConnectScopeSessionStore(redisTemplate, new ObjectMapper());
    DirectTextConnectScopeSessionStore replacementInstance =
        new DirectTextConnectScopeSessionStore(redisTemplate, new ObjectMapper());

    assertThat(firstInstance.realmsSnapshot(caller, 22L, "demo-world", now))
        .hasValueSatisfying(
            snapshot -> {
              assertThat(snapshot.tenantId()).isEqualTo(22L);
              assertThat(snapshot.requestedWorldSelector()).isEqualTo("1");
            });
    DirectTextConnectScopeSessionStore.JoinScope selected =
        replacementInstance
            .publicProductionScopeForJoin(caller, "1", 22L, "demo-world", now)
            .orElseThrow();
    assertThat(selected.scope().connectScopeId()).isEqualTo("account-connect-scope-legacy");
    assertThat(selected.scope().playerContext().getAccountId()).isEqualTo("7");
    assertThat(selected.scope().playerContext().getSessionId()).isEqualTo("41");
    assertThat(selected.scope().playerContext().getTenantId()).isEqualTo("22");
    assertThat(selected.requestId()).isEqualTo("join-request-already-bound");
    assertThat(
            firstInstance
                .publicProductionScopeForJoin(caller, "1", 22L, "demo-world", now)
                .orElseThrow()
                .requestId())
        .isEqualTo("join-request-already-bound");
    assertThat(sharedRedisValue.get()).doesNotContain("worldBySelector");
  }

  @Test
  void sameWorldSlugInDifferentTenantsKeepsOrdinalScopesIsolated() {
    SessionContext caller = session(41L, 7L);
    Instant now = Instant.now();
    store.replaceWorldSnapshot(
        caller.sessionId(),
        0L,
        "catalog-fingerprint",
        List.of(
            new DirectTextConnectScopeSessionStore.WorldOrdinalTarget(
                1, "demo", 22L, 3L, "tenant-22-target"),
            new DirectTextConnectScopeSessionStore.WorldOrdinalTarget(
                2, "DEMO", 33L, 4L, "tenant-33-target")),
        now);
    store.replaceRealmSnapshot(
        caller,
        "1",
        22L,
        "demo",
        "tenant-22-catalog",
        List.of(),
        List.of(scopedRealm(caller, 22L, "production", "tenant-22-scope", now.plusSeconds(60))),
        now);
    store.replaceRealmSnapshot(
        caller,
        "2",
        33L,
        "demo",
        "tenant-33-catalog",
        List.of(),
        List.of(scopedRealm(caller, 33L, "production", "tenant-33-scope", now.plusSeconds(60))),
        now);

    assertThat(store.publicProductionScopeForJoin(caller, "1", 22L, "demo", now))
        .hasValueSatisfying(
            selected -> {
              assertThat(selected.scope().connectScopeId()).isEqualTo("tenant-22-scope");
              assertThat(selected.scope().playerContext().getTenantId()).isEqualTo("22");
            });
    assertThat(store.publicProductionScopeForJoin(caller, "2", 33L, "demo", now))
        .hasValueSatisfying(
            selected -> {
              assertThat(selected.scope().connectScopeId()).isEqualTo("tenant-33-scope");
              assertThat(selected.scope().playerContext().getTenantId()).isEqualTo("33");
            });
    assertThat(store.publicProductionScopeForJoin(caller, "2", 22L, "demo", now)).isEmpty();
    assertThat(store.publicProductionScope(caller, "demo", now)).isEmpty();
    assertThat(store.publicProductionScopeForJoin(caller, "demo", now)).isEmpty();
  }

  @Test
  void storesRealmsResponseSnapshotOnlyForTheIssuingAccountAndBeforeExpiry() {
    SessionContext caller = session(41L, 7L);
    Instant now = Instant.now();
    DirectTextConnectScopeSessionStore.RealmOrdinalTarget target =
        new DirectTextConnectScopeSessionStore.RealmOrdinalTarget(
            1, "production", 22L, 29L, 3L, "realm-target-fingerprint");
    store.replaceRealmSnapshot(
        caller, "demo", 22L, "demo", "realm-catalog-fingerprint", List.of(target), List.of(), now);

    assertThat(store.realmsSnapshot(caller, 22L, "demo", now))
        .hasValueSatisfying(
            snapshot -> {
              assertThat(snapshot.worldSlug()).isEqualTo("demo");
              assertThat(snapshot.tenantId()).isEqualTo(22L);
              assertThat(snapshot.requestedWorldSelector()).isEqualTo("demo");
              assertThat(snapshot.catalogFingerprint()).isEqualTo("realm-catalog-fingerprint");
              assertThat(snapshot.ordinalTargets()).containsExactly(target);
              assertThat(snapshot.expiresAt()).isBeforeOrEqualTo(now.plusSeconds(301));
            });
    assertThat(store.realmsSnapshot(session(41L, 8L), 22L, "demo", now)).isEmpty();
    assertThat(store.realmsSnapshot(session(42L, 7L), 22L, "demo", now)).isEmpty();
    assertThat(store.realmsSnapshot(caller, 22L, "demo", now.plusSeconds(301))).isEmpty();
  }

  @Test
  void replacingWorldScopesClearsOnlyThatWorldsRealmsSnapshotAndKeepsJoinScopes() {
    SessionContext caller = session(41L, 7L);
    Instant now = Instant.now();
    store.replaceRealmSnapshot(
        caller, "demo", 22L, "demo", "demo-catalog", List.of(), List.of(), now);
    store.replaceRealmSnapshot(
        caller, "secondary", 22L, "secondary", "secondary-catalog", List.of(), List.of(), now);
    store.replaceRealmSnapshot(
        caller, "demo", 33L, "demo", "other-tenant-catalog", List.of(), List.of(), now);
    store.replaceWorldScopes(
        caller,
        "demo",
        22L,
        "demo",
        List.of(scopedRealm(caller, 22L, "production", "join-scope", now.plusSeconds(60))));

    assertThat(store.realmsSnapshot(caller, 22L, "demo", now)).isEmpty();
    assertThat(store.realmsSnapshot(caller, 22L, "secondary", now))
        .hasValueSatisfying(
            snapshot -> assertThat(snapshot.catalogFingerprint()).isEqualTo("secondary-catalog"));
    assertThat(store.realmsSnapshot(caller, 33L, "demo", now))
        .hasValueSatisfying(
            snapshot ->
                assertThat(snapshot.catalogFingerprint()).isEqualTo("other-tenant-catalog"));
    assertThat(store.publicProductionScopeForJoin(caller, "demo", 22L, "demo", now)).isEmpty();
    assertThat(store.publicProductionScope(caller, 22L, "demo", now))
        .hasValueSatisfying(scope -> assertThat(scope.connectScopeId()).isEqualTo("join-scope"));
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
    return scopedRealm(caller, 22L, realmSlug, scopeId, expiry);
  }

  private static DirectTextConnectScopeSessionStore.ScopedRealm scopedRealm(
      SessionContext caller, long tenantId, String realmSlug, String scopeId, Instant expiry) {
    PlayerExecutionContext playerContext =
        PlayerExecutionContext.newBuilder()
            .setAccountId(Long.toString(caller.accountId()))
            .setSessionId(Long.toString(caller.sessionId()))
            .setTenantId(Long.toString(tenantId))
            .setRealmId("4c4b57d8-e3a2-48fe-9977-e7df0fdce901")
            .setPlayableStateNamespaceId("42d234a2-7487-4dda-a7e5-a3831214328e")
            .setPlayableStateScope("SHARED")
            .setGameInstanceId("9")
            .build();
    return new DirectTextConnectScopeSessionStore.ScopedRealm(
        realmSlug, true, scopeId, expiry, playerContext);
  }
}
