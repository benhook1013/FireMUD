package integration.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.mkammerer.argon2.Argon2Factory;
import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.accountservice.dto.CanonicalGameplayLoginRequest;
import net.firedevops.firemud.accountservice.dto.GameplayCredentialSourceContext;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.IdempotencyConflictException;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCanonicalLoginOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCredentialRequestDigestKeySource;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCredentialRequestDigestKeySource.CredentialDigestKey;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationIssuanceCommitService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseRecoveryOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayPrimaryCredentialVerifier;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Physical SQL producer proof with real primary authority. Account birth and challenge issuance are
 * controlled upstream fixture inputs. External commit/recovery owners are stopped before signing,
 * registry, or credential release; caller authentication and runtime activation are not exercised.
 */
class AccountGameplayPrimaryCredentialVerifierPostgresIntegrationTest {
  private static final String WORKLOAD = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String OTP = "246810";
  private static final String PASSWORD = "password";
  private static final String PASSWORD_HASH = hash(PASSWORD);
  private static final String OTP_HASH = hash(OTP);
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopOwnedPostgres() {
    POSTGRES.stop();
  }

  @Test
  void invalidOtpCommitsAttemptEvidenceWithoutPendingIssuance() {
    Harness h = harness("EMAIL_OTP", false);
    assertInvalid(h, request(h, "wrong"));
    assertThat(
            h.challenges.findByAccountId(h.account.getId()).orElseThrow().getInvalidAttemptCount())
        .isEqualTo(1);
    assertThat(h.operationCount()).isZero();
    verifyNoInteractions(h.commit, h.response);
  }

  @Test
  void expiredOtpDeletionCommitsWithoutPendingIssuance() {
    Harness h = harness("EMAIL_OTP", true);
    assertInvalid(h, request(h, OTP));
    assertThat(h.challenges.findByAccountId(h.account.getId())).isEmpty();
    assertThat(h.operationCount()).isZero();
    verifyNoInteractions(h.commit, h.response);
  }

  @Test
  void successfulOtpCommitsExactPendingAndConsumptionBeforeExternalOwner() {
    Harness h = harness("EMAIL_OTP", false);
    CanonicalGameplayLoginRequest request = request(h, OTP);
    assertSqlCommittedStop(h, request);
    var replay =
        h.tx.execute(
            status -> h.issuance.readCurrentGameplayLoginReplay(request.requestId()).orElseThrow());
    assertThat(replay.identity().requestId()).isEqualTo(request.requestId());
    assertThat(replay.identity().accountId()).isEqualTo(h.account.getAccountUuid());
    assertThat(replay.identity().callerWorkload()).isEqualTo(WORKLOAD);
    assertThat(replay.identity().callerContextId()).isEqualTo(request.sourceContext().contextId());
    assertThat(replay.state().name()).isEqualTo("PENDING");
    assertThat(h.operationCount()).isEqualTo(1);
    assertThat(h.challenges.findByAccountId(h.account.getId())).isEmpty();
    verifyNoInteractions(h.response);
  }

  @ParameterizedTest
  @ValueSource(strings = {"preparation", "consumption"})
  void sqlFailureRollsBackPendingAndChallengeMutation(String phase) {
    Harness h = harness("EMAIL_OTP", false);
    Record original = h.challengeRow();
    // Add rejection only: all canonical credential and issuance guards remain enabled.
    h.dsl.execute(
        "CREATE FUNCTION reject_test_write() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test SQL failure'; END $$");
    String triggerTarget =
        phase.equals("preparation")
            ? "BEFORE INSERT ON account_gameplay_delegation_issuance_operations"
            : "AFTER DELETE ON account_email_login_challenge";
    h.dsl.execute(
        "CREATE TRIGGER test_rejection "
            + triggerTarget
            + " FOR EACH ROW EXECUTE FUNCTION reject_test_write()");
    assertThatThrownBy(() -> h.login.authenticate(request(h, OTP), WORKLOAD, h.verifier))
        .isInstanceOf(DataAccessException.class);
    assertThat(h.operationCount()).isZero();
    assertThat(h.challengeRow()).isEqualTo(original);
    verifyNoInteractions(h.commit, h.response);
  }

  @Test
  void pendingRetryRetainsOriginalBindingAndDoesNotTouchReplacementChallenge() {
    Harness h = harness("EMAIL_OTP", false);
    CanonicalGameplayLoginRequest request = request(h, OTP);
    assertSqlCommittedStop(h, request);
    Record original = h.operationRow();
    // A replacement challenge rejects the old secret; a re-verification would mutate its counter.
    h.tx.execute(status -> h.challenges.save(challenge(h.account, PASSWORD_HASH, false)));
    Record replacement = h.challengeRow();
    assertSqlCommittedStop(h, request);
    assertThat(h.operationRow()).isEqualTo(original);
    assertThat(h.challengeRow()).isEqualTo(replacement);
    assertThat(h.operationCount()).isEqualTo(1);
    verifyNoInteractions(h.response);
  }

