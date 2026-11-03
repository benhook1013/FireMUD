package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Claims;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository.CanonicalConnectScopeEvidence;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.service.AccountCanonicalConnectScopeReader;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountCanonicalConnectScopeReaderTest {
  private static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID REALM = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID NAMESPACE = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID GAME_INSTANCE = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID OPERATION = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final String SECRET = "controlled-account-canonical-connect-scope-key-0123456789";
  private static final String PROVENANCE_DIGEST = "sha256:" + "a".repeat(64);
  private static final long TOKEN_LIFETIME_MILLIS = 300_000L;

  @AfterEach
  void clearThreadContexts() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void returnsExactSignedV2SelectorOnlyAfterMatchingAccountHashAndProvenanceReadback() {
    Fixture f = new Fixture();
    SessionContext.setContext(
        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        List.of("tenantAdmin"),
        Map.of(TENANT.toString(), List.of("tenantAdmin")));
    String originalSessionAccount = SessionContext.getAccountId();

    CanonicalJoinScopeV2 actual = f.reader.read(ACCOUNT, f.scope.connectScopeId());

    assertThat(actual).isEqualTo(f.scope);
    assertThat(f.clock.instant()).isAfter(Instant.parse(f.scope.evaluatedAt()));
    assertThat(f.jwt.parseToken(f.scope.connectScopeId()).getPayload().getIssuedAt().toInstant())
        .isAfter(Instant.parse(f.scope.evaluatedAt()));
    assertThat(SessionContext.getAccountId()).isEqualTo(originalSessionAccount);
    assertThat(SessionContext.getScopedRoles(TENANT.toString())).containsExactly("tenantAdmin");
    assertThat(f.transactions.started).isEqualTo(1);
    assertThat(f.transactions.commits).isEqualTo(1);
    assertThat(f.transactions.rollbacks).isZero();
    verify(f.scopes).findCanonical(f.scope.connectScopeId());
    verify(f.scopes)
        .findCanonicalEvidenceByTokenHash(AccountJoinDigest.tokenHash(f.scope.connectScopeId()));
  }

  @Test
  void refusesWrongExpectedUuidBeforeReadingAccountStorage() {
    Fixture f = new Fixture();

    assertCode(
        "CONNECT_SCOPE_INVALID",
        () ->
            f.reader.read(
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), f.scope.connectScopeId()));

    assertThat(f.transactions.started).isZero();
    verifyNoInteractions(f.scopes);
  }

  @Test
  void refusesMissingOrMalformedSignedProfileBeforeAccountRead() {
    Fixture f = new Fixture();

    assertCode("CONNECT_SCOPE_INVALID", () -> f.reader.read(ACCOUNT, "not-a-jwt"));
    assertCode(
        "CONNECT_SCOPE_INVALID",
        () -> f.reader.read(ACCOUNT, f.issue(claims -> claims.put("aud", "player-bootstrap"))));
    assertCode(
        "CONNECT_SCOPE_INVALID",
        () ->
            f.reader.read(
                ACCOUNT,
                f.issue(
                    claims ->
                        claims.put(
                            "aud", List.of("bootstrap-connect-scope", "player-bootstrap")))));
    assertCode(
        "CONNECT_SCOPE_INVALID",
        () -> f.reader.read(ACCOUNT, f.issue(claims -> claims.put("targetClass", "PRIVATE"))));
    assertCode(
        "CONNECT_SCOPE_INVALID",
        () -> f.reader.read(ACCOUNT, f.issue(claims -> claims.put("scopeDigestVersion", 1L))));
    assertCode(
        "CONNECT_SCOPE_INVALID",
        () -> f.reader.read(ACCOUNT, f.issue(claims -> claims.put("accountId", "123"))));
    assertThat(f.transactions.started).isZero();
    verifyNoInteractions(f.scopes);
  }

  @Test
  void rejectsNumericTenantFallbackAndNoncanonicalOrNilUuidClaims() {
    Fixture f = new Fixture();

    assertCode(
        "CONNECT_SCOPE_INVALID",
        () -> f.reader.read(ACCOUNT, f.issue(claims -> claims.put("tenantId", 42L))));
    assertCode(
        "CONNECT_SCOPE_INVALID",
        () ->
            f.reader.read(
                ACCOUNT,
                f.issue(claims -> claims.put("tenantId", "22222222-2222-4222-8222-22222222222"))));
    assertCode(
        "CONNECT_SCOPE_INVALID",
        () ->
            f.reader.read(
                ACCOUNT,
                f.issue(claims -> claims.put("realmId", "00000000-0000-0000-0000-000000000000"))));
    assertThat(f.transactions.started).isZero();
    verifyNoInteractions(f.scopes);
  }

  @Test
  void rejectsInvalidRuntimeSelectorsAndNoncanonicalTimeClaims() {
    Fixture f = new Fixture();

    assertCode(
        "CONNECT_SCOPE_INVALID",
        () -> f.reader.read(ACCOUNT, f.issue(claims -> claims.put("catalogRevision", 0L))));
    assertCode(
        "CONNECT_SCOPE_INVALID",
        () -> f.reader.read(ACCOUNT, f.issue(claims -> claims.put("pointerVersion", "9"))));
    assertCode(
        "CONNECT_SCOPE_INVALID",
        () ->
            f.reader.read(ACCOUNT, f.issue(claims -> claims.put("playableStateScope", "shared"))));
    assertCode(
        "CONNECT_SCOPE_INVALID",
        () ->
            f.reader.read(
                ACCOUNT,
                f.issue(claims -> claims.put("evaluatedAt", "2026-10-03T01:02:03+00:00"))));
    assertThat(f.transactions.started).isZero();
    verifyNoInteractions(f.scopes);
  }

  @Test
  void rejectsFutureEvaluationExpiredScopeAndJwtExpiryContradiction() {
    Fixture f = new Fixture();

    assertCode(
        "CONNECT_SCOPE_INVALID",
        () ->
            f.reader.read(
                ACCOUNT,
                f.issue(
                    claims ->
                        claims.put("evaluatedAt", Instant.now().plusSeconds(30).toString()))));
    assertCode(
        "CONNECT_SCOPE_INVALID",
        () ->
            new AccountCanonicalConnectScopeReader(
                    f.jwt,
                    f.scopes,
                    new RecordingTransactions(),
                    Clock.offset(f.clock, Duration.ofMinutes(6)))
                .read(ACCOUNT, f.scope.connectScopeId()));
    assertCode(
        "CONNECT_SCOPE_INVALID",
        () ->
            f.reader.read(
                ACCOUNT,
                f.issue(
                    claims ->
                        claims.put(
                            "connectScopeExpiresAt",
                            Instant.parse((String) claims.get("connectScopeExpiresAt"))
                                .plusSeconds(1)
                                .toString()))));
    assertThat(f.transactions.started).isZero();
    verifyNoInteractions(f.scopes);
  }

  @Test
  void refusesAbsentScopeAndMismatchWithImmutablePersistedV2Evidence() {
    Fixture f = new Fixture();
    when(f.scopes.findCanonical(f.scope.connectScopeId())).thenReturn(Optional.empty());
    assertCode("CONNECT_SCOPE_INVALID", () -> f.reader.read(ACCOUNT, f.scope.connectScopeId()));
    assertThat(f.transactions.rollbacks).isEqualTo(1);

    Fixture changed = new Fixture();
    CanonicalJoinScopeV2 different = changed.copyWithWorldSlug("other-world");
    when(changed.scopes.findCanonical(changed.scope.connectScopeId()))
        .thenReturn(Optional.of(different));
    assertCode(
        "CONNECT_SCOPE_INVALID",
        () -> changed.reader.read(ACCOUNT, changed.scope.connectScopeId()));
    assertThat(changed.transactions.rollbacks).isEqualTo(1);

    Fixture wrongDigest = new Fixture();
    CanonicalConnectScopeEvidence validEvidence = evidence(wrongDigest.scope);
    when(wrongDigest.scopes.findCanonicalEvidenceByTokenHash(
            AccountJoinDigest.tokenHash(wrongDigest.scope.connectScopeId())))
        .thenReturn(Optional.of(withDigest(validEvidence, "sha256:" + "b".repeat(64))));
    assertCode(
        "CONNECT_SCOPE_INVALID",
        () -> wrongDigest.reader.read(ACCOUNT, wrongDigest.scope.connectScopeId()));
    assertThat(wrongDigest.transactions.rollbacks).isEqualTo(1);
  }

  @Test
  void mapsDatabaseFailureAndCommitFailureToSafeAuthorityUnavailable() {
    Fixture readFailure = new Fixture();
    when(readFailure.scopes.findCanonical(readFailure.scope.connectScopeId()))
        .thenThrow(new org.jooq.exception.DataAccessException("controlled storage failure"));
    assertCode(
        "AUTH_UNAVAILABLE",
        () -> readFailure.reader.read(ACCOUNT, readFailure.scope.connectScopeId()));
    assertThat(readFailure.transactions.rollbacks).isEqualTo(1);

    Fixture commitFailure = new Fixture();
    commitFailure.transactions.failCommit = true;
    assertCode(
        "AUTH_UNAVAILABLE",
        () -> commitFailure.reader.read(ACCOUNT, commitFailure.scope.connectScopeId()));

    Fixture rollbackFailure = new Fixture();
    when(rollbackFailure.scopes.findCanonical(rollbackFailure.scope.connectScopeId()))
        .thenReturn(Optional.empty());
    rollbackFailure.transactions.failRollback = true;
    assertCode(
        "AUTH_UNAVAILABLE",
        () -> rollbackFailure.reader.read(ACCOUNT, rollbackFailure.scope.connectScopeId()));
  }

  @Test
  void refusesAmbientTransactionWithoutChangingCallerSessionContext() {
    Fixture f = new Fixture();
    SessionContext.setContext(ACCOUNT.toString(), List.of("player"), Map.of());
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> f.reader.read(ACCOUNT, f.scope.connectScopeId()))
        .isInstanceOf(IllegalStateException.class);

    assertThat(SessionContext.getAccountId()).isEqualTo(ACCOUNT.toString());
    assertThat(f.transactions.started).isZero();
    verifyNoInteractions(f.scopes);
  }

  private static void assertCode(String expected, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(AuthenticationException.class)
        .satisfies(
            failure -> {
              AuthenticationException authentication = (AuthenticationException) failure;
              assertThat(authentication.getCode()).isEqualTo(expected);
              assertThat(authentication.getMessage()).doesNotContain("eyJ");
            });
  }

  private static CanonicalJoinScopeV2 scope(String token, String evaluatedAt, String expiresAt) {
    return new CanonicalJoinScopeV2(
        token,
        ACCOUNT,
        TENANT,
        REALM,
        "acme-worlds",
        "demo",
        "main",
        NAMESPACE,
        "SHARED",
        GAME_INSTANCE,
        9L,
        13L,
        evaluatedAt,
        expiresAt);
  }

  private static CanonicalJoinScopeV2 copyWithWorldSlug(
      CanonicalJoinScopeV2 original, String world) {
    return new CanonicalJoinScopeV2(
        original.connectScopeId(),
        original.accountId(),
        original.tenantId(),
        original.realmId(),
        original.tenantSlug(),
        world,
        original.realmSlug(),
        original.playableStateNamespaceId(),
        original.playableStateScope(),
        original.gameInstanceId(),
        original.catalogRevision(),
        original.pointerVersion(),
        original.evaluatedAt(),
        original.connectScopeExpiresAt());
  }

  private static final class Fixture {
    private final Clock clock;
    private final JwtUtil jwt = new JwtUtil(SECRET, TOKEN_LIFETIME_MILLIS);
    private final AccountConnectScopeRepository scopes =
        Mockito.mock(AccountConnectScopeRepository.class);
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final AccountCanonicalConnectScopeReader reader;
    private final CanonicalJoinScopeV2 scope;

    private Fixture() {
      Token token = AccountCanonicalConnectScopeReaderTest.issue(jwt, claims -> {});
      Claims claims = jwt.parseToken(token.value()).getPayload();
      String expiresAt = claims.get("connectScopeExpiresAt", String.class);
      clock =
          Clock.fixed(
              Instant.parse(claims.get("evaluatedAt", String.class)).plusSeconds(1),
              ZoneOffset.UTC);
      scope = scope(token.value(), claims.get("evaluatedAt", String.class), expiresAt);
      when(scopes.findCanonical(token.value())).thenReturn(Optional.of(scope));
      when(scopes.findCanonicalEvidenceByTokenHash(AccountJoinDigest.tokenHash(token.value())))
          .thenReturn(Optional.of(evidence(scope)));
      reader = new AccountCanonicalConnectScopeReader(jwt, scopes, transactions, clock);
    }

    private String issue(Consumer<Map<String, Object>> mutation) {
      return AccountCanonicalConnectScopeReaderTest.issue(jwt, mutation).value();
    }

    private CanonicalJoinScopeV2 copyWithWorldSlug(String world) {
      return AccountCanonicalConnectScopeReaderTest.copyWithWorldSlug(scope, world);
    }
  }

  private static Token issue(JwtUtil jwt, Consumer<Map<String, Object>> mutation) {
    for (int attempt = 0; attempt < 100; attempt++) {
      long before = System.currentTimeMillis();
      Instant expiresAt =
          Instant.ofEpochMilli(before)
              .truncatedTo(ChronoUnit.SECONDS)
              .plusMillis(TOKEN_LIFETIME_MILLIS);
      Map<String, Object> claims = new LinkedHashMap<>();
      claims.put("aud", "bootstrap-connect-scope");
      claims.put("accountId", ACCOUNT.toString());
      claims.put("targetClass", "PUBLIC_PRODUCTION");
      claims.put("scopeDigestVersion", 2L);
      claims.put("tenantId", TENANT.toString());
      claims.put("tenantSlug", "acme-worlds");
      claims.put("worldSlug", "demo");
      claims.put("realmId", REALM.toString());
      claims.put("realmSlug", "main");
      claims.put("playableStateNamespaceId", NAMESPACE.toString());
      claims.put("playableStateScope", "SHARED");
      claims.put("gameInstanceId", GAME_INSTANCE.toString());
      claims.put("catalogRevision", 9L);
      claims.put("pointerVersion", 13L);
      claims.put("evaluatedAt", Instant.ofEpochMilli(before - 2_000L).toString());
      claims.put("connectScopeExpiresAt", expiresAt.toString());
      claims.put("jti", UUID.randomUUID().toString());
      mutation.accept(claims);
      String value =
          jwt.generateToken(
              ACCOUNT.toString(), expiresAt.toEpochMilli() - System.currentTimeMillis(), claims);
      Instant actualExpiry = jwt.parseToken(value).getPayload().getExpiration().toInstant();
      if (expiresAt.equals(actualExpiry)) return new Token(value);
    }
    throw new IllegalStateException("Could not create exact test JWT expiry");
  }

  private static CanonicalConnectScopeEvidence evidence(CanonicalJoinScopeV2 scope) {
    return new CanonicalConnectScopeEvidence(
        AccountJoinDigest.tokenHash(scope.connectScopeId()),
        41L,
        "PUBLIC_PRODUCTION",
        scope.accountId(),
        scope.tenantId(),
        scope.realmId(),
        scope.tenantSlug(),
        scope.worldSlug(),
        scope.realmSlug(),
        scope.playableStateNamespaceId(),
        scope.playableStateScope(),
        scope.gameInstanceId(),
        scope.catalogRevision(),
        scope.pointerVersion(),
        scope.evaluatedAt(),
        scope.connectScopeExpiresAt(),
        2,
        AccountJoinDigest.scopeV2(scope),
        new VerifiedTenantProvenance(
            null, TenantProvenanceKind.FRESH_GAME_DESIGN, OPERATION, PROVENANCE_DIGEST));
  }

  private static CanonicalConnectScopeEvidence withDigest(
      CanonicalConnectScopeEvidence evidence, String digest) {
    return new CanonicalConnectScopeEvidence(
        evidence.scopeTokenHash(),
        evidence.privateAccountId(),
        evidence.targetClass(),
        evidence.accountUuid(),
        evidence.tenantUuid(),
        evidence.realmId(),
        evidence.tenantSlug(),
        evidence.worldSlug(),
        evidence.realmSlug(),
        evidence.playableStateNamespaceUuid(),
        evidence.playableStateScope(),
        evidence.gameInstanceUuid(),
        evidence.catalogRevision(),
        evidence.pointerVersion(),
        evidence.evaluatedAt(),
        evidence.connectScopeExpiresAt(),
        evidence.scopeDigestVersion(),
        digest,
        evidence.tenantProvenance());
  }

  private record Token(String value) {}

  private static final class RecordingTransactions implements PlatformTransactionManager {
    private int started;
    private int commits;
    private int rollbacks;
    private boolean failCommit;
    private boolean failRollback;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      TransactionDefinition actual =
          definition == null ? TransactionDefinition.withDefaults() : definition;
      assertThat(actual.getPropagationBehavior())
          .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      assertThat(actual.getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
      assertThat(actual.getTimeout()).isEqualTo(5);
      assertThat(actual.isReadOnly()).isFalse();
      started++;
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      try {
        if (failCommit) throw new IllegalStateException("controlled commit failure");
        commits++;
      } finally {
        clearTransaction();
      }
    }

    @Override
    public void rollback(TransactionStatus status) {
      rollbacks++;
      try {
        if (failRollback) throw new IllegalStateException("controlled rollback failure");
      } finally {
        clearTransaction();
      }
    }

    private static void clearTransaction() {
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }
  }
}
