package integration.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.mkammerer.argon2.Argon2Factory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationEvidence;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationPolicy;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationPolicyRepository;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationRepository;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationService;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationService.Disposition;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual primary authentication, Account repositories, policy sources and evidence use PostgreSQL.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountRecentReauthenticationPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private static final String PASSWORD = "test-only-recent-reauth-password";
  private static final String OTP = "test-only-recent-reauth-otp";
  private static final Instant BASE = Instant.parse("2026-11-01T00:00:00Z");

  @Test
  void actualPasswordProducerPersistsExactImmutableAccountOperationAndPolicyEvidence() {
    Context c = context();
    Account account = c.account("PASSWORD");
    UUID operationId = UUID.randomUUID();
    var request = c.request(account, UUID.randomUUID(), operationId, PASSWORD);

    var evidence = c.service.reauthenticate(request);
    Record row = c.evidenceRow(evidence.evidenceId());
    String payload =
        new String(
            row.get("request_payload", byte[].class), java.nio.charset.StandardCharsets.UTF_8);

    assertThat(evidence.accountId()).isEqualTo(account.getAccountUuid());
    assertThat(evidence.creationOperationId()).isEqualTo(operationId);
    assertThat(evidence.identitySourceNumericId()).isEqualTo(account.getId());
    assertThat(evidence.accountAuthorityGeneration()).isPositive();
    assertThat(evidence.accountAuthoritySourceVersion()).isPositive();
    assertThat(evidence.issuanceFence()).isPositive();
    assertThat(evidence.issuanceFenceSourceVersion()).isPositive();
    assertThat(evidence.platformPolicyVersion()).isEqualTo(1L);
    assertThat(evidence.deploymentPolicyVersion()).isEqualTo(1L);
    assertThat(evidence.recentAgeSeconds()).isEqualTo(300L);
    assertThat(evidence.maxClockSkewSeconds()).isEqualTo(30L);
    assertThat(payload).contains(account.getAccountUuid().toString(), evidence.purpose());
    assertThat(payload).doesNotContain(PASSWORD, account.getEmail());
    assertThat(request.toString()).doesNotContain(PASSWORD, account.getEmail());
    assertThat(row.get("request_digest", String.class)).isEqualTo(evidence.requestDigest());
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_evidence"))
        .isEqualTo(1L);
  }

  @Test
  void postgresMicrosecondTimestampReadbackPreservesOriginalAndExactReplay() {
    Context c = context();
    Account account = c.account("PASSWORD");
    Instant sourceInstant = BASE.plusNanos(123_456_789L);
    Instant persistedInstant = sourceInstant.truncatedTo(ChronoUnit.MICROS);
    c.clock.set(sourceInstant);
    var request = c.request(account, UUID.randomUUID(), UUID.randomUUID(), PASSWORD);

    var original = c.service.reauthenticate(request);
    Record row = c.evidenceRow(original.evidenceId());
    var replay = c.service.reauthenticate(request);

    assertThat(original.issuedAt()).isEqualTo(persistedInstant);
    assertThat(row.get("issued_at", java.time.OffsetDateTime.class).toInstant())
        .isEqualTo(persistedInstant);
    assertThat(replay.evidenceId()).isEqualTo(original.evidenceId());
    assertThat(replay.issuedAt()).isEqualTo(persistedInstant);
  }

  @Test
  void nonV4RequestAndCreationIdsAreAcceptedForPersistedAccountIdentity() {
    Context c = context();
    Account account = c.account("PASSWORD");
    UUID requestId = new UUID(1L, 1L);
    UUID operationId = new UUID(2L, 2L);
    assertThat(requestId.version()).isNotEqualTo(4);
    assertThat(operationId.version()).isNotEqualTo(4);
    var request = c.request(account, requestId, operationId, PASSWORD);

    var original = c.service.reauthenticate(request);
    var replay = c.service.reauthenticate(request);

    assertThat(original.requestId()).isEqualTo(requestId);
    assertThat(original.creationOperationId()).isEqualTo(operationId);
    assertThat(replay.evidenceId()).isEqualTo(original.evidenceId());
  }

  @Test
  void missingCurrentPolicyFailsBeforeValidOtpIsConsumedOrEvidenceIsRetained() {
    Context c = context(false);
    assertThat(
            c.count(
                "SELECT count(*) FROM account_draft_authorization_source_locks "
                    + "WHERE source_key LIKE 'RECENT_REAUTH_POLICY:%'"))
        .isZero();
    Account account = c.account("EMAIL_OTP");
    AccountEmailLoginChallenge challenge = c.challenge(account);
    var request = c.request(account, UUID.randomUUID(), UUID.randomUUID(), OTP);

    assertThatThrownBy(() -> c.service.reauthenticate(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Current Account reauthentication policy is unavailable");

    assertThat(c.challenges.findByAccountId(account.getId())).contains(challenge);
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_evidence")).isZero();
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_consumptions"))
        .isZero();
  }

  @Test
  void currentPolicyResolutionReturnsExactSortedValuesFromBothCurrentPointers() {
    Context c = context();
    c.tx(
        () -> {
          c.dsl.execute(
              "INSERT INTO account_recent_reauthentication_policy_values "
                  + "(policy_scope, policy_version, recent_age_seconds, max_clock_skew_seconds) "
                  + "VALUES ('DEPLOYMENT', 2, 100, 10)");
          c.dsl.execute(
              "UPDATE account_recent_reauthentication_policy_current SET policy_version = 2 "
                  + "WHERE policy_scope = 'DEPLOYMENT'");
          return null;
        });

    var resolved =
        c.tx(
            () ->
                new AccountRecentReauthenticationPolicyRepository(c.dsl)
                    .resolveCurrentSourcesForGameCreation());
    var effective = resolved.effectivePolicy();
    assertThat(effective.platformVersion()).isEqualTo(1L);
    assertThat(effective.deploymentVersion()).isEqualTo(2L);
    assertThat(effective.recentAgeSeconds()).isEqualTo(100L);
    assertThat(effective.maxClockSkewSeconds()).isEqualTo(10L);
    assertThat(resolved.sources())
        .extracting(SourceEvidence::key)
        .containsExactly(
            "RECENT_REAUTH_POLICY:SELF_SERVICE_GAME_CREATION/DEPLOYMENT",
            "RECENT_REAUTH_POLICY:SELF_SERVICE_GAME_CREATION/PLATFORM");
    assertThatThrownBy(() -> resolved.sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);

    for (String policyScope : List.of("DEPLOYMENT", "PLATFORM")) {
      Record current =
          Objects.requireNonNull(
              c.dsl.fetchOne(
                  "SELECT value.policy_version, value.recent_age_seconds, "
                      + "value.max_clock_skew_seconds "
                      + "FROM account_recent_reauthentication_policy_current current "
                      + "JOIN account_recent_reauthentication_policy_values value "
                      + "ON value.policy_scope = current.policy_scope "
                      + "AND value.policy_version = current.policy_version "
                      + "WHERE current.policy_scope = ?",
                  policyScope));
      String sourceScope = AccountRecentReauthenticationPolicy.PURPOSE + "/" + policyScope;
      SourceEvidence source =
          resolved.sources().stream()
              .filter(candidate -> candidate.scopeId().equals(sourceScope))
              .findFirst()
              .orElseThrow();
      long version = current.get("policy_version", Long.class);
      long age = current.get("recent_age_seconds", Long.class);
      long skew = current.get("max_clock_skew_seconds", Long.class);
      String expectedValue =
          "account-recent-reauthentication-policy-source/v1\n"
              + "owner=ACCOUNT\n"
              + "purpose=SELF_SERVICE_GAME_CREATION\n"
              + "scope="
              + sourceScope
              + "\nversion="
              + version
              + "\nrecentAgeSeconds="
              + age
              + "\nmaxClockSkewSeconds="
              + skew
              + "\n";

      assertThat(source.sourceVersion()).isEqualTo(Long.toString(version));
      assertThat(source.generation()).isNull();
      assertThat(source.checkpointStream()).isNull();
      assertThat(source.checkpointSequence()).isNull();
      assertThat(new String(source.evidence(), java.nio.charset.StandardCharsets.UTF_8))
          .isEqualTo(expectedValue);
    }

    assertThat(
            c.count(
                "SELECT count(*) FROM account_draft_authorization_source_locks "
                    + "WHERE source_key IN ("
                    + "'RECENT_REAUTH_POLICY:SELF_SERVICE_GAME_CREATION/DEPLOYMENT', "
                    + "'RECENT_REAUTH_POLICY:SELF_SERVICE_GAME_CREATION/PLATFORM')"))
        .isEqualTo(2L);
  }

  @Test
  void originalTenantCreationIntentRetryReturnsSameBytesWithoutConsumingReauthentication() {
    Context c = context();
    Account account = c.account("PASSWORD");
    UUID reauthenticationRequestId = UUID.randomUUID();
    UUID creationOperationId = UUID.randomUUID();
    var evidence =
        c.service.reauthenticate(
            c.request(account, reauthenticationRequestId, creationOperationId, PASSWORD));
    var repository = new AccountTenantCreationIntentRepository(c.dsl, c.evidenceRepository);
    UUID intentRequestId = UUID.randomUUID();
    var request =
        intentRequest(
            account,
            intentRequestId,
            creationOperationId,
            UUID.randomUUID(),
            evidence.evidenceId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1);

    var original = c.tx(() -> repository.claim(request));
    String originalXmin =
        Objects.requireNonNull(
                c.dsl.fetchOne(
                    "SELECT xmin::text AS xmin FROM account_tenant_creation_original_intents "
                        + "WHERE request_id = ?",
                    intentRequestId))
            .get("xmin", String.class);
    var retry = c.tx(() -> repository.claim(request));

    assertThat(retry.requestPayload()).containsExactly(original.requestPayload());
    assertThat(retry.requestDigest()).isEqualTo(original.requestDigest());
    assertThat(retry.gameDesignRequestDigest()).isEqualTo(original.gameDesignRequestDigest());
    assertThat(retry.createdAt()).isEqualTo(original.createdAt());
    assertThat(
            Objects.requireNonNull(
                    c.dsl.fetchOne(
                        "SELECT xmin::text AS xmin FROM account_tenant_creation_original_intents "
                            + "WHERE request_id = ?",
                        intentRequestId))
                .get("xmin", String.class))
        .isEqualTo(originalXmin);
    assertThat(
            c.count(
                "SELECT count(*) FROM account_tenant_creation_original_intents "
                    + "WHERE request_id = ?",
                intentRequestId))
        .isEqualTo(1L);
    assertThat(original.requestId()).isNotEqualTo(evidence.requestId());
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_consumptions"))
        .isZero();
  }

  @Test
  void changedTenantCreationConsentAccountAndGameDesignInputConflictOnOriginalRequestId() {
    Context c = context();
    Account account = c.account("PASSWORD");
    Account otherAccount = c.account("PASSWORD");
    UUID reauthenticationRequestId = UUID.randomUUID();
    UUID creationOperationId = UUID.randomUUID();
    var evidence =
        c.service.reauthenticate(
            c.request(account, reauthenticationRequestId, creationOperationId, PASSWORD));
    var repository = new AccountTenantCreationIntentRepository(c.dsl, c.evidenceRepository);
    UUID intentRequestId = UUID.randomUUID();
    var original =
        intentRequest(
            account,
            intentRequestId,
            creationOperationId,
            UUID.randomUUID(),
            evidence.evidenceId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1);
    c.tx(() -> repository.claim(original));

    var changedRequests =
        List.of(
            changeIntent(
                original,
                otherAccount.getAccountUuid(),
                original.gameDesignCreationRequestId(),
                original.targetNamespace(),
                original.sourceGameTenantKey(),
                original.name(),
                original.description(),
                original.creatorPartyId(),
                original.consentOperationId(),
                original.consentStatementVersion()),
            changeIntent(
                original,
                original.initiatingAccountId(),
                UUID.randomUUID(),
                original.targetNamespace(),
                original.sourceGameTenantKey(),
                original.name(),
                original.description(),
                original.creatorPartyId(),
                original.consentOperationId(),
                original.consentStatementVersion()),
            changeIntent(
                original,
                original.initiatingAccountId(),
                original.gameDesignCreationRequestId(),
                "other-game",
                original.sourceGameTenantKey(),
                original.name(),
                original.description(),
                original.creatorPartyId(),
                original.consentOperationId(),
                original.consentStatementVersion()),
            changeIntent(
                original,
                original.initiatingAccountId(),
                original.gameDesignCreationRequestId(),
                original.targetNamespace(),
                "another-source-key",
                original.name(),
                original.description(),
                original.creatorPartyId(),
                original.consentOperationId(),
                original.consentStatementVersion()),
            changeIntent(
                original,
                original.initiatingAccountId(),
                original.gameDesignCreationRequestId(),
                original.targetNamespace(),
                original.sourceGameTenantKey(),
                "Changed game name",
                original.description(),
                original.creatorPartyId(),
                original.consentOperationId(),
                original.consentStatementVersion()),
            changeIntent(
                original,
                original.initiatingAccountId(),
                original.gameDesignCreationRequestId(),
                original.targetNamespace(),
                original.sourceGameTenantKey(),
                original.name(),
                "A changed description",
                original.creatorPartyId(),
                original.consentOperationId(),
                original.consentStatementVersion()),
            changeIntent(
                original,
                original.initiatingAccountId(),
                original.gameDesignCreationRequestId(),
                original.targetNamespace(),
                original.sourceGameTenantKey(),
                original.name(),
                original.description(),
                UUID.randomUUID(),
                original.consentOperationId(),
                original.consentStatementVersion()),
            changeIntent(
                original,
                original.initiatingAccountId(),
                original.gameDesignCreationRequestId(),
                original.targetNamespace(),
                original.sourceGameTenantKey(),
                original.name(),
                original.description(),
                original.creatorPartyId(),
                UUID.randomUUID(),
                original.consentStatementVersion() + 1));

    for (var changed : changedRequests) {
      assertThatThrownBy(() -> c.tx(() -> repository.claim(changed)))
          .isInstanceOf(AccountTenantCreationIntentRepository.IntentConflictException.class);
    }
    assertThat(
            c.count(
                "SELECT count(*) FROM account_tenant_creation_original_intents "
                    + "WHERE request_id = ?",
                intentRequestId))
        .isEqualTo(1L);
  }

  @Test
  void originalIntentSqlGuardRejectsWrongAccountOperationAndConsumedReauthentication() {
    Context c = context();
    Account account = c.account("PASSWORD");
    Account otherAccount = c.account("PASSWORD");
    UUID reauthenticationRequestId = UUID.randomUUID();
    UUID creationOperationId = UUID.randomUUID();
    var evidence =
        c.service.reauthenticate(
            c.request(account, reauthenticationRequestId, creationOperationId, PASSWORD));
    var repository = new AccountTenantCreationIntentRepository(c.dsl, c.evidenceRepository);
    var validShape =
        intentRequest(
            account,
            UUID.randomUUID(),
            creationOperationId,
            UUID.randomUUID(),
            evidence.evidenceId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1);
    var wrongAccount =
        changeIntent(
            validShape,
            otherAccount.getAccountUuid(),
            validShape.gameDesignCreationRequestId(),
            validShape.targetNamespace(),
            validShape.sourceGameTenantKey(),
            validShape.name(),
            validShape.description(),
            validShape.creatorPartyId(),
            validShape.consentOperationId(),
            validShape.consentStatementVersion());
    assertThatThrownBy(() -> c.tx(() -> repository.claim(wrongAccount)))
        .isInstanceOf(DataAccessException.class);

    var wrongOperation =
        new AccountTenantCreationIntentRepository.Request(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            account.getAccountUuid(),
            "test-game",
            "source-game-tenant",
            "Test game",
            null,
            evidence.evidenceId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            true);
    assertThatThrownBy(() -> c.tx(() -> repository.claim(wrongOperation)))
        .isInstanceOf(DataAccessException.class);

    Account consumedAccount = c.account("PASSWORD");
    UUID consumedReauthenticationRequestId = UUID.randomUUID();
    UUID consumedOperationId = UUID.randomUUID();
    var consumedEvidence =
        c.service.reauthenticate(
            c.request(
                consumedAccount, consumedReauthenticationRequestId, consumedOperationId, PASSWORD));
    c.consume(
        consumedEvidence,
        consumedAccount.getAccountUuid(),
        consumedEvidence.purpose(),
        consumedEvidence.creationOperationId());
    var consumedIntent =
        intentRequest(
            consumedAccount,
            UUID.randomUUID(),
            consumedOperationId,
            UUID.randomUUID(),
            consumedEvidence.evidenceId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1);
    assertThatThrownBy(() -> c.tx(() -> repository.claim(consumedIntent)))
        .isInstanceOf(DataAccessException.class);

    assertThat(c.count("SELECT count(*) FROM account_tenant_creation_original_intents")).isZero();
  }

  @Test
  void originalTenantCreationIntentRejectsSqlMutationDeletionTruncationAndDigestCorruption() {
    Context c = context();
    Account account = c.account("PASSWORD");
    UUID reauthenticationRequestId = UUID.randomUUID();
    UUID creationOperationId = UUID.randomUUID();
    var evidence =
        c.service.reauthenticate(
            c.request(account, reauthenticationRequestId, creationOperationId, PASSWORD));
    var repository = new AccountTenantCreationIntentRepository(c.dsl, c.evidenceRepository);
    UUID intentRequestId = UUID.randomUUID();
    c.tx(
        () ->
            repository.claim(
                intentRequest(
                    account,
                    intentRequestId,
                    creationOperationId,
                    UUID.randomUUID(),
                    evidence.evidenceId(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    1)));
    Record original =
        Objects.requireNonNull(
            c.dsl.fetchOne(
                "SELECT * FROM account_tenant_creation_original_intents WHERE request_id = ?",
                intentRequestId));

    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "UPDATE account_tenant_creation_original_intents SET name = ? "
                                + "WHERE request_id = ?",
                            "Changed game name",
                            intentRequestId)))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "UPDATE account_tenant_creation_original_intents SET request_digest = ? "
                                + "WHERE request_id = ?",
                            "sha256:" + "0".repeat(64),
                            intentRequestId)))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "DELETE FROM account_tenant_creation_original_intents WHERE request_id = ?",
                            intentRequestId)))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> c.tx(() -> c.dsl.execute("TRUNCATE account_tenant_creation_original_intents")))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            Objects.requireNonNull(
                c.dsl.fetchOne(
                    "SELECT * FROM account_tenant_creation_original_intents WHERE request_id = ?",
                    intentRequestId)))
        .isEqualTo(original);
  }

  @Test
  void concurrentExactTenantCreationIntentClaimsReturnOneOriginalRow() throws Exception {
    Context c = context();
    Account account = c.account("PASSWORD");
    UUID reauthenticationRequestId = UUID.randomUUID();
    UUID creationOperationId = UUID.randomUUID();
    var evidence =
        c.service.reauthenticate(
            c.request(account, reauthenticationRequestId, creationOperationId, PASSWORD));
    var repository = new AccountTenantCreationIntentRepository(c.dsl, c.evidenceRepository);
    UUID intentRequestId = UUID.randomUUID();
    var request =
        intentRequest(
            account,
            intentRequestId,
            creationOperationId,
            UUID.randomUUID(),
            evidence.evidenceId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  c.tx(
                      () -> {
                        ready.countDown();
                        awaitLatch(start);
                        return repository.claim(request);
                      }));
      var second =
          executor.submit(
              () ->
                  c.tx(
                      () -> {
                        ready.countDown();
                        awaitLatch(start);
                        return repository.claim(request);
                      }));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var firstResult = first.get(10, TimeUnit.SECONDS);
      var secondResult = second.get(10, TimeUnit.SECONDS);

      assertThat(secondResult.requestDigest()).isEqualTo(firstResult.requestDigest());
      assertThat(secondResult.createdAt()).isEqualTo(firstResult.createdAt());
      assertThat(secondResult.requestPayload()).containsExactly(firstResult.requestPayload());
      assertThat(c.count("SELECT count(*) FROM account_tenant_creation_original_intents"))
          .isEqualTo(1L);
      assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_consumptions"))
          .isZero();
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void currentPolicyPointerWriterUsesOnlyItsExactSourceLock() throws Exception {
    Context c = context();
    String platformKey = "RECENT_REAUTH_POLICY:SELF_SERVICE_GAME_CREATION/PLATFORM";
    var sourceLockHeld = new CountDownLatch(1);
    var releaseSourceLock = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var sourceLockHolder =
          executor.submit(
              () ->
                  c.tx(
                      () -> {
                        c.dsl.fetchOne(
                            "SELECT account_control_ui_hold_required_sources(ARRAY[?]::TEXT[])",
                            platformKey);
                        sourceLockHeld.countDown();
                        awaitLatch(releaseSourceLock);
                        return null;
                      }));
      assertThat(sourceLockHeld.await(5, TimeUnit.SECONDS)).isTrue();

      c.tx(
          () -> {
            c.dsl.execute(
                "INSERT INTO account_recent_reauthentication_policy_values "
                    + "(policy_scope, policy_version, recent_age_seconds, max_clock_skew_seconds) "
                    + "VALUES ('DEPLOYMENT', 2, 100, 10)");
            c.dsl.execute(
                "UPDATE account_recent_reauthentication_policy_current SET policy_version = 2 "
                    + "WHERE policy_scope = 'DEPLOYMENT'");
            return null;
          });

      assertThatThrownBy(
              () ->
                  c.tx(
                      () -> {
                        c.dsl.execute(
                            "INSERT INTO account_recent_reauthentication_policy_values "
                                + "(policy_scope, policy_version, recent_age_seconds, max_clock_skew_seconds) "
                                + "VALUES ('PLATFORM', 2, 240, 20)");
                        c.dsl.execute(
                            "UPDATE account_recent_reauthentication_policy_current "
                                + "SET policy_version = 2 WHERE policy_scope = 'PLATFORM'");
                        return null;
                      }))
          .isInstanceOf(DataAccessException.class);
      assertThat(
              Objects.requireNonNull(
                      c.dsl.fetchOne(
                          "SELECT policy_version FROM account_recent_reauthentication_policy_current "
                              + "WHERE policy_scope = 'PLATFORM'"))
                  .get("policy_version", Long.class))
          .isEqualTo(1L);

      releaseSourceLock.countDown();
      sourceLockHolder.get(10, TimeUnit.SECONDS);

      c.tx(
          () -> {
            c.dsl.execute(
                "INSERT INTO account_recent_reauthentication_policy_values "
                    + "(policy_scope, policy_version, recent_age_seconds, max_clock_skew_seconds) "
                    + "VALUES ('PLATFORM', 2, 240, 20)");
            c.dsl.execute(
                "UPDATE account_recent_reauthentication_policy_current SET policy_version = 2 "
                    + "WHERE policy_scope = 'PLATFORM'");
            return null;
          });
      assertThat(
              c.count(
                  "SELECT count(*) FROM account_recent_reauthentication_policy_current "
                      + "WHERE policy_version = 2"))
          .isEqualTo(2L);
    } finally {
      releaseSourceLock.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void forwardSourceOrderingMigrationSeedsOnlyCurrentPointersAndPreservesRetainedBytes() {
    Context c = contextBeforePolicySourceOrdering();
    Account account = c.account("PASSWORD");
    var evidence =
        c.service.reauthenticate(
            c.request(account, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    c.consume(
        evidence, account.getAccountUuid(), evidence.purpose(), evidence.creationOperationId());
    Record originalEvidence = c.evidenceRow(evidence.evidenceId());
    Record originalConsumption =
        Objects.requireNonNull(
            c.dsl.fetchOne(
                "SELECT * FROM account_recent_reauthentication_consumptions WHERE evidence_id = ?",
                evidence.evidenceId()));
    Record originalPlatformValue =
        Objects.requireNonNull(
            c.dsl.fetchOne(
                "SELECT * FROM account_recent_reauthentication_policy_values "
                    + "WHERE policy_scope = 'PLATFORM' AND policy_version = 1"));
    Record originalDeploymentValue =
        Objects.requireNonNull(
            c.dsl.fetchOne(
                "SELECT * FROM account_recent_reauthentication_policy_values "
                    + "WHERE policy_scope = 'DEPLOYMENT' AND policy_version = 1"));
    Record originalPlatformCurrent =
        Objects.requireNonNull(
            c.dsl.fetchOne(
                "SELECT * FROM account_recent_reauthentication_policy_current "
                    + "WHERE policy_scope = 'PLATFORM'"));
    Record originalDeploymentCurrent =
        Objects.requireNonNull(
            c.dsl.fetchOne(
                "SELECT * FROM account_recent_reauthentication_policy_current "
                    + "WHERE policy_scope = 'DEPLOYMENT'"));

    c.migrateToLatest();

    assertThat(c.evidenceRow(evidence.evidenceId())).isEqualTo(originalEvidence);
    assertThat(
            Objects.requireNonNull(
                c.dsl.fetchOne(
                    "SELECT * FROM account_recent_reauthentication_consumptions "
                        + "WHERE evidence_id = ?",
                    evidence.evidenceId())))
        .isEqualTo(originalConsumption);
    assertThat(
            Objects.requireNonNull(
                c.dsl.fetchOne(
                    "SELECT * FROM account_recent_reauthentication_policy_values "
                        + "WHERE policy_scope = 'PLATFORM' AND policy_version = 1")))
        .isEqualTo(originalPlatformValue);
    assertThat(
            Objects.requireNonNull(
                c.dsl.fetchOne(
                    "SELECT * FROM account_recent_reauthentication_policy_values "
                        + "WHERE policy_scope = 'DEPLOYMENT' AND policy_version = 1")))
        .isEqualTo(originalDeploymentValue);
    assertThat(
            Objects.requireNonNull(
                c.dsl.fetchOne(
                    "SELECT * FROM account_recent_reauthentication_policy_current "
                        + "WHERE policy_scope = 'PLATFORM'")))
        .isEqualTo(originalPlatformCurrent);
    assertThat(
            Objects.requireNonNull(
                c.dsl.fetchOne(
                    "SELECT * FROM account_recent_reauthentication_policy_current "
                        + "WHERE policy_scope = 'DEPLOYMENT'")))
        .isEqualTo(originalDeploymentCurrent);
    assertThat(
            c.count(
                "SELECT count(*) FROM account_draft_authorization_source_locks "
                    + "WHERE source_key LIKE 'RECENT_REAUTH_POLICY:%'"))
        .isEqualTo(2L);
    assertThat(
            c.count(
                "SELECT count(*) FROM account_recent_reauthentication_policy_current "
                    + "WHERE policy_scope IN ('PLATFORM', 'DEPLOYMENT')"))
        .isEqualTo(2L);
  }

  @Test
  void exactOtpRequestReplayReturnsOriginalEvidenceWithoutSecondAuthenticationOrOtpUse() {
    Context c = context();
    Account account = c.account("EMAIL_OTP");
    AccountEmailLoginChallenge challenge = c.challenge(account);
    var request = c.request(account, UUID.randomUUID(), UUID.randomUUID(), OTP);

    var first = c.service.reauthenticate(request);
    assertThat(c.challenges.findByAccountId(account.getId())).isEmpty();
    var replay = c.service.reauthenticate(request);

    assertThat(replay.evidenceId()).isEqualTo(first.evidenceId());
    assertThat(replay.issuedAt()).isEqualTo(first.issuedAt());
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_evidence"))
        .isEqualTo(1L);
    assertThat(c.challenges.findByAccountId(account.getId())).isEmpty();
    assertThat(challenge.getCodeHash()).isNotBlank();
  }

  @Test
  void changedAccountPurposeOrCreationOperationConflictsBeforeNewAuthentication() {
    Context c = context();
    Account account = c.account("PASSWORD");
    Account other = c.account("PASSWORD");
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    var original = c.request(account, requestId, operationId, PASSWORD);
    c.service.reauthenticate(original);

    assertThatThrownBy(
            () ->
                c.service.reauthenticate(
                    c.request(other, requestId, operationId, "not-the-secret")))
        .isInstanceOf(AccountRecentReauthenticationService.IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                c.service.reauthenticate(
                    new AccountRecentReauthenticationService.Request(
                        requestId,
                        account.getAccountUuid(),
                        "ACCOUNT_SECURITY_CHANGE",
                        operationId,
                        account.getEmail(),
                        "not-the-secret")))
        .isInstanceOf(AccountRecentReauthenticationService.IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                c.service.reauthenticate(
                    c.request(account, requestId, UUID.randomUUID(), "not-the-secret")))
        .isInstanceOf(AccountRecentReauthenticationService.IdempotencyConflictException.class);
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_evidence"))
        .isEqualTo(1L);
  }

  @Test
  void failedOwnerCommitRollsBackExactOtpConsumptionAndDoesNotReturnEvidence() {
    Context c = context();
    Account account = c.account("EMAIL_OTP");
    AccountEmailLoginChallenge challenge = c.challenge(account);
    var request = c.request(account, UUID.randomUUID(), UUID.randomUUID(), OTP);
    c.dsl.execute(
        "CREATE FUNCTION reject_recent_reauthentication_commit() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test commit failure'; END $$");
    c.dsl.execute(
        "CREATE CONSTRAINT TRIGGER reject_recent_reauthentication_commit "
            + "AFTER INSERT ON account_recent_reauthentication_evidence "
            + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW "
            + "EXECUTE FUNCTION reject_recent_reauthentication_commit()");

    assertThatThrownBy(() -> c.service.reauthenticate(request))
        .isInstanceOf(RuntimeException.class);
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_evidence")).isZero();
    assertThat(c.challenges.findByAccountId(account.getId())).contains(challenge);
  }

  @Test
  void accountAuthorityAdvanceAndCurrentLockedLifecycleInvalidateUnconsumedEvidence() {
    Context c = context();
    Account account = c.account("PASSWORD");
    var generationEvidence =
        c.service.reauthenticate(
            c.request(account, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    ScopeState before =
        c.tx(() -> c.generations.read(AuthorityScope.account(account.getAccountUuid())));

    c.tx(
        () -> {
          account.setEmailVerified(true);
          c.accounts.save(account);
          return null;
        });
    ScopeState after =
        c.tx(() -> c.generations.read(AuthorityScope.account(account.getAccountUuid())));
    assertThat(after.generation()).isGreaterThan(before.generation());
    assertThatThrownBy(
            () ->
                c.consume(
                    generationEvidence,
                    account.getAccountUuid(),
                    generationEvidence.purpose(),
                    generationEvidence.creationOperationId()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_consumptions"))
        .isZero();

    Account lifecycleAccount = c.account("PASSWORD");
    var lifecycleEvidence =
        c.service.reauthenticate(
            c.request(lifecycleAccount, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    // This fixture changes only the persisted current lifecycle to isolate the AccountRepository
    // current-state denial. It does not claim the separate security-lock transition producer.
    c.transaction.executeWithoutResult(
        status -> {
          c.dsl.execute("SET LOCAL session_replication_role = 'replica'");
          c.dsl.execute(
              "UPDATE accounts SET lifecycle_state = 'security_locked' WHERE account_uuid = ?",
              lifecycleAccount.getAccountUuid());
        });
    assertThatThrownBy(
            () ->
                c.consume(
                    lifecycleEvidence,
                    lifecycleAccount.getAccountUuid(),
                    lifecycleEvidence.purpose(),
                    lifecycleEvidence.creationOperationId()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_consumptions"))
        .isZero();
  }

  @Test
  void exactExpiryFutureSkewAndPolicyVersionAdvanceFailClosed() {
    Context c = context();
    Account exactDeadlineAccount = c.account("PASSWORD");
    c.clock.set(BASE);
    var exactDeadline =
        c.service.reauthenticate(
            c.request(exactDeadlineAccount, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    c.clock.set(BASE.plusSeconds(270));
    assertThatThrownBy(
            () ->
                c.consume(
                    exactDeadline,
                    exactDeadlineAccount.getAccountUuid(),
                    exactDeadline.purpose(),
                    exactDeadline.creationOperationId()))
        .isInstanceOf(IllegalStateException.class);

    Account futureAccount = c.account("PASSWORD");
    c.clock.set(BASE.plusSeconds(30));
    var atMaximumSkew =
        c.service.reauthenticate(
            c.request(futureAccount, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    c.clock.set(BASE);
    var accepted =
        c.consume(
            atMaximumSkew,
            futureAccount.getAccountUuid(),
            atMaximumSkew.purpose(),
            atMaximumSkew.creationOperationId());
    assertThat(accepted.disposition()).isEqualTo(Disposition.CONSUMED);

    Account beyondSkewAccount = c.account("PASSWORD");
    c.clock.set(BASE.plusSeconds(31));
    var beyondSkew =
        c.service.reauthenticate(
            c.request(beyondSkewAccount, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    c.clock.set(BASE);
    assertThatThrownBy(
            () ->
                c.consume(
                    beyondSkew,
                    beyondSkewAccount.getAccountUuid(),
                    beyondSkew.purpose(),
                    beyondSkew.creationOperationId()))
        .isInstanceOf(IllegalStateException.class);

    Account policyAccount = c.account("PASSWORD");
    c.clock.set(BASE);
    var priorPolicy =
        c.service.reauthenticate(
            c.request(policyAccount, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    c.tx(
        () -> {
          c.dsl.execute(
              "INSERT INTO account_recent_reauthentication_policy_values "
                  + "(policy_scope, policy_version, recent_age_seconds, max_clock_skew_seconds) "
                  + "VALUES ('DEPLOYMENT', 2, 100, 10)");
          c.dsl.execute(
              "UPDATE account_recent_reauthentication_policy_current SET policy_version = 2 "
                  + "WHERE policy_scope = 'DEPLOYMENT'");
          return null;
        });
    assertThatThrownBy(
            () ->
                c.consume(
                    priorPolicy,
                    policyAccount.getAccountUuid(),
                    priorPolicy.purpose(),
                    priorPolicy.creationOperationId()))
        .isInstanceOf(IllegalStateException.class);
    var currentPolicy =
        c.service.reauthenticate(
            c.request(policyAccount, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    assertThat(currentPolicy.deploymentPolicyVersion()).isEqualTo(2L);
    assertThat(currentPolicy.recentAgeSeconds()).isEqualTo(100L);
    assertThat(currentPolicy.maxClockSkewSeconds()).isEqualTo(10L);
  }

  @Test
  void consumeIsImmutableAndConcurrentExactRetryCannotConsumeForAnotherOperation()
      throws Exception {
    Context c = context();
    Account account = c.account("PASSWORD");
    var evidence =
        c.service.reauthenticate(
            c.request(account, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    var locked = c.evidenceRow(evidence.evidenceId());
    var enteredFirst = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var secondEntered = new CountDownLatch(1);
    var firstPid = new AtomicInteger();
    var secondPid = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () ->
                  c.transaction.execute(
                      status -> {
                        firstPid.set(c.pid());
                        var result =
                            c.service.validateAndConsumeLocked(
                                evidence,
                                account.getAccountUuid(),
                                evidence.purpose(),
                                evidence.creationOperationId());
                        enteredFirst.countDown();
                        awaitLatch(releaseFirst);
                        return result.disposition();
                      }));
      awaitLatch(enteredFirst);
      var second =
          executor.submit(
              () ->
                  c.transaction.execute(
                      status -> {
                        secondPid.set(c.pid());
                        secondEntered.countDown();
                        return c.service
                            .validateAndConsumeLocked(
                                evidence,
                                account.getAccountUuid(),
                                evidence.purpose(),
                                evidence.creationOperationId())
                            .disposition();
                      }));
      awaitLatch(secondEntered);
      awaitBlocked(c, secondPid.get(), firstPid.get());
      releaseFirst.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(Disposition.CONSUMED);
      assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(Disposition.EXACT_REPLAY);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_consumptions"))
        .isEqualTo(1L);
    assertThat(c.evidenceRow(evidence.evidenceId())).isEqualTo(locked);
    assertThatThrownBy(
            () ->
                c.consume(
                    evidence, account.getAccountUuid(), evidence.purpose(), UUID.randomUUID()))
        .isInstanceOf(AccountRecentReauthenticationService.IdempotencyConflictException.class);
  }

  @Test
  void pendingCreationValidationReadsCurrentExactEvidenceWithoutConsumption() {
    Context c = context();
    Account account = c.account("PASSWORD");
    var evidence =
        c.service.reauthenticate(
            c.request(account, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    Record original = c.evidenceRow(evidence.evidenceId());

    assertThat(c.validatePending(evidence)).usingRecursiveComparison().isEqualTo(evidence);
    assertThat(c.validatePending(evidence)).usingRecursiveComparison().isEqualTo(evidence);
    assertThat(c.evidenceRow(evidence.evidenceId())).isEqualTo(original);
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_consumptions"))
        .isZero();

    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.service.validatePendingCreationLocked(
                            evidence,
                            UUID.randomUUID(),
                            evidence.purpose(),
                            evidence.creationOperationId())))
        .isInstanceOf(AccountRecentReauthenticationService.IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.service.validatePendingCreationLocked(
                            evidence,
                            evidence.accountId(),
                            "ACCOUNT_SECURITY_CHANGE",
                            evidence.creationOperationId())))
        .isInstanceOf(AccountRecentReauthenticationService.IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.service.validatePendingCreationLocked(
                            evidence, evidence.accountId(), evidence.purpose(), UUID.randomUUID())))
        .isInstanceOf(AccountRecentReauthenticationService.IdempotencyConflictException.class);
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_consumptions"))
        .isZero();
    assertThat(c.evidenceRow(evidence.evidenceId())).isEqualTo(original);
  }

  @Test
  void pendingCreationRechecksActualAccountAuthorityAfterEarlierSuccessfulValidation() {
    Context c = context();
    Account account = c.account("PASSWORD");
    var evidence =
        c.service.reauthenticate(
            c.request(account, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    assertThat(c.validatePending(evidence)).usingRecursiveComparison().isEqualTo(evidence);
    ScopeState before =
        c.tx(() -> c.generations.read(AuthorityScope.account(account.getAccountUuid())));

    c.tx(
        () -> {
          account.setEmailVerified(true);
          return c.accounts.save(account);
        });
    ScopeState after =
        c.tx(() -> c.generations.read(AuthorityScope.account(account.getAccountUuid())));

    assertThat(after.generation()).isGreaterThan(before.generation());
    assertThatThrownBy(() -> c.validatePending(evidence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account recent-reauthentication authority is unavailable");
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_consumptions"))
        .isZero();
  }

  @Test
  void pendingCreationRechecksCurrentPolicyAfterEarlierSuccessfulValidation() {
    Context c = context();
    Account account = c.account("PASSWORD");
    var evidence =
        c.service.reauthenticate(
            c.request(account, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    assertThat(c.validatePending(evidence)).usingRecursiveComparison().isEqualTo(evidence);

    c.tx(
        () -> {
          c.dsl.execute(
              "INSERT INTO account_recent_reauthentication_policy_values "
                  + "(policy_scope, policy_version, recent_age_seconds, max_clock_skew_seconds) "
                  + "VALUES ('DEPLOYMENT', 2, 100, 10)");
          return c.dsl.execute(
              "UPDATE account_recent_reauthentication_policy_current SET policy_version = 2 "
                  + "WHERE policy_scope = 'DEPLOYMENT'");
        });

    assertThatThrownBy(() -> c.validatePending(evidence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account recent-reauthentication authority is unavailable");
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_consumptions"))
        .isZero();
  }

  @Test
  void pendingCreationRechecksAgeAfterEarlierSuccessfulValidation() {
    Context c = context();
    Account account = c.account("PASSWORD");
    var evidence =
        c.service.reauthenticate(
            c.request(account, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    assertThat(c.validatePending(evidence)).usingRecursiveComparison().isEqualTo(evidence);

    c.clock.set(BASE.plusSeconds(270));

    assertThatThrownBy(() -> c.validatePending(evidence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account recent-reauthentication authority is unavailable");
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_consumptions"))
        .isZero();
  }

  @Test
  void pendingCreationRefusesConsumedEvidenceWhileFinalResultRecoveryRetainsExactReplay() {
    Context c = context();
    Account account = c.account("PASSWORD");
    var evidence =
        c.service.reauthenticate(
            c.request(account, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    var consumed =
        c.consume(
            evidence, evidence.accountId(), evidence.purpose(), evidence.creationOperationId());
    Record original = c.evidenceRow(evidence.evidenceId());
    Record originalConsumption =
        Objects.requireNonNull(
            c.dsl.fetchOne(
                "SELECT * FROM account_recent_reauthentication_consumptions WHERE evidence_id = ?",
                evidence.evidenceId()));
    assertThat(consumed.disposition()).isEqualTo(Disposition.CONSUMED);
    assertThatThrownBy(() -> c.validatePending(evidence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Account recent-reauthentication authority is unavailable");

    c.clock.set(BASE.plusSeconds(270));
    var replay =
        c.consume(
            evidence, evidence.accountId(), evidence.purpose(), evidence.creationOperationId());
    assertThat(replay.disposition()).isEqualTo(Disposition.EXACT_REPLAY);
    assertThat(c.evidenceRow(evidence.evidenceId())).isEqualTo(original);
    assertThat(
            c.dsl.fetchOne(
                "SELECT * FROM account_recent_reauthentication_consumptions WHERE evidence_id = ?",
                evidence.evidenceId()))
        .isEqualTo(originalConsumption);
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_consumptions"))
        .isEqualTo(1L);
  }

  @Test
  void retainedEvidenceAndConsumptionRejectSqlMutation() {
    Context c = context();
    Account account = c.account("PASSWORD");
    var evidence =
        c.service.reauthenticate(
            c.request(account, UUID.randomUUID(), UUID.randomUUID(), PASSWORD));
    Record original = c.evidenceRow(evidence.evidenceId());

    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "UPDATE account_recent_reauthentication_evidence "
                                + "SET issued_at = clock_timestamp() WHERE evidence_id = ?",
                            evidence.evidenceId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "DELETE FROM account_recent_reauthentication_evidence WHERE evidence_id = ?",
                            evidence.evidenceId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> c.tx(() -> c.dsl.execute("TRUNCATE account_recent_reauthentication_evidence")))
        .isInstanceOf(DataAccessException.class);
    assertThat(c.evidenceRow(evidence.evidenceId())).isEqualTo(original);

    c.consume(
        evidence, account.getAccountUuid(), evidence.purpose(), evidence.creationOperationId());
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "UPDATE account_recent_reauthentication_consumptions "
                                + "SET consumed_at = clock_timestamp() WHERE evidence_id = ?",
                            evidence.evidenceId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "DELETE FROM account_recent_reauthentication_consumptions WHERE evidence_id = ?",
                            evidence.evidenceId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                c.tx(() -> c.dsl.execute("TRUNCATE account_recent_reauthentication_consumptions")))
        .isInstanceOf(DataAccessException.class);
    assertThat(c.count("SELECT count(*) FROM account_recent_reauthentication_consumptions"))
        .isEqualTo(1L);
  }

  private static AccountTenantCreationIntentRepository.Request intentRequest(
      Account account,
      UUID requestId,
      UUID creationOperationId,
      UUID gameDesignCreationRequestId,
      UUID evidenceId,
      UUID creatorPartyId,
      UUID consentOperationId,
      int consentStatementVersion) {
    return new AccountTenantCreationIntentRepository.Request(
        requestId,
        creationOperationId,
        gameDesignCreationRequestId,
        account.getAccountUuid(),
        "test-game",
        "source-game-tenant",
        "Test game",
        null,
        evidenceId,
        creatorPartyId,
        consentOperationId,
        consentStatementVersion,
        true);
  }

  private static AccountTenantCreationIntentRepository.Request changeIntent(
      AccountTenantCreationIntentRepository.Request original,
      UUID initiatingAccountId,
      UUID gameDesignCreationRequestId,
      String targetNamespace,
      String sourceGameTenantKey,
      String name,
      String description,
      UUID creatorPartyId,
      UUID consentOperationId,
      int consentStatementVersion) {
    return new AccountTenantCreationIntentRepository.Request(
        original.requestId(),
        original.creationOperationId(),
        gameDesignCreationRequestId,
        initiatingAccountId,
        targetNamespace,
        sourceGameTenantKey,
        name,
        description,
        original.recentReauthenticationEvidenceId(),
        creatorPartyId,
        consentOperationId,
        consentStatementVersion,
        original.affirmativeConsent());
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Expected recent-reauthentication transaction boundary");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted recent-reauthentication transaction boundary");
    }
  }

  private static void awaitBlocked(Context c, int waitingPid, int blockerPid) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      var observed =
          Objects.requireNonNull(
              c.dsl.fetchOne(
                  "SELECT ? = ANY(pg_blocking_pids(?)) AS blocked", blockerPid, waitingPid));
      if (Boolean.TRUE.equals(observed.get("blocked", Boolean.class))) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new IllegalStateException("Concurrent consume did not wait on the exact evidence row");
  }

  private static Context context() {
    return context(true);
  }

  private static Context context(boolean installApprovedPolicySources) {
    return context(installApprovedPolicySources, true);
  }

  private static Context contextBeforePolicySourceOrdering() {
    return context(true, false);
  }

  private static Context context(
      boolean installApprovedPolicySources, boolean installPolicySourceOrdering) {
    String schema = "recent_reauth_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    var flywayConfiguration =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (!installPolicySourceOrdering) {
      flywayConfiguration.target(MigrationVersion.fromVersion("1018"));
    }
    flywayConfiguration.load().migrate();
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var manager = new DataSourceTransactionManager(source);
    var transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    if (installApprovedPolicySources) {
      transaction.executeWithoutResult(
          ignored -> {
            dsl.execute(
                "INSERT INTO account_recent_reauthentication_policy_values "
                    + "(policy_scope, policy_version, recent_age_seconds, max_clock_skew_seconds) "
                    + "VALUES ('PLATFORM', 1, 300, 30), ('DEPLOYMENT', 1, 300, 30)");
            dsl.execute(
                "INSERT INTO account_recent_reauthentication_policy_current "
                    + "(policy_scope, policy_version) VALUES ('PLATFORM', 1), ('DEPLOYMENT', 1)");
          });
    }
    var generations = new AccountAuthorityGenerationRepository(dsl);
    var outbox = new AccountAuthorityOutboxRepository(dsl);
    var sourceEvidence = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
    var accounts = new AccountRepository(dsl, sourceEvidence);
    var challenges = new AccountEmailLoginChallengeRepository(dsl);
    var primary =
        new AccountServiceImpl(
            accounts,
            generations,
            sourceEvidence,
            outbox,
            mock(AccountPasswordResetOperationRepository.class),
            mock(AccountLogoutAllOperationRepository.class),
            mock(AccountSecurityStateOperationRepository.class),
            mock(AccountPasswordResetDraftSourceChangeRepository.class),
            mock(AccountAuditOutboxRepository.class),
            mock(AccountConnectScopeRepository.class),
            mock(AccountJoinOperationRepository.class),
            challenges,
            null,
            null,
            Mappers.getMapper(AccountMapper.class),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            manager);
    var evidence = new AccountRecentReauthenticationRepository(dsl);
    var policies = new AccountRecentReauthenticationPolicyRepository(dsl);
    var clock = new MutableClock(BASE);
    var service =
        new AccountRecentReauthenticationService(
            primary, accounts, generations, evidence, policies, manager, clock);
    return new Context(
        dsl,
        accounts,
        challenges,
        generations,
        evidence,
        service,
        transaction,
        clock,
        source,
        schema);
  }

  private static String hash(String secret) {
    char[] chars = secret.toCharArray();
    var argon = Argon2Factory.create();
    try {
      return argon.hash(2, 4096, 1, chars);
    } finally {
      argon.wipeArray(chars);
    }
  }

  private record Context(
      DSLContext dsl,
      AccountRepository accounts,
      AccountEmailLoginChallengeRepository challenges,
      AccountAuthorityGenerationRepository generations,
      AccountRecentReauthenticationRepository evidenceRepository,
      AccountRecentReauthenticationService service,
      TransactionTemplate transaction,
      MutableClock clock,
      DriverManagerDataSource source,
      String schema) {
    <T> T tx(Supplier<T> action) {
      return transaction.execute(ignored -> action.get());
    }

    Account account(String modes) {
      Account account = new Account();
      String suffix = UUID.randomUUID().toString();
      account.setUsername("recent-reauth-" + suffix);
      account.setEmail(suffix + "@example.test");
      account.setPasswordHash(hash(PASSWORD));
      account.setRole("player");
      account.setLoginAuthModes(modes);
      account.setLifecycleState(AccountLifecycleState.ACTIVE);
      return tx(() -> accounts.save(account));
    }

    AccountRecentReauthenticationService.Request request(
        Account account, UUID requestId, UUID operationId, String secret) {
      return new AccountRecentReauthenticationService.Request(
          requestId,
          account.getAccountUuid(),
          AccountRecentReauthenticationPolicy.PURPOSE,
          operationId,
          account.getEmail(),
          secret);
    }

    AccountEmailLoginChallenge challenge(Account account) {
      return tx(
          () -> {
            var challenge = new AccountEmailLoginChallenge();
            var now = LocalDateTime.now().withNano(0);
            challenge.setAccountId(account.getId());
            challenge.setCodeHash(hash(OTP));
            challenge.setExpiresAt(now.plusMinutes(5));
            challenge.setResendAvailableAt(now.plusMinutes(1));
            challenge.setCreatedAt(now);
            challenge.setUpdatedAt(now);
            return challenges.save(challenge);
          });
    }

    AccountRecentReauthenticationService.Validation consume(
        AccountRecentReauthenticationEvidence evidence,
        UUID accountId,
        String purpose,
        UUID operationId) {
      return tx(() -> service.validateAndConsumeLocked(evidence, accountId, purpose, operationId));
    }

    AccountRecentReauthenticationEvidence validatePending(
        AccountRecentReauthenticationEvidence evidence) {
      return tx(
          () ->
              service.validatePendingCreationLocked(
                  evidence,
                  evidence.accountId(),
                  evidence.purpose(),
                  evidence.creationOperationId()));
    }

    Record evidenceRow(UUID evidenceId) {
      return Objects.requireNonNull(
          dsl.fetchOne(
              "SELECT * FROM account_recent_reauthentication_evidence WHERE evidence_id = ?",
              evidenceId));
    }

    long count(String query, Object... parameters) {
      return Objects.requireNonNull(dsl.fetchOne(query, parameters)).get(0, Long.class);
    }

    int pid() {
      return Objects.requireNonNull(dsl.fetchOne("SELECT pg_backend_pid() AS pid"))
          .get("pid", Integer.class);
    }

    void migrateToLatest() {
      Flyway.configure()
          .dataSource(source)
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .load()
          .migrate();
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void set(Instant next) {
      instant = next;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) {
        throw new IllegalArgumentException("Test clock is fixed to UTC");
      }
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