  @ParameterizedTest
  @ValueSource(strings = {"credential", "email", "context", "source", "workload"})
  void changedRequestOrCallerConflictsWithoutChangingOriginal(String change) {
    Harness h = harness("EMAIL_OTP", false);
    CanonicalGameplayLoginRequest originalRequest = request(h, OTP);
    assertSqlCommittedStop(h, originalRequest);
    h.tx.execute(status -> h.challenges.save(challenge(h.account, PASSWORD_HASH, false)));
    Record operation = h.operationRow();
    Record replacement = h.challengeRow();
    var context = originalRequest.sourceContext();
    var altered =
        new CanonicalGameplayLoginRequest(
            originalRequest.requestId(),
            change.equals("email") ? "different@example.test" : originalRequest.email(),
            change.equals("credential") ? "different" : OTP,
            new GameplayCredentialSourceContext(
                change.equals("context") ? UUID.randomUUID() : context.contextId(),
                change.equals("source") ? "203.0.113.99" : context.canonicalClientAddress(),
                context.transportClass()));
    String caller =
        change.equals("workload") ? "spiffe://firemud/ns/other/sa/game-session-service" : WORKLOAD;
    assertThatThrownBy(() -> h.login.authenticate(altered, caller, h.verifier))
        .isInstanceOf(IdempotencyConflictException.class);
    assertThat(h.operationRow()).isEqualTo(operation);
    assertThat(h.challengeRow()).isEqualTo(replacement);
    assertThat(h.operationCount()).isEqualTo(1);
  }

  @Test
  void passwordCreatesPendingWithoutConsumingUnrelatedChallenge() {
    Harness h = harness("PASSWORD", false);
    Record before = h.challengeRow();
    assertSqlCommittedStop(h, request(h, PASSWORD));
    assertThat(h.operationCount()).isEqualTo(1);
    assertThat(h.challengeRow()).isEqualTo(before);
  }

  @Test
  void missingAccountCreatesNeitherPendingNorChallengeChanges() {
    Harness h = harness("EMAIL_OTP", false);
    Record before = h.challengeRow();
    var request =
        new CanonicalGameplayLoginRequest(
            UUID.randomUUID(),
            "missing@example.test",
            OTP,
            new GameplayCredentialSourceContext(
                UUID.randomUUID(), "203.0.113.10", "FIRST_PARTY_WEB"));
    assertInvalid(h, request);
    assertThat(h.operationCount()).isZero();
    assertThat(h.challengeRow()).isEqualTo(before);
    verifyNoInteractions(h.commit, h.response);
  }

  private static void assertInvalid(Harness h, CanonicalGameplayLoginRequest request) {
    assertThatThrownBy(() -> h.login.authenticate(request, WORKLOAD, h.verifier))
        .isInstanceOfSatisfying(
            AuthenticationException.class,
            failure ->
                assertThat(failure.getCode())
                    .isEqualTo(AuthenticationErrorCodes.INVALID_CREDENTIALS));
  }

  private static void assertSqlCommittedStop(Harness h, CanonicalGameplayLoginRequest request) {
    assertThatThrownBy(() -> h.login.authenticate(request, WORKLOAD, h.verifier))
        .isInstanceOf(ExternalCommitStopped.class);
  }

  private static CanonicalGameplayLoginRequest request(Harness h, String secret) {
    return new CanonicalGameplayLoginRequest(
        UUID.randomUUID(),
        h.account.getEmail(),
        secret,
        new GameplayCredentialSourceContext(UUID.randomUUID(), "203.0.113.10", "FIRST_PARTY_WEB"));
  }

  private static AccountEmailLoginChallenge challenge(
      Account account, String hash, boolean expired) {
    LocalDateTime now = LocalDateTime.now();
    AccountEmailLoginChallenge challenge = new AccountEmailLoginChallenge();
    challenge.setAccountId(account.getId());
    challenge.setCodeHash(hash);
    challenge.setExpiresAt(expired ? now.minusMinutes(1) : now.plusMinutes(10));
    challenge.setResendAvailableAt(now);
    challenge.setCreatedAt(now);
    challenge.setUpdatedAt(now);
    challenge.setInvalidAttemptCount(0);
    return challenge;
  }

  private static Harness harness(String authModes, boolean expired) {
    String schema = "acct_primary_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource = POSTGRES.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var manager = new DataSourceTransactionManager(dataSource);
    var tx = new TransactionTemplate(manager);
    var authorities = new AccountAuthorityGenerationRepository(dsl);
    var outbox = new AccountAuthorityOutboxRepository(dsl);
    var sources = new AccountAuthoritySourceEvidenceRepository(dsl, authorities, outbox);
    var accounts = new AccountRepository(dsl, sources);
    var challenges = new AccountEmailLoginChallengeRepository(dsl);
    Account seed = new Account();
    seed.setUsername("primary-" + UUID.randomUUID());
    seed.setEmail("primary-" + UUID.randomUUID() + "@example.test");
    seed.setPasswordHash(PASSWORD_HASH);
    seed.setLoginAuthModes(authModes);
    Account account =
        tx.execute(
            status -> {
              sources.initializeIssuerIfAbsent("firemud-account-service");
              Account saved = accounts.save(seed);
              challenges.save(challenge(saved, OTP_HASH, expired));
              return saved;
            });
    var issuance = new AccountGameplayDelegationIssuanceRepository(dsl, sources);
    // Unused AccountService surfaces are absent; every collaborator on this credential path is
    // real.
    var authority =
        new AccountServiceImpl(
            accounts,
            authorities,
            sources,
            outbox,
            new AccountPasswordResetOperationRepository(dsl),
            new AccountLogoutAllOperationRepository(dsl),
            new AccountSecurityStateOperationRepository(dsl),
            null,
            null,
            null,
            null,
            challenges,
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
            null,
            null,
            null,
            manager);
    var verifier = new AccountGameplayPrimaryCredentialVerifier(authority);
    var key =
        new CredentialDigestKey(
            "fixture-key",
            new SecretKeySpec(new byte[32], "HmacSHA256"),
            Instant.now().plusSeconds(3600));
    AccountGameplayCredentialRequestDigestKeySource keys =
        new AccountGameplayCredentialRequestDigestKeySource() {
          public CredentialDigestKey currentKey() {
            return key;
          }

          public CredentialDigestKey requireKey(String id) {
            if (!key.keyId().equals(id)) throw new IllegalStateException("Unknown fixture key");
            return key;
          }
        };
    var commit = mock(AccountGameplayDelegationIssuanceCommitService.class);
    var response = mock(AccountGameplayDelegationResponseRecoveryOwner.class);
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              // A separate connection must already observe the producer's committed SQL effects.
              DSLContext observation = DSL.using(dataSource, SQLDialect.POSTGRES);
              assertThat(
                      Objects.requireNonNull(
                              observation.fetchOne(
                                  "SELECT count(*) FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                                  (Object) invocation.getArgument(0)),
                              "Committed operation count row required")
                          .get(0, Long.class))
                  .isEqualTo(1L);
              throw new ExternalCommitStopped();
            })
        .when(commit)
        .commitPendingCandidate(any(UUID.class));
    var login =
        new AccountGameplayCanonicalLoginOwner(
            accounts, issuance, keys, commit, response, manager, Clock.systemUTC());
    return new Harness(dsl, tx, account, challenges, issuance, verifier, commit, response, login);
  }

  private static String hash(String secret) {
    var argon2 = Argon2Factory.create();
    char[] chars = secret.toCharArray();
    try {
      return argon2.hash(2, 65536, 1, chars);
    } finally {
      argon2.wipeArray(chars);
    }
  }

  private static final class ExternalCommitStopped extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  private record Harness(
      DSLContext dsl,
      TransactionTemplate tx,
      Account account,
      AccountEmailLoginChallengeRepository challenges,
      AccountGameplayDelegationIssuanceRepository issuance,
      AccountGameplayPrimaryCredentialVerifier verifier,
      AccountGameplayDelegationIssuanceCommitService commit,
      AccountGameplayDelegationResponseRecoveryOwner response,
      AccountGameplayCanonicalLoginOwner login) {
    long operationCount() {
      return Objects.requireNonNull(
              dsl.fetchOne("SELECT count(*) FROM account_gameplay_delegation_issuance_operations"),
              "Operation count row required")
          .get(0, Long.class);
    }

    Record challengeRow() {
      return dsl.fetchOne(
          "SELECT * FROM account_email_login_challenge WHERE account_id = ?", account.getId());
    }

    Record operationRow() {
      return dsl.fetchOne(
          "SELECT * FROM account_gameplay_delegation_issuance_operations WHERE account_uuid = ?",
          account.getAccountUuid());
    }
  }
}
