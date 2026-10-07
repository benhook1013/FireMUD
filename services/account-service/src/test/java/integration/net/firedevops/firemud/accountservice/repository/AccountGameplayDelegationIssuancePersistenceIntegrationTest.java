package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import io.grpc.Context;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.output.CommandOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.State;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.TokenIdentity;
import net.firedevops.firemud.accountservice.dto.AccountSecurityStateMutationRequest;
import net.firedevops.firemud.accountservice.dto.CanonicalGameplayLoginRequest;
import net.firedevops.firemud.accountservice.dto.GameplayCredentialSourceContext;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthModes;
import net.firedevops.firemud.accountservice.repository.AccountAuthEvidenceBundleRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.EventEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayCredentialRequestBinding;
import net.firedevops.firemud.accountservice.repository.AccountGameplayCredentialRequestBindingFixture;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.BoundTokenCandidate;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.PendingIntent;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.PendingRegistryCandidate;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.StaleAuthorityException;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.SealedCandidateObservation;
import net.firedevops.firemud.accountservice.repository.AccountGameplayTokenIdentityFenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayTokenIdentityFenceRepository.TokenRevokedException;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository.Capture;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCanonicalLoginOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCredentialRequestDigestKeySource;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCredentialRequestDigestKeySource.CredentialDigestKey;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCredentialRequestDigester;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationAuthorityProjection;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationCommitSignerFixture;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationCommittedIssuanceOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationIssuanceCommitService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.AccountCoordinationPinnedConnectionProvider;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.AcknowledgementRequirements;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseEnvelopeService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseRecoveryOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationTokenRegistry;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography.Binding;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeKeyring;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec.AccountState;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile.AccountSecurityCutoff;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

class AccountGameplayDelegationIssuancePersistenceIntegrationTest {
  private static final String SCHEMA_PREFIX = "acct_gameplay_issuance";
  private static final String EXTERNAL_POSTGRES_URL_ENV =
      "FIREMUD_ACCOUNT_SIGNER_TEST_POSTGRES_URL";
  private static final String CALLER_WORKLOAD = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String ACCOUNT_ISSUER = "firemud-account-service";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");
  private static String testJdbcUrl;
  private static String testJdbcUsername;
  private static String testJdbcPassword;
  private static volatile boolean startedOwnedContainer;

  @TempDir Path temporaryDirectory;

  @BeforeAll
  static void configureDatabase() {
    String externalUrl = System.getenv(EXTERNAL_POSTGRES_URL_ENV);
    if (externalUrl != null) {
      testJdbcUrl = validateExternalLoopbackPostgresUrl(externalUrl);
      testJdbcUsername = "postgres";
      testJdbcPassword = "";
      return;
    }
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(),
        "PostgreSQL proof requires the explicit loopback tunnel or an available Docker daemon");
    postgres.start();
    startedOwnedContainer = true;
    testJdbcUrl = postgres.getJdbcUrl();
    testJdbcUsername = postgres.getUsername();
    testJdbcPassword = postgres.getPassword();
  }

  @AfterAll
  static void stopOwnedContainer() {
    if (startedOwnedContainer) postgres.stop();
  }

  @Test
  void createsRequestIdentityFromCurrentAccountAuthorityAndReplaysItExactly() {
    TestContext context = newTestContext();
    UUID accountId = createAccount(context).getAccountUuid();
    AccountAuthorityGenerationRepository authorities =
        new AccountAuthorityGenerationRepository(context.dsl());
    AccountAuthoritySourceEvidenceRepository sources = sourceEvidence(context.dsl(), authorities);
    AccountGameplayDelegationIssuanceRepository repository =
        new AccountGameplayDelegationIssuanceRepository(context.dsl(), sources);
    UUID requestId = UUID.randomUUID();
    UUID callerContextId = UUID.randomUUID();

    var created =
        inTransaction(
            context,
            () ->
                repository.beginPendingForAccount(
                    requestId,
                    accountId,
                    CALLER_WORKLOAD,
                    callerContextId,
                    AccountGameplayCredentialRequestBindingFixture.binding()));
    var replay =
        inTransaction(
            context,
            () ->
                repository.beginPendingForAccount(
                    requestId,
                    accountId,
                    CALLER_WORKLOAD,
                    callerContextId,
                    AccountGameplayCredentialRequestBindingFixture.binding()));

    assertThat(created.created()).isTrue();
    assertThat(created.identity().accountId()).isEqualTo(accountId);
    assertThat(created.identity().requestId()).isEqualTo(requestId);
    assertThat(created.identity().callerWorkload()).isEqualTo(CALLER_WORKLOAD);
    assertThat(created.identity().callerContextId()).isEqualTo(callerContextId);
    assertThat(created.identity().operationId()).isEqualTo(created.operation().operationId());
    assertThat(created.identity().tokenJti()).isEqualTo(created.operation().tokenJti());
    assertThat(created.identity().expiresAtEpochSecond() - created.identity().issuedAtEpochSecond())
        .isEqualTo(GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS);
    assertThat(replay.created()).isFalse();
    assertThat(replay.identity()).isEqualTo(created.identity());
    assertThat(replay.operation()).isEqualTo(created.operation());

    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        repository.beginPendingForAccount(
                            requestId,
                            accountId,
                            "spiffe://firemud/ns/other/sa/game-session-service",
                            callerContextId,
                            AccountGameplayCredentialRequestBindingFixture.binding())))
        .isInstanceOf(
            AccountGameplayDelegationIssuanceRepository.IdempotencyConflictException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        repository.beginPendingForAccount(
                            requestId,
                            accountId,
                            CALLER_WORKLOAD,
                            UUID.randomUUID(),
                            AccountGameplayCredentialRequestBindingFixture.binding())))
        .isInstanceOf(
            AccountGameplayDelegationIssuanceRepository.IdempotencyConflictException.class);
    UUID otherAccountId = createAccount(context).getAccountUuid();
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        repository.beginPendingForAccount(
                            requestId,
                            otherAccountId,
                            CALLER_WORKLOAD,
                            callerContextId,
                            AccountGameplayCredentialRequestBindingFixture.binding())))
        .isInstanceOf(
            AccountGameplayDelegationIssuanceRepository.IdempotencyConflictException.class);

    Record operationCount =
        Objects.requireNonNull(
            context
                .dsl()
                .fetchOne(
                    "SELECT count(*) FROM account_gameplay_delegation_issuance_operations "
                        + "WHERE request_id = ?",
                    requestId),
            "Operation count query must return a row");
    assertThat(operationCount.get(0, Long.class)).isEqualTo(1L);
  }

  @Test
  void credentialBindingMigrationRetainsLegacyEvidenceAndRequiresImmutableBoundRows()
      throws Exception {
    // V78 is the existing pre-binding schema in this checkout; V79/V80 are reserved elsewhere.
    TestContext context = newTestContextAtVersion("78");
    Account account = createAccount(context);
    AccountAuthorityGenerationRepository authorities =
        new AccountAuthorityGenerationRepository(context.dsl());
    AccountAuthoritySourceEvidenceRepository sources = sourceEvidence(context.dsl(), authorities);
    PendingIntent retainedIntent =
        intent(
            currentSnapshot(context, sources, account.getAccountUuid()), account.getAccountUuid());
    insertRetainedUnboundPendingOperation(context.dsl(), retainedIntent);
    Record beforeMigration =
        context
            .dsl()
            .fetchOne(
                "SELECT * FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                retainedIntent.requestId());
    assertThat(beforeMigration).isNotNull();

    migrateTestSchemaToLatest(context);

    Record retained =
        context
            .dsl()
            .fetchOne(
                "SELECT * FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                retainedIntent.requestId());
    assertThat(retained).isNotNull();
    assertThat(retained.intoMap()).containsAllEntriesOf(beforeMigration.intoMap());
    assertThat(retained.get("operation_id", UUID.class))
        .isEqualTo(beforeMigration.get("operation_id", UUID.class));
    assertThat(retained.get("request_digest_version", Short.class)).isEqualTo((short) 1);
    assertThat(retained.get("request_digest", String.class))
        .isEqualTo(beforeMigration.get("request_digest", String.class));
    assertThat(retained.get("status", String.class)).isEqualTo("PENDING");
    assertThat(retained.get("token_hash", String.class)).isNull();
    assertThat(retained.get("signer_kid", String.class)).isNull();
    assertThat(retained.get("signer_generation", String.class)).isNull();
    assertThat(retained.get("pending_registry_candidate_bytes", byte[].class)).isNull();
    assertThat(retained.get("credential_request_digest_version", Short.class)).isNull();
    assertThat(retained.get("credential_digest_key_id", String.class)).isNull();
    assertThat(retained.get("credential_request_digest", String.class)).isNull();

    AccountGameplayDelegationIssuanceRepository repository =
        new AccountGameplayDelegationIssuanceRepository(context.dsl(), sources);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () -> repository.readCurrentGameplayLoginReplay(retainedIntent.requestId())))
        .isInstanceOf(
            AccountGameplayDelegationIssuanceRepository.StorageUnavailableException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_gameplay_delegation_issuance_operations "
                                    + "SET token_hash = ?, signer_kid = ?, signer_generation = ?, "
                                    + "pending_registry_candidate_bytes = ? WHERE request_id = ?",
                                "cd".repeat(32),
                                "integration-key",
                                "1",
                                new byte[] {1, 2, 3},
                                retainedIntent.requestId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_gameplay_delegation_issuance_operations "
                                    + "SET status = 'COMMITTED' WHERE request_id = ?",
                                retainedIntent.requestId())))
        .isInstanceOf(DataAccessException.class);
    Record retainedAfterDeniedAdvances =
        context
            .dsl()
            .fetchOne(
                "SELECT status, token_hash, pending_registry_candidate_bytes, "
                    + "credential_request_digest_version, credential_digest_key_id, "
                    + "credential_request_digest "
                    + "FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                retainedIntent.requestId());
    assertThat(retainedAfterDeniedAdvances.get("status", String.class)).isEqualTo("PENDING");
    assertThat(retainedAfterDeniedAdvances.get("token_hash", String.class)).isNull();
    assertThat(retainedAfterDeniedAdvances.get("pending_registry_candidate_bytes", byte[].class))
        .isNull();
    assertThat(retainedAfterDeniedAdvances.get("credential_request_digest_version", Short.class))
        .isNull();
    assertThat(retainedAfterDeniedAdvances.get("credential_digest_key_id", String.class)).isNull();
    assertThat(retainedAfterDeniedAdvances.get("credential_request_digest", String.class)).isNull();

    var binding = AccountGameplayCredentialRequestBindingFixture.binding();
    UUID newRequestId = UUID.randomUUID();
    inTransaction(
        context,
        () ->
            repository.beginPendingForAccount(
                newRequestId,
                account.getAccountUuid(),
                CALLER_WORKLOAD,
                UUID.randomUUID(),
                binding));
    Record bound =
        context
            .dsl()
            .fetchOne(
                "SELECT request_digest_version, credential_request_digest_version, "
                    + "credential_digest_key_id, credential_request_digest "
                    + "FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                newRequestId);
    assertThat(bound.get("request_digest_version", Short.class)).isEqualTo((short) 2);
    assertThat(bound.get("credential_request_digest_version", Short.class))
        .isEqualTo((short) binding.digestSchemaVersion());
    assertThat(bound.get("credential_digest_key_id", String.class))
        .isEqualTo(binding.digestKeyId());
    assertThat(bound.get("credential_request_digest", String.class))
        .isEqualTo(binding.credentialRequestDigest());
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_gameplay_delegation_issuance_operations "
                                    + "SET credential_request_digest = ? WHERE request_id = ?",
                                "cd".repeat(32),
                                newRequestId)))
        .isInstanceOf(DataAccessException.class);
    Record boundAfterDeniedMutation =
        context
            .dsl()
            .fetchOne(
                "SELECT credential_request_digest_version, credential_digest_key_id, "
                    + "credential_request_digest "
                    + "FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                newRequestId);
    assertThat(boundAfterDeniedMutation.get("credential_request_digest_version", Short.class))
        .isEqualTo((short) binding.digestSchemaVersion());
    assertThat(boundAfterDeniedMutation.get("credential_digest_key_id", String.class))
        .isEqualTo(binding.digestKeyId());
    assertThat(boundAfterDeniedMutation.get("credential_request_digest", String.class))
        .isEqualTo(binding.credentialRequestDigest());
  }

  @Test
  void deterministicOtpFixtureAndCanonicalLoginCommitBeforeReturningCredential() throws Exception {
    TestContext context = newTestContext();
    AccountAuthorityGenerationRepository authorities =
        new AccountAuthorityGenerationRepository(context.dsl());
    AccountAuthoritySourceEvidenceRepository sources = sourceEvidence(context.dsl(), authorities);
    Account account = createEmailOtpAccount(context, sources);
    AccountEmailLoginChallengeRepository challenges =
        new AccountEmailLoginChallengeRepository(context.dsl());
    AccountEmailLoginChallenge challenge =
        persistEmailLoginChallenge(context, challenges, account.getId(), "123456");
    AccountAuthEvidenceBundleRepository bundles =
        new AccountAuthEvidenceBundleRepository(
            context.dsl(), authorities, new AccountAuthorityOutboxRepository(context.dsl()));
    AccountGameplayDelegationIssuanceRepository issuances =
        new AccountGameplayDelegationIssuanceRepository(context.dsl(), sources, bundles);
    CommitHarness harness =
        buildCommitHarness(
            context, account.getAccountUuid(), sources, bundles, issuances, null, null);
    UUID requestId = UUID.randomUUID();
    UUID callerContextId = UUID.randomUUID();
    CanonicalGameplayLoginRequest request =
        canonicalLoginRequest(account.getEmail(), "123456", requestId, callerContextId);

    var result =
        withAuthenticatedGameSession(
            () ->
                harness
                    .loginOwner()
                    .authenticate(
                        request,
                        CALLER_WORKLOAD,
                        deterministicOtpCredentialVerifier(challenges, "123456")));

    assertThat(result.accountId()).isEqualTo(account.getAccountUuid());
    assertThat(result.tokenJti()).isNotNull();
    assertThat(result.compactJwtBytes()).isNotEmpty();
    assertThat(result.expiresAtEpochSecond()).isPositive();
    assertThat(result.toString()).doesNotContain("123456");
    assertThat(challenges.findByAccountId(account.getId())).isEmpty();
    Record stored =
        context
            .dsl()
            .fetchOne(
                "SELECT status, account_uuid, token_jti, caller_context_id "
                    + "FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                requestId);
    assertThat(stored).isNotNull();
    assertThat(stored.get("status", String.class)).isEqualTo("COMMITTED");
    assertThat(stored.get("account_uuid", UUID.class)).isEqualTo(account.getAccountUuid());
    assertThat(stored.get("token_jti", UUID.class)).isEqualTo(result.tokenJti());
    assertThat(stored.get("caller_context_id", UUID.class)).isEqualTo(callerContextId);
    assertThat(harness.redis().registeredRecord()).isNotEmpty();
    assertThat(challenge.getId()).isNotNull();
  }

  @Test
  void failureAfterOtpDeleteRollsBackCanonicalLoginAndOtpConsumption() throws Exception {
    TestContext context = newTestContext();
    AccountAuthorityGenerationRepository authorities =
        new AccountAuthorityGenerationRepository(context.dsl());
    AccountAuthoritySourceEvidenceRepository sources = sourceEvidence(context.dsl(), authorities);
    Account account = createEmailOtpAccount(context, sources);
    AccountEmailLoginChallengeRepository actualChallenges =
        new AccountEmailLoginChallengeRepository(context.dsl());
    AccountEmailLoginChallenge challenge =
        persistEmailLoginChallenge(context, actualChallenges, account.getId(), "654321");
    AccountEmailLoginChallengeRepository challenges = spy(actualChallenges);
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("simulated failure after OTP deletion");
            })
        .when(challenges)
        .delete(any(AccountEmailLoginChallenge.class));
    AccountAuthEvidenceBundleRepository bundles =
        new AccountAuthEvidenceBundleRepository(
            context.dsl(), authorities, new AccountAuthorityOutboxRepository(context.dsl()));
    AccountGameplayDelegationIssuanceRepository issuances =
        new AccountGameplayDelegationIssuanceRepository(context.dsl(), sources, bundles);
    CommitHarness harness =
        buildCommitHarness(
            context, account.getAccountUuid(), sources, bundles, issuances, null, null);
    UUID requestId = UUID.randomUUID();
    UUID callerContextId = UUID.randomUUID();
    CanonicalGameplayLoginRequest request =
        canonicalLoginRequest(account.getEmail(), "654321", requestId, callerContextId);

    assertThatThrownBy(
            () ->
                withAuthenticatedGameSession(
                    () ->
                        harness
                            .loginOwner()
                            .authenticate(
                                request,
                                CALLER_WORKLOAD,
                                deterministicOtpCredentialVerifier(challenges, "654321"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated failure");

    AccountEmailLoginChallenge restored =
        actualChallenges.findByAccountId(account.getId()).orElseThrow();
    assertThat(restored.getId()).isEqualTo(challenge.getId());
    assertThat(restored.getCodeHash()).isEqualTo(challenge.getCodeHash());
    Record operationCount =
        Objects.requireNonNull(
            context
                .dsl()
                .fetchOne(
                    "SELECT count(*) FROM account_gameplay_delegation_issuance_operations "
                        + "WHERE request_id = ?",
                    requestId),
            "Operation count query must return a row");
    assertThat(operationCount.get(0, Long.class)).isEqualTo(0L);
    assertThat(harness.redis().registeredRecord()).isNull();
  }

  @Test
  void keepsExactPendingIntentAndCandidateImmutableAndRejectsAdvancedAuthority() throws Exception {
    TestContext context = newTestContext();
    DSLContext dsl = context.dsl();
    UUID accountId = createAccount(context).getAccountUuid();
    AccountAuthorityGenerationRepository authorities =
        new AccountAuthorityGenerationRepository(dsl);
    AccountAuthoritySourceEvidenceRepository sources = sourceEvidence(dsl, authorities);
    AccountAuthEvidenceBundleRepository bundles =
        new AccountAuthEvidenceBundleRepository(
            dsl, authorities, new AccountAuthorityOutboxRepository(dsl));
    AccountGameplayDelegationIssuanceRepository repository =
        new AccountGameplayDelegationIssuanceRepository(dsl, sources, bundles);
    IssuerAccountSourceSnapshot firstSnapshot = currentSnapshot(context, sources, accountId);
    PendingIntent firstIntent = intent(firstSnapshot, accountId);

    var first = inTransaction(context, () -> repository.beginPending(firstIntent));
    var exactRetry = inTransaction(context, () -> repository.beginPending(firstIntent));
    assertThat(exactRetry).isEqualTo(first);
    assertThat(first.candidateBound()).isFalse();
    assertThat(readCandidate(dsl, firstIntent.requestId()).authorityTupleBytes())
        .isEqualTo(canonicalBytes(GameSessionAccountDelegationProfile.authorityTuple(1L, 1L)));

    PendingIntent changedContext =
        new PendingIntent(
            firstIntent.operationId(),
            firstIntent.requestId(),
            firstIntent.callerWorkload(),
            UUID.randomUUID(),
            firstIntent.tokenJti(),
            firstIntent.issuedAtEpochSecond(),
            firstIntent.notBeforeEpochSecond(),
            firstIntent.expiresAtEpochSecond(),
            firstIntent.authoritySnapshot(),
            firstIntent.credentialRequestBinding());
    assertThatThrownBy(() -> inTransaction(context, () -> repository.beginPending(changedContext)))
        .isInstanceOf(
            AccountGameplayDelegationIssuanceRepository.IdempotencyConflictException.class);

    String mismatchedTimeCandidate =
        compactCandidate(firstIntent, firstIntent.expiresAtEpochSecond() + 1L);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        repository.bindSignedCandidate(
                            firstIntent.requestId(), mismatchedTimeCandidate, "1")))
        .isInstanceOf(
            AccountGameplayDelegationIssuanceRepository.IdempotencyConflictException.class);
    assertThat(readCandidate(dsl, firstIntent.requestId()).tokenHash()).isNull();

    String compactJwt = compactCandidate(firstIntent);
    AccountResponseEnvelopeCryptography responseCryptography =
        mock(AccountResponseEnvelopeCryptography.class);
    byte[] ciphertext = new byte[] {3, 1, 4, 1, 5};
    when(responseCryptography.encrypt(
            any(Binding.class), any(byte[].class), any(java.time.Instant.class)))
        .thenReturn(new EncryptedResponseEnvelope(ciphertext));
    AccountGameplayDelegationResponseEnvelopeRepository responses =
        new AccountGameplayDelegationResponseEnvelopeRepository(
            dsl, sources, bundles, responseCryptography);
    CallerIdentity caller =
        new CallerIdentity(firstIntent.callerWorkload(), firstIntent.callerContextId());
    BindReadSeal boundReadSeal =
        inTransaction(
            context,
            () -> {
              BoundTokenCandidate boundCandidate =
                  repository.bindSignedCandidate(firstIntent.requestId(), compactJwt, "1");
              PendingRegistryCandidate persistedCandidate =
                  repository.readPendingRegistryCandidate(firstIntent.requestId());
              SealedCandidateObservation sealed =
                  responses.sealPendingCandidate(firstIntent.requestId(), caller, compactJwt);
              return new BindReadSeal(boundCandidate, persistedCandidate, sealed);
            });
    BoundTokenCandidate bound = boundReadSeal.bound();
    assertThat(boundReadSeal.persisted().tokenHash()).isEqualTo(bound.tokenHash());
    assertThat(boundReadSeal.persisted().canonicalRecordBytes()).isNotEmpty();
    assertThat(boundReadSeal.sealed().operationId()).isEqualTo(firstIntent.operationId());
    assertThat(boundReadSeal.sealed().requestId()).isEqualTo(firstIntent.requestId());
    assertThat(boundReadSeal.sealed().envelopeBytesLength()).isPositive();
    var boundRetry =
        inTransaction(
            context,
            () -> repository.bindSignedCandidate(firstIntent.requestId(), compactJwt, "1"));
    assertThat(boundRetry).isEqualTo(bound);
    assertThat(bound.tokenHash()).hasSize(64).matches("[0-9a-f]{64}");

    RecordData persisted = readCandidate(dsl, firstIntent.requestId());
    assertThat(new String(persisted.candidateBytes(), StandardCharsets.UTF_8))
        .doesNotContain(compactJwt);
    assertThat(persisted.status()).isEqualTo("PENDING");
    assertThat(persisted.tokenHash()).isEqualTo(bound.tokenHash());
    assertThat(persisted.authorityTupleBytes())
        .isEqualTo(canonicalBytes(GameSessionAccountDelegationProfile.authorityTuple(1L, 1L)));
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE account_gameplay_delegation_issuance_operations "
                        + "SET token_hash = ? WHERE request_id = ?",
                    "f".repeat(64),
                    firstIntent.requestId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("token identity may be bound exactly once");

    advanceEmailVerifiedThroughOwner(dsl, context.transaction(), sources, accountId);
    assertThatThrownBy(() -> inTransaction(context, () -> repository.beginPending(firstIntent)))
        .isInstanceOf(StaleAuthorityException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () -> repository.bindSignedCandidate(firstIntent.requestId(), compactJwt, "1")))
        .isInstanceOf(StaleAuthorityException.class);
    RecordData accountAdvancePersisted = readCandidate(dsl, firstIntent.requestId());
    assertThat(accountAdvancePersisted.status()).isEqualTo(persisted.status());
    assertThat(accountAdvancePersisted.tokenHash()).isEqualTo(persisted.tokenHash());
    assertThat(accountAdvancePersisted.candidateBytes())
        .containsExactly(persisted.candidateBytes());
    assertThat(accountAdvancePersisted.authorityTupleBytes())
        .containsExactly(persisted.authorityTupleBytes());

    IssuerAccountSourceSnapshot afterAccountAdvance = currentSnapshot(context, sources, accountId);
    PendingIntent secondIntent = intent(afterAccountAdvance, accountId);
    inTransaction(context, () -> repository.beginPending(secondIntent));
    assertThat(secondIntent.authoritySnapshot().accountSecurityCutoff()).isPresent();
    byte[] advancedAuthorityTuple =
        canonicalBytes(
            GameSessionAccountDelegationProfile.authorityTuple(
                secondIntent.authoritySnapshot().issuerGeneration(),
                secondIntent.authoritySnapshot().accountGeneration(),
                secondIntent.authoritySnapshot().accountSecurityCutoff()));
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE account_gameplay_delegation_issuance_operations "
                        + "SET authority_tuple_canonical_bytes = ? WHERE request_id = ?",
                    canonicalBytes(
                        authorityTupleWithoutCutoff(
                            secondIntent.authoritySnapshot().issuerGeneration(),
                            secondIntent.authoritySnapshot().accountGeneration())),
                    secondIntent.requestId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("authority snapshot is stale or missing");
    assertThat(readCandidate(dsl, secondIntent.requestId()).authorityTupleBytes())
        .containsExactly(advancedAuthorityTuple);
    String advancedJwt = compactCandidate(secondIntent);
    var advancedBound =
        inTransaction(
            context,
            () -> repository.bindSignedCandidate(secondIntent.requestId(), advancedJwt, "1"));
    var advancedBoundRetry =
        inTransaction(
            context,
            () -> repository.bindSignedCandidate(secondIntent.requestId(), advancedJwt, "1"));
    assertThat(advancedBoundRetry).isEqualTo(advancedBound);
    RecordData advancedPersisted = readCandidate(dsl, secondIntent.requestId());

    byte[] otherwiseShapedCommitProof =
        canonicalBytes(
            Map.ofEntries(
                Map.entry("schema", "account-game-session-delegation-commit-proof/v1"),
                Map.entry("identity", Map.of()),
                Map.entry("tokenSha256", advancedBound.tokenHash()),
                Map.entry("registryRecordSha256", "a".repeat(64)),
                Map.entry("registry", Map.of()),
                Map.entry("bundle", Map.of()),
                Map.entry("authority", Map.of()),
                Map.entry("signer", Map.of()),
                Map.entry("envelope", Map.of())));
    String otherwiseShapedProofSha256 = sha256Hex(otherwiseShapedCommitProof);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO account_gameplay_delegation_issuance_operations ("
                        + "operation_id, request_id, account_uuid, caller_workload, caller_context_id, "
                        + "credential_request_digest_version, credential_digest_key_id, credential_request_digest, "
                        + "request_digest_version, request_digest, token_jti, token_generation, "
                        + "issued_at_epoch_second, not_before_epoch_second, expires_at_epoch_second, "
                        + "authority_issuer_generation, authority_issuer_source_version, "
                        + "authority_account_generation, authority_account_source_version, issuance_fence, "
                        + "issuance_fence_source_version, authority_tuple_canonical_bytes, "
                        + "membership_version_canonical_bytes, authority_source_versions_canonical_bytes, "
                        + "status, token_hash, signer_kid, signer_generation, "
                        + "pending_registry_candidate_bytes, commit_proof_version, commit_proof_sha256, "
                        + "commit_proof_canonical_bytes, committed_at) "
                        + "SELECT operation_id, request_id, account_uuid, caller_workload, caller_context_id, "
                        + "credential_request_digest_version, credential_digest_key_id, credential_request_digest, "
                        + "request_digest_version, request_digest, token_jti, token_generation, "
                        + "issued_at_epoch_second, not_before_epoch_second, expires_at_epoch_second, "
                        + "authority_issuer_generation, authority_issuer_source_version, "
                        + "authority_account_generation, authority_account_source_version, issuance_fence, "
                        + "issuance_fence_source_version, authority_tuple_canonical_bytes, "
                        + "membership_version_canonical_bytes, authority_source_versions_canonical_bytes, "
                        + "'COMMITTED', token_hash, signer_kid, signer_generation, "
                        + "pending_registry_candidate_bytes, 1, ?, ?, CURRENT_TIMESTAMP "
                        + "FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                    otherwiseShapedProofSha256,
                    otherwiseShapedCommitProof,
                    secondIntent.requestId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("must begin as unbound PENDING intent");
    RecordData afterCommittedInsert = readCandidate(dsl, secondIntent.requestId());
    assertThat(afterCommittedInsert.status()).isEqualTo("PENDING");
    assertThat(afterCommittedInsert.tokenHash()).isEqualTo(advancedBound.tokenHash());
    assertThat(afterCommittedInsert.candidateBytes())
        .containsExactly(advancedPersisted.candidateBytes());

    byte[] forgedCommitProof = "{}".getBytes(StandardCharsets.US_ASCII);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE account_gameplay_delegation_issuance_operations "
                        + "SET status = 'COMMITTED', commit_proof_version = 1, "
                        + "commit_proof_sha256 = ?, commit_proof_canonical_bytes = ?, "
                        + "committed_at = CURRENT_TIMESTAMP WHERE request_id = ?",
                    "a".repeat(64),
                    forgedCommitProof,
                    secondIntent.requestId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("unknown or missing fields");
    RecordData afterForgedCommit = readCandidate(dsl, secondIntent.requestId());
    assertThat(afterForgedCommit.status()).isEqualTo("PENDING");
    assertThat(afterForgedCommit.tokenHash()).isEqualTo(advancedBound.tokenHash());
    assertThat(afterForgedCommit.candidateBytes())
        .containsExactly(advancedPersisted.candidateBytes());
    var commitColumns =
        dsl.resultQuery(
                "SELECT commit_proof_version, commit_proof_sha256, "
                    + "commit_proof_canonical_bytes, committed_at "
                    + "FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                secondIntent.requestId())
            .fetchOne();
    assertThat(commitColumns.get("commit_proof_version")).isNull();
    assertThat(commitColumns.get("commit_proof_sha256")).isNull();
    assertThat(commitColumns.get("commit_proof_canonical_bytes")).isNull();
    assertThat(commitColumns.get("committed_at")).isNull();

    var advancedReadback =
        inTransaction(
            context, () -> repository.readPendingRegistryCandidate(secondIntent.requestId()));
    assertThat(advancedReadback.authoritySnapshot().accountSecurityCutoff())
        .isEqualTo(secondIntent.authoritySnapshot().accountSecurityCutoff());
    assertThat(advancedPersisted.authorityTupleBytes()).containsExactly(advancedAuthorityTuple);
    assertThat(new String(advancedPersisted.candidateBytes(), StandardCharsets.UTF_8))
        .contains("\"accountSecurityCutoff\"")
        .doesNotContain(advancedJwt);
    inTransaction(
        context,
        () ->
            sources.appendIssuerAuthorityChange(
                ACCOUNT_ISSUER, "SIGNER_COMPROMISE", UUID.randomUUID().toString()));
    assertThatThrownBy(() -> inTransaction(context, () -> repository.beginPending(secondIntent)))
        .isInstanceOf(StaleAuthorityException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () -> repository.readPendingRegistryCandidate(secondIntent.requestId())))
        .isInstanceOf(StaleAuthorityException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    context,
                    () ->
                        repository.bindSignedCandidate(secondIntent.requestId(), advancedJwt, "1")))
        .isInstanceOf(StaleAuthorityException.class);
    RecordData afterIssuerAdvance = readCandidate(dsl, secondIntent.requestId());
    assertThat(afterIssuerAdvance.status()).isEqualTo(advancedPersisted.status());
    assertThat(afterIssuerAdvance.tokenHash()).isEqualTo(advancedPersisted.tokenHash());
    assertThat(afterIssuerAdvance.candidateBytes())
        .containsExactly(advancedPersisted.candidateBytes());
    assertThat(afterIssuerAdvance.authorityTupleBytes())
        .containsExactly(advancedPersisted.authorityTupleBytes());
  }

  @Test
  void realCommittedIssuanceEstablishesExactSqlFenceAndIndependentRetryReadback() throws Exception {
    CommitHarness harness = newCommitHarness(CommitHook.NONE, false);
    assertThat(
            Objects.requireNonNull(
                    harness
                        .dsl()
                        .fetchOne("SELECT count(*) FROM account_gameplay_token_identity_fences"),
                    "token identity fence count query must return a row")
                .get(0, Long.class))
        .isZero();
    harness.service().commitPendingCandidate(harness.pending().requestId());
    TokenIdentity identity = tokenFenceIdentity(harness);
    AccountGameplayTokenIdentityFenceRepository fences =
        new AccountGameplayTokenIdentityFenceRepository(harness.dsl());
    var active = inTransaction(harness.context(), () -> fences.requireActiveForUpdate(identity));
    assertThat(active.state()).isEqualTo(State.ACTIVE);
    assertThat(active.tokenIdentityFence()).isEqualTo(1L);
    assertThat(active.identity()).isEqualTo(identity);
    assertThat(harness.issuance().readCommittedProof(identity.issuanceRequestId())).isPresent();
    assertThat(harness.service().commitPendingCandidate(identity.issuanceRequestId()).outcome())
        .isEqualTo(AccountGameplayDelegationIssuanceCommitService.Outcome.EXACT_RETRY);
    assertThat(
            Objects.requireNonNull(
                    harness
                        .dsl()
                        .fetchOne("SELECT count(*) FROM account_gameplay_token_identity_fences"),
                    "token identity fence count query must return a row")
                .get(0, Long.class))
        .isEqualTo(1L);
  }

  @Test
  void durableExactRevocationIntentDeniesAdmissionAndCommitRecoveryWithoutRecreation()
      throws Exception {
    CommitHarness harness = newCommitHarness(CommitHook.NONE, false);
    harness.service().commitPendingCandidate(harness.pending().requestId());
    TokenIdentity identity = tokenFenceIdentity(harness);
    AccountGameplayTokenIdentityFenceRepository fences =
        new AccountGameplayTokenIdentityFenceRepository(harness.dsl());
    UUID requestId = UUID.randomUUID();
    String digest =
        AccountGameplayTokenIdentityFenceRepository.revocationDigest(identity, requestId);
    var pending =
        inTransaction(
            harness.context(), () -> fences.beginRevocationIntent(identity, requestId, digest));
    assertThat(pending.state()).isEqualTo(State.PENDING);
    assertThat(pending.tokenIdentityFence()).isEqualTo(2L);
    assertThat(
            inTransaction(
                harness.context(), () -> fences.beginRevocationIntent(identity, requestId, digest)))
        .isEqualTo(pending);
    assertThatThrownBy(
            () -> inTransaction(harness.context(), () -> fences.requireActiveForUpdate(identity)))
        .isInstanceOf(TokenRevokedException.class);
    assertThatThrownBy(() -> harness.issuance().readCommittedProof(identity.issuanceRequestId()))
        .isInstanceOf(TokenRevokedException.class);
    UUID conflictingRequest = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                inTransaction(
                    harness.context(),
                    () ->
                        fences.beginRevocationIntent(
                            identity,
                            conflictingRequest,
                            AccountGameplayTokenIdentityFenceRepository.revocationDigest(
                                identity, conflictingRequest))))
        .isInstanceOf(
            AccountGameplayTokenIdentityFenceRepository.RevocationConflictException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    harness.context(),
                    () ->
                        harness
                            .dsl()
                            .execute(
                                "UPDATE account_gameplay_token_identity_fences SET state = 'ACTIVE', "
                                    + "token_identity_fence = 1, revocation_request_id = NULL, revocation_digest = NULL "
                                    + "WHERE operation_id = ?",
                                identity.operationId())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    harness.context(),
                    () ->
                        harness
                            .dsl()
                            .execute(
                                "DELETE FROM account_gameplay_token_identity_fences WHERE operation_id = ?",
                                identity.operationId())))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            Objects.requireNonNull(
                    harness
                        .dsl()
                        .fetchOne(
                            "SELECT state FROM account_gameplay_token_identity_fences "
                                + "WHERE operation_id = ?",
                            identity.operationId()),
                    "token identity fence query must return a row")
                .get(0, String.class))
        .isEqualTo("PENDING");
  }

  @Test
  void racingAdmissionWaitsForAccountRevocationTransactionThenObservesPendingFence()
      throws Exception {
    CommitHarness harness = newCommitHarness(CommitHook.NONE, false);
    harness.service().commitPendingCandidate(harness.pending().requestId());
    TokenIdentity identity = tokenFenceIdentity(harness);
    AccountGameplayTokenIdentityFenceRepository fences =
        new AccountGameplayTokenIdentityFenceRepository(harness.dsl());
    CountDownLatch pendingWritten = new CountDownLatch(1);
    CountDownLatch releaseRevocation = new CountDownLatch(1);
    CountDownLatch admissionStarted = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var revocation =
          executor.submit(
              () ->
                  inTransaction(
                      harness.context(),
                      () -> {
                        UUID requestId = UUID.randomUUID();
                        var pending =
                            fences.beginRevocationIntent(
                                identity,
                                requestId,
                                AccountGameplayTokenIdentityFenceRepository.revocationDigest(
                                    identity, requestId));
                        pendingWritten.countDown();
                        try {
                          if (!releaseRevocation.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("Admission not released");
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        return pending;
                      }));
      assertThat(pendingWritten.await(10, TimeUnit.SECONDS)).isTrue();
      var admission =
          executor.submit(
              () -> {
                admissionStarted.countDown();
                return inTransaction(
                    harness.context(), () -> fences.requireActiveForUpdate(identity));
              });
      assertThat(admissionStarted.await(10, TimeUnit.SECONDS)).isTrue();
      // The uncommitted PENDING row is invisible; serialization must block on the Account lock.
      assertThatThrownBy(() -> admission.get(200, TimeUnit.MILLISECONDS))
          .isInstanceOf(java.util.concurrent.TimeoutException.class);
      releaseRevocation.countDown();
      assertThat(revocation.get(10, TimeUnit.SECONDS).state()).isEqualTo(State.PENDING);
      assertThatThrownBy(() -> admission.get(10, TimeUnit.SECONDS))
          .isInstanceOf(java.util.concurrent.ExecutionException.class)
          .hasCauseInstanceOf(TokenRevokedException.class);
    } finally {
      releaseRevocation.countDown();
      executor.shutdownNow();
    }
  }

  private static TokenIdentity tokenFenceIdentity(CommitHarness harness) {
    Record row =
        Objects.requireNonNull(
            harness
                .dsl()
                .fetchOne(
                    "SELECT token_hash FROM account_gameplay_delegation_issuance_operations "
                        + "WHERE request_id = ?",
                    harness.pending().requestId()),
            "committed token candidate query must return a row");
    return new TokenIdentity(
        harness.accountId(),
        harness.pending().operationId(),
        harness.pending().requestId(),
        row.get("token_hash", String.class),
        harness.pending().tokenJti(),
        harness.pending().notBeforeEpochSecond(),
        1L,
        harness.pending().authoritySnapshot().issuanceFence());
  }

  @Test
  void signerProducedRsaProofCommitsWithExactOwnerEvidenceAndExactRetryDoesNotRepeatNetworkWork()
      throws Exception {
    CommitHarness harness = newCommitHarness(CommitHook.NONE, false, true);

    var committed = harness.service().commitPendingCandidate(harness.pending().requestId());

    assertThat(committed.outcome())
        .isEqualTo(AccountGameplayDelegationIssuanceCommitService.Outcome.COMMITTED);
    assertThat(committed.operationId()).isEqualTo(harness.pending().operationId());
    assertThat(committed.requestId()).isEqualTo(harness.pending().requestId());
    assertThat(committed.accountId()).isEqualTo(harness.accountId());
    assertThat(committed.proofSha256()).matches("[0-9a-f]{64}");
    var row =
        harness
            .dsl()
            .resultQuery(
                "SELECT status, token_hash, pending_registry_candidate_bytes, "
                    + "commit_proof_sha256, commit_proof_canonical_bytes, committed_at "
                    + "FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                harness.pending().requestId())
            .fetchOne();
    assertThat(row.get("status", String.class)).isEqualTo("COMMITTED");
    assertThat(row.get("token_hash", String.class)).matches("[0-9a-f]{64}");
    assertThat(row.get("pending_registry_candidate_bytes", byte[].class)).isNotEmpty();
    byte[] persistedProof = row.get("commit_proof_canonical_bytes", byte[].class);
    assertThat(row.get("commit_proof_sha256", String.class)).isEqualTo(committed.proofSha256());
    assertThat(row.get("committed_at")).isNotNull();
    assertThat(new String(persistedProof, StandardCharsets.UTF_8))
        .contains("account-game-session-delegation-commit-proof/v1")
        .doesNotContain("eyJ");
    assertThat(harness.pending().authoritySnapshot().accountSecurityCutoff()).isPresent();
    assertThat(harness.redis().registeredRecord()).isNotEmpty();
    assertThat(new String(harness.redis().registeredRecord(), StandardCharsets.UTF_8))
        .doesNotContain("eyJ");

    CanonicalGameplayLoginRequest exactLoginRetry = harness.loginRequest();
    AtomicInteger credentialVerifierCalls = new AtomicInteger();
    var firstLoginReplay =
        withAuthenticatedGameSession(
            () ->
                harness
                    .loginOwner()
                    .authenticate(
                        exactLoginRetry,
                        CALLER_WORKLOAD,
                        verifierMustNotRun(credentialVerifierCalls)));
    var exactLoginRetryResult =
        withAuthenticatedGameSession(
            () ->
                harness
                    .loginOwner()
                    .authenticate(
                        exactLoginRetry,
                        CALLER_WORKLOAD,
                        verifierMustNotRun(credentialVerifierCalls)));
    assertThat(firstLoginReplay.accountId()).isEqualTo(harness.accountId());
    assertThat(firstLoginReplay.tokenJti()).isEqualTo(harness.pending().tokenJti());
    assertThat(firstLoginReplay.tokenSha256()).isEqualTo(row.get("token_hash", String.class));
    assertThat(firstLoginReplay.compactJwtBytes())
        .containsExactly(exactLoginRetryResult.compactJwtBytes());
    assertThat(firstLoginReplay.toString())
        .doesNotContain("already-consumed-credential", "eyJ", committed.proofSha256());
    assertThat(credentialVerifierCalls).hasValue(0);
    assertThat(
            Objects.requireNonNull(
                    harness
                        .dsl()
                        .fetchOne(
                            "SELECT status FROM account_gameplay_delegation_issuance_operations "
                                + "WHERE request_id = ?",
                            harness.pending().requestId()),
                    "issuance status query must return a row")
                .get("status", String.class))
        .isEqualTo("COMMITTED");

    GameplayCredentialSourceContext sourceContext = exactLoginRetry.sourceContext();
    CanonicalGameplayLoginRequest changedCallerContext =
        new CanonicalGameplayLoginRequest(
            exactLoginRetry.requestId(),
            exactLoginRetry.email(),
            exactLoginRetry.credential(),
            new GameplayCredentialSourceContext(
                UUID.randomUUID(),
                sourceContext.canonicalClientAddress(),
                sourceContext.transportClass()));
    assertThatThrownBy(
            () ->
                withAuthenticatedGameSession(
                    () ->
                        harness
                            .loginOwner()
                            .authenticate(
                                changedCallerContext,
                                CALLER_WORKLOAD,
                                verifierMustNotRun(credentialVerifierCalls))))
        .isInstanceOf(
            AccountGameplayDelegationIssuanceRepository.IdempotencyConflictException.class);
    assertThat(credentialVerifierCalls).hasValue(0);

    AccountGameplayDelegationIssuanceRepository expiredReader =
        new AccountGameplayDelegationIssuanceRepository(
            harness.dsl(),
            harness.sources(),
            Clock.fixed(
                Instant.ofEpochSecond(harness.pending().expiresAtEpochSecond()), ZoneOffset.UTC));
    assertThatThrownBy(
            () ->
                inTransaction(
                    harness.context(),
                    () ->
                        expiredReader.readCurrentGameplayLoginReplay(
                            harness.pending().requestId())))
        .isInstanceOf(
            AccountGameplayDelegationIssuanceRepository.PendingCandidateUnavailableException.class);

    AccountGameplayDelegationCommittedIssuanceOwner committedOwner =
        new AccountGameplayDelegationCommittedIssuanceOwner(
            harness.issuance(),
            harness.signer(),
            new AccountGameplayDelegationTokenRegistry(
                harness.issuance(),
                harness.redis().client(),
                Clock.systemUTC(),
                GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
                30_000L),
            harness.context().manager(),
            Clock.systemUTC());
    var currentObservation =
        committedOwner.inspectCurrentCommittedIssuance(harness.pending().requestId());
    assertThat(currentObservation.operationId()).isEqualTo(harness.pending().operationId());
    assertThat(currentObservation.requestId()).isEqualTo(harness.pending().requestId());
    assertThat(currentObservation.accountId()).isEqualTo(harness.accountId());
    assertThat(currentObservation.tokenSha256()).isEqualTo(row.get("token_hash", String.class));
    assertThat(currentObservation.commitProofSha256()).isEqualTo(committed.proofSha256());
    assertThat(currentObservation.authoritySnapshot())
        .isEqualTo(harness.pending().authoritySnapshot());
    assertThat(currentObservation.evidenceBundleReference().canonicalSha256())
        .matches("[0-9a-f]{64}");
    assertThat(currentObservation.envelopeSha256()).matches("[0-9a-f]{64}");
    assertThat(currentObservation.toString())
        .doesNotContain(
            "eyJ",
            currentObservation.tokenSha256(),
            currentObservation.envelopeSha256(),
            currentObservation.commitProofSha256());

    byte[] originalProof = persistedProof.clone();
    inTransaction(
        harness.context(),
        () -> {
          AccountRepository accounts = new AccountRepository(harness.dsl(), harness.sources());
          Account changed = accounts.findByAccountUuid(harness.accountId()).orElseThrow();
          changed.setEmailVerified(true);
          accounts.save(changed);
          return null;
        });
    assertThatThrownBy(
            () -> committedOwner.inspectCurrentCommittedIssuance(harness.pending().requestId()))
        .isInstanceOf(
            AccountGameplayDelegationCommittedIssuanceOwner.OwnerUnavailableException.class)
        .hasNoCause();
    var unchangedAfterAuthorityChange =
        harness
            .dsl()
            .resultQuery(
                "SELECT status, commit_proof_sha256, commit_proof_canonical_bytes "
                    + "FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                harness.pending().requestId())
            .fetchOne();
    assertThat(unchangedAfterAuthorityChange.get("status", String.class)).isEqualTo("COMMITTED");
    assertThat(unchangedAfterAuthorityChange.get("commit_proof_sha256", String.class))
        .isEqualTo(committed.proofSha256());
    assertThat(unchangedAfterAuthorityChange.get("commit_proof_canonical_bytes", byte[].class))
        .containsExactly(originalProof);

    assertThatThrownBy(
            () ->
                withAuthenticatedGameSession(
                    () ->
                        harness
                            .loginOwner()
                            .authenticate(
                                exactLoginRetry,
                                CALLER_WORKLOAD,
                                verifierMustNotRun(credentialVerifierCalls))))
        .isInstanceOf(StaleAuthorityException.class);
    assertThat(credentialVerifierCalls).hasValue(0);

    int closesBeforeRetry = harness.redis().connectionCloseCount();
    int loadsBeforeRetry = harness.redis().scriptLoadCount();
    var exactRetry = harness.service().commitPendingCandidate(harness.pending().requestId());
    assertThat(exactRetry.outcome())
        .isEqualTo(AccountGameplayDelegationIssuanceCommitService.Outcome.EXACT_RETRY);
    assertThat(exactRetry.operationId()).isEqualTo(committed.operationId());
    assertThat(exactRetry.requestId()).isEqualTo(committed.requestId());
    assertThat(exactRetry.accountId()).isEqualTo(committed.accountId());
    assertThat(exactRetry.proofSha256()).isEqualTo(committed.proofSha256());
    assertThat(harness.redis().connectionCloseCount()).isEqualTo(closesBeforeRetry);
    assertThat(harness.redis().scriptLoadCount()).isEqualTo(loadsBeforeRetry);
    assertThat(
            Objects.requireNonNull(
                    harness
                        .dsl()
                        .resultQuery(
                            "SELECT commit_proof_canonical_bytes FROM "
                                + "account_gameplay_delegation_issuance_operations "
                                + "WHERE request_id = ?",
                            harness.pending().requestId())
                        .fetchOne(),
                    "committed proof query must return a row")
                .get("commit_proof_canonical_bytes", byte[].class))
        .containsExactly(persistedProof);
  }

  @Test
  void signerTrustWithdrawalAfterExternalProofLeavesOnlyQuarantinedPendingCandidate()
      throws Exception {
    CommitHarness harness = newCommitHarness(CommitHook.WITHDRAW_SIGNER_TRUST, false);

    assertThatThrownBy(
            () -> harness.service().commitPendingCandidate(harness.pending().requestId()))
        .isInstanceOf(
            AccountGameplayDelegationIssuanceCommitService.IssuanceCommitUnavailableException.class)
        .hasNoCause();

    assertPendingWithoutCommitProof(harness);
    assertThat(harness.redis().registeredRecord()).isNotEmpty();
  }

  @Test
  void advancedAccountSourceChangeAfterProjectionDeniesCommitWithoutRewritingCandidate()
      throws Exception {
    CommitHarness harness = newCommitHarness(CommitHook.ADVANCE_ACCOUNT_AFTER_PROJECTION, false);

    assertThatThrownBy(
            () -> harness.service().commitPendingCandidate(harness.pending().requestId()))
        .isInstanceOf(
            AccountGameplayDelegationIssuanceCommitService.IssuanceCommitUnavailableException.class)
        .hasNoCause();

    assertPendingWithoutCommitProof(harness);
    assertThat(
            currentSnapshot(harness.context(), harness.sources(), harness.accountId())
                .account()
                .generation())
        .isGreaterThan(harness.pending().authoritySnapshot().accountGeneration());
  }

  @Test
  void changedPendingRedisReadbackNeverCommitsTheCandidate() throws Exception {
    CommitHarness harness = newCommitHarness(CommitHook.NONE, true);

    assertThatThrownBy(
            () -> harness.service().commitPendingCandidate(harness.pending().requestId()))
        .isInstanceOf(
            AccountGameplayDelegationIssuanceCommitService.IssuanceCommitUnavailableException.class)
        .hasNoCause();

    assertPendingWithoutCommitProof(harness);
    assertThat(harness.redis().connectionCloseCount()).isEqualTo(1);
  }

  @Test
  void ambiguousCommittedSqlReadbackDoesNotReturnSuccessThoughExactOwnerRowIsCommitted()
      throws Exception {
    CommitHarness harness = newCommitHarness(CommitHook.AMBIGUOUS_COMMITTED_READBACK, false);

    assertThatThrownBy(
            () -> harness.service().commitPendingCandidate(harness.pending().requestId()))
        .isInstanceOf(
            AccountGameplayDelegationIssuanceCommitService.IssuanceCommitUnavailableException.class)
        .hasNoCause();

    var row =
        harness
            .dsl()
            .resultQuery(
                "SELECT status, commit_proof_sha256, commit_proof_canonical_bytes "
                    + "FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                harness.pending().requestId())
            .fetchOne();
    assertThat(row.get("status", String.class)).isEqualTo("COMMITTED");
    assertThat(row.get("commit_proof_sha256", String.class)).matches("[0-9a-f]{64}");
    assertThat(row.get("commit_proof_canonical_bytes", byte[].class)).isNotEmpty();
  }

  private CommitHarness newCommitHarness(CommitHook hook, boolean mismatchRedisReadback)
      throws Exception {
    return newCommitHarness(hook, mismatchRedisReadback, false);
  }

  private CommitHarness newCommitHarness(
      CommitHook hook, boolean mismatchRedisReadback, boolean bindCanonicalLoginRequest)
      throws Exception {
    TestContext context = newTestContext();
    DSLContext dsl = context.dsl();
    Account account = createAccount(context);
    UUID accountId = account.getAccountUuid();
    AccountAuthorityGenerationRepository authorities =
        new AccountAuthorityGenerationRepository(dsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(dsl);
    AccountAuthoritySourceEvidenceRepository sources =
        new AccountAuthoritySourceEvidenceRepository(dsl, authorities, outbox);
    AccountAuthEvidenceBundleRepository bundles =
        new AccountAuthEvidenceBundleRepository(dsl, authorities, outbox);
    IssuerAccountSourceSnapshot snapshot = currentSnapshot(context, sources, accountId);
    CanonicalGameplayLoginRequest loginRequest = null;
    PendingIntent pending;
    if (bindCanonicalLoginRequest) {
      UUID requestId = UUID.randomUUID();
      UUID callerContextId = UUID.randomUUID();
      loginRequest =
          canonicalLoginRequest(
              account.getEmail(), "already-consumed-credential", requestId, callerContextId);
      AccountGameplayCredentialRequestBinding binding =
          AccountGameplayCredentialRequestDigester.bind(
              integrationDigestKeySource().currentKey(),
              loginRequest,
              account.getEmail(),
              CALLER_WORKLOAD,
              accountId);
      pending = intent(snapshot, accountId, requestId, callerContextId, binding);
    } else {
      pending = intent(snapshot, accountId);
    }
    AccountGameplayDelegationIssuanceRepository storedIssuance =
        new AccountGameplayDelegationIssuanceRepository(dsl, sources, bundles);
    inTransaction(context, () -> storedIssuance.beginPending(pending));

    AccountGameplayDelegationIssuanceRepository issuance = storedIssuance;
    if (hook == CommitHook.AMBIGUOUS_COMMITTED_READBACK) {
      issuance = spy(storedIssuance);
      AtomicInteger committedReads = new AtomicInteger();
      AccountGameplayDelegationIssuanceRepository readbackSpy = issuance;
      doAnswer(
              invocation ->
                  committedReads.incrementAndGet() == 1
                      ? invocation.callRealMethod()
                      : Optional.empty())
          .when(readbackSpy)
          .readCommittedProof(pending.requestId());
    }

    return buildCommitHarness(
        context,
        accountId,
        sources,
        bundles,
        issuance,
        pending,
        loginRequest,
        hook,
        mismatchRedisReadback);
  }

  private CommitHarness buildCommitHarness(
      TestContext context,
      UUID accountId,
      AccountAuthoritySourceEvidenceRepository sources,
      AccountAuthEvidenceBundleRepository bundles,
      AccountGameplayDelegationIssuanceRepository issuance,
      PendingIntent pending,
      CanonicalGameplayLoginRequest loginRequest,
      CommitHook hook,
      boolean mismatchRedisReadback)
      throws Exception {
    DSLContext dsl = context.dsl();
    IssuerAccountSourceSnapshot snapshot = currentSnapshot(context, sources, accountId);
    Path responseKeyring = temporaryDirectory.resolve("response-keyring-" + UUID.randomUUID());
    writeTestKeyring(responseKeyring);
    AccountResponseEnvelopeCryptography responseCryptography =
        new AccountResponseEnvelopeCryptography(
            new AccountResponseEnvelopeKeyring(responseKeyring.toString()));
    AccountGameplayDelegationResponseEnvelopeRepository responseRepository =
        new AccountGameplayDelegationResponseEnvelopeRepository(
            dsl, sources, bundles, responseCryptography);
    AccountGameplayDelegationResponseEnvelopeService responseService =
        new AccountGameplayDelegationResponseEnvelopeService(responseRepository);

    // Only the commit service uses this manager: its transaction is the final commit boundary.
    TransactionHookManager transactionManager = new TransactionHookManager(context.manager());
    AccountGameplayDelegationCommitSignerFixture signerFixture =
        AccountGameplayDelegationCommitSignerFixture.create(
            temporaryDirectory, issuance, responseService, context.manager(), Clock.systemUTC());
    signerFixture.observeDatabaseFailures(dsl);
    transactionManager.setBeforeTargetTransaction(
        switch (hook) {
          case NONE, AMBIGUOUS_COMMITTED_READBACK -> () -> {};
          case WITHDRAW_SIGNER_TRUST -> signerFixture::withdrawTrust;
          case ADVANCE_ACCOUNT_AFTER_PROJECTION ->
              () ->
                  advanceEmailVerifiedThroughOwner(dsl, context.transaction(), sources, accountId);
        });

    RedisHarness redis =
        new RedisHarness(accountId, snapshot, mismatchRedisReadback, getClass().getClassLoader());
    AccountGameplayDelegationTokenRegistry tokenRegistry =
        new AccountGameplayDelegationTokenRegistry(
            AccountGameplayDelegationCommitSignerFixture.transactionalPendingRegistryReads(
                issuance, context.manager()),
            redis.client(),
            Clock.systemUTC(),
            GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
            30_000L);
    AccountGameplayDelegationAuthorityProjection authorityProjection =
        new AccountGameplayDelegationAuthorityProjection(
            sources, context.manager(), redis.client());
    AccountGameplayDelegationIssuanceCommitService service =
        new AccountGameplayDelegationIssuanceCommitService(
            signerFixture.signer(),
            tokenRegistry,
            authorityProjection,
            issuance,
            transactionManager,
            Clock.systemUTC());
    AccountGameplayDelegationCommittedIssuanceOwner committedOwner =
        new AccountGameplayDelegationCommittedIssuanceOwner(
            issuance, signerFixture.signer(), tokenRegistry, context.manager(), Clock.systemUTC());
    AccountGameplayDelegationResponseRecoveryOwner responseOwner =
        new AccountGameplayDelegationResponseRecoveryOwner(
            responseRepository, committedOwner, context.manager());
    AccountGameplayCanonicalLoginOwner loginOwner =
        new AccountGameplayCanonicalLoginOwner(
            new AccountRepository(dsl, sources),
            issuance,
            integrationDigestKeySource(),
            service,
            responseOwner,
            context.manager(),
            Clock.systemUTC());
    return new CommitHarness(
        context,
        dsl,
        accountId,
        sources,
        pending,
        redis,
        service,
        issuance,
        signerFixture.signer(),
        loginOwner,
        loginRequest);
  }

  private CommitHarness buildCommitHarness(
      TestContext context,
      UUID accountId,
      AccountAuthoritySourceEvidenceRepository sources,
      AccountAuthEvidenceBundleRepository bundles,
      AccountGameplayDelegationIssuanceRepository issuance,
      PendingIntent pending,
      CanonicalGameplayLoginRequest loginRequest)
      throws Exception {
    return buildCommitHarness(
        context,
        accountId,
        sources,
        bundles,
        issuance,
        pending,
        loginRequest,
        CommitHook.NONE,
        false);
  }

  private static void assertPendingWithoutCommitProof(CommitHarness harness) {
    var row =
        harness
            .dsl()
            .resultQuery(
                "SELECT status, token_hash, pending_registry_candidate_bytes, "
                    + "commit_proof_version, commit_proof_sha256, "
                    + "commit_proof_canonical_bytes, committed_at "
                    + "FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                harness.pending().requestId())
            .fetchOne();
    assertThat(row.get("status", String.class)).isEqualTo("PENDING");
    assertThat(row.get("token_hash", String.class)).matches("[0-9a-f]{64}");
    byte[] storedCandidate = row.get("pending_registry_candidate_bytes", byte[].class);
    assertThat(storedCandidate).isNotEmpty();
    if (harness.redis().registeredRecord() != null) {
      assertThat(storedCandidate).containsExactly(harness.redis().registeredRecord());
    }
    assertThat(row.get("commit_proof_version")).isNull();
    assertThat(row.get("commit_proof_sha256")).isNull();
    assertThat(row.get("commit_proof_canonical_bytes")).isNull();
    assertThat(row.get("committed_at")).isNull();
  }

  private static Account createAccount(TestContext context) {
    return createAccount(context, "PASSWORD");
  }

  private static Account createAccount(TestContext context, String loginAuthModes) {
    Account account = new Account();
    String suffix = UUID.randomUUID().toString().replace("-", "");
    account.setUsername("delegation-" + suffix);
    account.setEmail("delegation-" + UUID.randomUUID() + "@example.test");
    account.setPasswordHash("integration-test-hash");
    // The fresh Account birth path creates the exact empty global-role source only for null role.
    account.setLoginAuthModes(loginAuthModes);
    DSLContext dsl = context.dsl();
    AccountAuthorityGenerationRepository authorities =
        new AccountAuthorityGenerationRepository(dsl);
    AccountAuthoritySourceEvidenceRepository sourceEvidence =
        new AccountAuthoritySourceEvidenceRepository(
            dsl, authorities, new AccountAuthorityOutboxRepository(dsl));
    return inTransaction(
        context,
        () -> {
          sourceEvidence.initializeIssuerIfAbsent(ACCOUNT_ISSUER);
          return new AccountRepository(dsl, sourceEvidence).save(account);
        });
  }

  private static Account createEmailOtpAccount(
      TestContext context, AccountAuthoritySourceEvidenceRepository sourceEvidence) {
    return createAccount(context, "EMAIL_OTP");
  }

  /** Test-only owner transaction using the retained closed operation/source receipt protocol. */
  static void advanceEmailVerifiedThroughOwner(
      DSLContext dsl,
      TransactionTemplate transaction,
      AccountAuthoritySourceEvidenceRepository sources,
      UUID accountUuid) {
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(dsl);
    AccountSecurityStateOperationRepository operations =
        new AccountSecurityStateOperationRepository(dsl);
    DraftAuthorizationFenceRepository fences = new DraftAuthorizationFenceRepository(dsl);
    transaction.execute(
        status -> {
          Account account =
              new AccountRepository(dsl, sources).findByAccountUuid(accountUuid).orElseThrow();
          var source =
              sources.readCurrentIssuerAccountSources(ACCOUNT_ISSUER, accountUuid).account();
          var roleRow =
              Objects.requireNonNull(
                  dsl.fetchOne(
                      "SELECT global_roles, global_role_source_version "
                          + "FROM account_global_role_sources WHERE account_uuid = ? FOR SHARE",
                      accountUuid));
          long roleVersion = roleRow.get("global_role_source_version", Long.class);
          AccountState before =
              new AccountState(
                  account.isEmailVerified(),
                  AccountLoginAuthModes.read(account.getLoginAuthModes()).stream()
                      .map(Enum::name)
                      .sorted()
                      .toList(),
                  java.util.Arrays.stream(roleRow.get("global_roles", String[].class))
                      .sorted()
                      .toList(),
                  account.getLifecycleState().name());
          AccountState after =
              new AccountState(
                  true, before.loginAuthModes(), before.globalRoles(), before.lifecycleState());
          if (before.emailVerified())
            throw new IllegalStateException("Fixture expected unverified Account");
          UUID requestId = UUID.randomUUID();
          AccountSecurityStateMutationRequest request =
              new AccountSecurityStateMutationRequest(
                  requestId,
                  accountUuid,
                  securityStateCorrelation(accountUuid, requestId),
                  source.generation(),
                  source.sourceVersion(),
                  AccountSecurityStateOperationRepository.detectedKinds(before, after),
                  after);
          long checkpointSequence = source.checkpoint().sequence();
          byte[] checkpointPayload =
              checkpointSequence == 0L
                  ? new byte[0]
                  : outbox
                      .findEvent(accountStream(accountUuid), checkpointSequence)
                      .orElseThrow()
                      .payload();
          Capture draft =
              new Capture(
                  account.getId(),
                  account.getAccountUuidProvenance(),
                  before,
                  new AccountAuthorityGenerationRepository.ScopeState(
                      source.scope(),
                      source.generation(),
                      source.sourceVersion(),
                      source.issuanceFence()),
                  checkpointSequence,
                  checkpointPayload,
                  roleVersion,
                  null);
          byte[] captureBytes =
              AccountSecurityStateOperationRepository.captureBytes(request, draft);
          SourceEvidence sourceEvidence =
              new SourceEvidence(
                  SourceKind.ACCOUNT,
                  accountUuid.toString(),
                  Long.toString(source.generation()),
                  Long.toString(source.sourceVersion()),
                  accountStream(accountUuid),
                  Long.toString(checkpointSequence),
                  captureBytes);
          SourceChange change =
              new SourceChange(UUID.randomUUID(), List.of(sourceEvidence), captureBytes);
          Capture capture =
              new Capture(
                  draft.accountId(),
                  draft.provenance(),
                  before,
                  draft.sourceState(),
                  checkpointSequence,
                  checkpointPayload,
                  roleVersion,
                  change);
          fences.requestSourceChange(change);
          if (!fences.sourceMutationPermitted(change))
            throw new IllegalStateException("Fixture owner source is not settled");
          operations.claim(request, capture);
          var advanced = sources.prepareClosedAccountAdvance(accountUuid, source);
          dsl.execute(
              "UPDATE accounts SET email_verified = TRUE WHERE account_uuid = ?", accountUuid);
          Event event =
              outbox.append(
                  accountStream(accountUuid),
                  requestId.toString(),
                  sequence -> {
                    var sealed =
                        AccountSecurityStateAuthorityEventV1Codec.seal(
                            AccountSecurityStateMutationRequest.eventPreimage(
                                requestId,
                                accountUuid,
                                Long.toString(advanced.generation()),
                                Long.toString(advanced.sourceVersion()),
                                Long.toString(sequence),
                                request.mutationKinds(),
                                after));
                    return new EventEvidence(
                        sealed.eventId(), sealed.eventDigest(), sealed.canonicalJsonUtf8());
                  });
          fences.markSourceCommitted(change);
          operations.complete(request, event, advanced, roleVersion);
          return null;
        });
  }

  private static byte[] securityStateCorrelation(UUID accountUuid, UUID operationId) {
    return ("{\"actorAccountUuid\":\""
            + accountUuid
            + "\",\"ownerEvidenceDigest\":\"sha256:"
            + "a".repeat(64)
            + "\",\"ownerOperationId\":\""
            + operationId
            + "\",\"schemaVersion\":\"account-security-state-caller-correlation/v1\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String accountStream(UUID accountUuid) {
    return "account:auth-authority:v1:account/" + accountUuid;
  }

  private static AccountEmailLoginChallenge persistEmailLoginChallenge(
      TestContext context,
      AccountEmailLoginChallengeRepository challenges,
      Long accountId,
      String code) {
    LocalDateTime now = LocalDateTime.now();
    AccountEmailLoginChallenge challenge = new AccountEmailLoginChallenge();
    challenge.setAccountId(accountId);
    challenge.setCodeHash(hashCredential(code));
    challenge.setExpiresAt(now.plusMinutes(5));
    challenge.setResendAvailableAt(now.plusMinutes(1));
    challenge.setInvalidAttemptCount(0);
    challenge.setCreatedAt(now);
    challenge.setUpdatedAt(now);
    return inTransaction(context, () -> challenges.save(challenge));
  }

  private static String hashCredential(String credential) {
    Argon2 argon2 = Argon2Factory.create();
    char[] chars = credential.toCharArray();
    try {
      return argon2.hash(2, 65536, 1, chars);
    } finally {
      argon2.wipeArray(chars);
    }
  }

  private static CanonicalGameplayLoginRequest canonicalLoginRequest(
      String email, String credential, UUID requestId, UUID callerContextId) {
    return new CanonicalGameplayLoginRequest(
        requestId,
        email,
        credential,
        new GameplayCredentialSourceContext(callerContextId, "203.0.113.10", "FIRST_PARTY_WEB"));
  }

  /**
   * Test-only deterministic successful credential verifier. These PostgreSQL proofs exercise the
   * canonical owner's transaction boundary and persisted issuance; they do not prove production
   * password or OTP verification.
   */
  private static AccountGameplayCanonicalLoginOwner.CredentialVerifier
      deterministicOtpCredentialVerifier(
          AccountEmailLoginChallengeRepository challenges, String expectedCredential) {
    return new AccountGameplayCanonicalLoginOwner.CredentialVerifier() {
      @Override
      public AccountGameplayCanonicalLoginOwner.CredentialVerification verify(
          Account account, String presentedCredential) {
        if (!expectedCredential.equals(presentedCredential)) {
          throw new AssertionError("Unexpected deterministic integration credential");
        }
        AccountEmailLoginChallenge challenge =
            challenges
                .findByAccountId(account.getId())
                .orElseThrow(() -> new AssertionError("Expected seeded OTP challenge"));
        return new AccountGameplayCanonicalLoginOwner.CredentialVerification(
            account.getAccountUuid(), challenge.getId());
      }

      @Override
      public void consumeOneTimeCredential(
          Account account, AccountGameplayCanonicalLoginOwner.CredentialVerification verification) {
        challenges.lockAccountChallenge(account.getId());
        AccountEmailLoginChallenge challenge =
            challenges
                .findByAccountId(account.getId())
                .orElseThrow(() -> new AssertionError("Expected locked OTP challenge"));
        if (!challenge.getId().equals(verification.oneTimeChallengeId())) {
          throw new AssertionError("OTP challenge changed during deterministic proof");
        }
        challenges.delete(challenge);
      }
    };
  }

  private static AccountGameplayCanonicalLoginOwner.CredentialVerifier verifierMustNotRun(
      AtomicInteger calls) {
    return new AccountGameplayCanonicalLoginOwner.CredentialVerifier() {
      @Override
      public AccountGameplayCanonicalLoginOwner.CredentialVerification verify(
          Account account, String presentedCredential) {
        calls.incrementAndGet();
        throw new AssertionError("A replay must not re-verify or retain credentials");
      }

      @Override
      public void consumeOneTimeCredential(
          Account account, AccountGameplayCanonicalLoginOwner.CredentialVerification verification) {
        calls.incrementAndGet();
        throw new AssertionError("A replay must not consume a credential again");
      }
    };
  }

  private static AccountGameplayCredentialRequestDigestKeySource integrationDigestKeySource() {
    CredentialDigestKey key =
        new CredentialDigestKey(
            "account-login-integration",
            new SecretKeySpec(new byte[32], "HmacSHA256"),
            Instant.MAX);
    return new AccountGameplayCredentialRequestDigestKeySource() {
      @Override
      public CredentialDigestKey currentKey() {
        return key;
      }

      @Override
      public CredentialDigestKey requireKey(String keyId) {
        return key.keyId().equals(keyId) ? key : null;
      }
    };
  }

  private static <T> T withAuthenticatedGameSession(java.util.function.Supplier<T> action) {
    AtomicReference<T> result = new AtomicReference<>();
    GrpcPeerIdentity peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/test/sa/game-session-service", "test", "game-session-service");
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(() -> result.set(action.get()));
    return result.get();
  }

  private static IssuerAccountSourceSnapshot currentSnapshot(
      TestContext context, AccountAuthoritySourceEvidenceRepository sources, UUID accountId) {
    return inTransaction(
        context, () -> sources.readCurrentIssuerAccountSources(ACCOUNT_ISSUER, accountId));
  }

  private static PendingIntent intent(IssuerAccountSourceSnapshot snapshot, UUID accountId) {
    return intent(
        snapshot,
        accountId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        AccountGameplayCredentialRequestBindingFixture.binding());
  }

  private static PendingIntent intent(
      IssuerAccountSourceSnapshot snapshot,
      UUID accountId,
      UUID requestId,
      UUID callerContextId,
      AccountGameplayCredentialRequestBinding credentialRequestBinding) {
    long now = Math.floorDiv(System.currentTimeMillis(), 1000L);
    AccountSecurityCutoff cutoff =
        snapshot
            .account()
            .accountSecurityCutoff()
            .map(
                value ->
                    new AccountSecurityCutoff(
                        value.accountAuthorityGeneration(),
                        value.outboxStreamKey(),
                        value.outboxSequence()))
            .orElse(null);
    return new PendingIntent(
        UUID.randomUUID(),
        requestId,
        CALLER_WORKLOAD,
        callerContextId,
        UUID.randomUUID(),
        now,
        now,
        now + 120L,
        new AccountAuthoritySnapshot(
            accountId,
            snapshot.issuer().generation(),
            snapshot.issuer().sourceVersion(),
            snapshot.account().generation(),
            snapshot.account().sourceVersion(),
            snapshot.issuanceFence().value(),
            snapshot.issuanceFence().sourceVersion(),
            Optional.ofNullable(cutoff)),
        credentialRequestBinding);
  }

  private static String compactCandidate(PendingIntent intent) throws Exception {
    return compactCandidate(intent, intent.expiresAtEpochSecond());
  }

  private static String compactCandidate(PendingIntent intent, long expiresAt) throws Exception {
    Map<String, Object> header = Map.of("alg", "RS256", "kid", "delegation-kid", "typ", "JWT");
    Map<String, Object> claims =
        Map.ofEntries(
            Map.entry("iss", GameSessionAccountDelegationProfile.ISSUER),
            Map.entry("sub", intent.authoritySnapshot().accountId().toString()),
            Map.entry("accountId", intent.authoritySnapshot().accountId().toString()),
            Map.entry("jti", intent.tokenJti().toString()),
            Map.entry("aud", GameSessionAccountDelegationProfile.AUDIENCE),
            Map.entry("iat", intent.issuedAtEpochSecond()),
            Map.entry("nbf", intent.notBeforeEpochSecond()),
            Map.entry("exp", expiresAt),
            Map.entry("tokenGeneration", "1"),
            Map.entry(
                "authorityTuple",
                GameSessionAccountDelegationProfile.authorityTuple(
                    intent.authoritySnapshot().issuerGeneration(),
                    intent.authoritySnapshot().accountGeneration(),
                    intent.authoritySnapshot().accountSecurityCutoff())),
            Map.entry("membershipVersion", Map.of()),
            Map.entry("issuanceFence", Long.toString(intent.authoritySnapshot().issuanceFence())));
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String head = encoder.encodeToString(canonicalBytes(header));
    String payload = encoder.encodeToString(canonicalBytes(claims));
    String signature = encoder.encodeToString(new byte[] {1, 2, 3});
    return head + "." + payload + "." + signature;
  }

  private static byte[] canonicalBytes(Object value) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static String sha256Hex(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static Map<String, Object> authorityTupleWithoutCutoff(
      long issuerGeneration, long accountGeneration) {
    Map<String, Object> tuple =
        new LinkedHashMap<>(
            GameSessionAccountDelegationProfile.authorityTuple(issuerGeneration, 1L));
    tuple.put("accountAuthorityGeneration", accountGeneration);
    return tuple;
  }

  private static RecordData readCandidate(DSLContext dsl, UUID requestId) {
    var row =
        Objects.requireNonNull(
            dsl.resultQuery(
                    "SELECT status, token_hash, pending_registry_candidate_bytes, "
                        + "authority_tuple_canonical_bytes "
                        + "FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                    requestId)
                .fetchOne(),
            "issuance candidate query must return a row");
    return new RecordData(
        row.get("status", String.class),
        row.get("token_hash", String.class),
        row.get("pending_registry_candidate_bytes", byte[].class),
        row.get("authority_tuple_canonical_bytes", byte[].class));
  }

  private static AccountAuthoritySourceEvidenceRepository sourceEvidence(
      DSLContext dsl, AccountAuthorityGenerationRepository authorities) {
    return new AccountAuthoritySourceEvidenceRepository(
        dsl, authorities, new AccountAuthorityOutboxRepository(dsl));
  }

  private static void insertRetainedUnboundPendingOperation(DSLContext dsl, PendingIntent intent)
      throws Exception {
    var authority = intent.authoritySnapshot();
    int inserted =
        dsl.execute(
            "INSERT INTO account_gameplay_delegation_issuance_operations "
                + "(operation_id, request_id, account_uuid, caller_workload, caller_context_id, "
                + "request_digest_version, request_digest, token_jti, token_generation, "
                + "issued_at_epoch_second, not_before_epoch_second, expires_at_epoch_second, "
                + "authority_issuer_generation, authority_issuer_source_version, "
                + "authority_account_generation, authority_account_source_version, issuance_fence, "
                + "issuance_fence_source_version, authority_tuple_canonical_bytes, "
                + "membership_version_canonical_bytes, authority_source_versions_canonical_bytes) "
                + "VALUES (?, ?, ?, ?, ?, 1, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            intent.operationId(),
            intent.requestId(),
            authority.accountId(),
            intent.callerWorkload(),
            intent.callerContextId(),
            "00".repeat(32),
            intent.tokenJti(),
            intent.issuedAtEpochSecond(),
            intent.notBeforeEpochSecond(),
            intent.expiresAtEpochSecond(),
            authority.issuerGeneration(),
            authority.issuerSourceVersion(),
            authority.accountGeneration(),
            authority.accountSourceVersion(),
            authority.issuanceFence(),
            authority.issuanceFenceSourceVersion(),
            canonicalBytes(legacyNumericAuthorityTuple(authority)),
            canonicalBytes(Map.of()),
            canonicalBytes(legacyNumericSourceVersions(authority)));
    assertThat(inserted).isEqualTo(1);
  }

  /**
   * V78's historical SQL guard expected JSON numeric generations before the V84 string contract.
   */
  private static Map<String, Object> legacyNumericAuthorityTuple(
      AccountAuthoritySnapshot authority) {
    Map<String, Object> tuple =
        new LinkedHashMap<>(
            GameSessionAccountDelegationProfile.authorityTuple(
                authority.issuerGeneration(),
                authority.accountGeneration(),
                authority.accountSecurityCutoff()));
    tuple.put("accountAuthorityGeneration", authority.accountGeneration());
    tuple.put("issuerAuthGeneration", authority.issuerGeneration());
    return tuple;
  }

  private static Map<String, Object> legacyNumericSourceVersions(
      AccountAuthoritySnapshot authority) {
    return Map.of(
        "accountSourceVersion", authority.accountSourceVersion(),
        "issuanceFenceSourceVersion", authority.issuanceFenceSourceVersion(),
        "issuerSourceVersion", authority.issuerSourceVersion());
  }

  private static void writeTestKeyring(Path root) throws Exception {
    Files.createDirectories(root);
    byte[] key = new byte[32];
    for (int index = 0; index < key.length; index++) key[index] = (byte) (index + 1);
    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    Files.writeString(
        root.resolve("keyring"),
        "firemud-account-response-envelope-keyring-v1\nactive integration-key " + encoded + "\n",
        StandardCharsets.US_ASCII);
  }

  private static String sha1Hex(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(value));
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private TestContext newTestContext() {
    return newTestContext(null);
  }

  private TestContext newTestContextAtVersion(String version) {
    return newTestContext(MigrationVersion.fromVersion(version));
  }

  private TestContext newTestContext(MigrationVersion target) {
    String schema = SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
    assertThat(schema.length()).isLessThanOrEqualTo(63);
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = testJdbcUrl.contains("?") ? "&" : "?";
    dataSource.setUrl(testJdbcUrl + separator + "currentSchema=" + schema);
    dataSource.setUsername(testJdbcUsername);
    dataSource.setPassword(testJdbcPassword);
    Properties connectionProperties = new Properties();
    connectionProperties.setProperty("connectTimeout", "10");
    connectionProperties.setProperty("socketTimeout", "60");
    connectionProperties.setProperty("options", "-c lock_timeout=60000 -c statement_timeout=90000");
    dataSource.setConnectionProperties(connectionProperties);
    var flywayConfiguration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (target != null) flywayConfiguration.target(target);
    flywayConfiguration.load().migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    return new TestContext(
        new TransactionTemplate(transactionManager), transactionManager, dsl, dataSource, schema);
  }

  private static void migrateTestSchemaToLatest(TestContext context) {
    Flyway.configure()
        .dataSource(context.dataSource())
        .schemas(context.schema())
        .defaultSchema(context.schema())
        .placeholders(Map.of("serviceSchema", context.schema()))
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  private static <T> T inTransaction(
      TestContext context, java.util.function.Supplier<T> operation) {
    return context.transaction().execute(status -> operation.get());
  }

  private static String validateExternalLoopbackPostgresUrl(String jdbcUrl) {
    final URI uri;
    try {
      if (!jdbcUrl.startsWith("jdbc:")) throw new IllegalArgumentException("not a JDBC URL");
      uri = URI.create(jdbcUrl.substring("jdbc:".length()));
    } catch (RuntimeException ex) {
      throw new IllegalStateException(
          EXTERNAL_POSTGRES_URL_ENV + " must target jdbc:postgresql://127.0.0.1:<port>/postgres",
          ex);
    }
    if (!"postgresql".equals(uri.getScheme())
        || !"127.0.0.1".equals(uri.getHost())
        || uri.getPort() < 1
        || uri.getPort() > 65_535
        || !"/postgres".equals(uri.getPath())
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalStateException(
          EXTERNAL_POSTGRES_URL_ENV + " must target jdbc:postgresql://127.0.0.1:<port>/postgres");
    }
    return jdbcUrl;
  }

  private record TestContext(
      TransactionTemplate transaction,
      PlatformTransactionManager manager,
      DSLContext dsl,
      DriverManagerDataSource dataSource,
      String schema) {}

  private enum CommitHook {
    NONE,
    WITHDRAW_SIGNER_TRUST,
    ADVANCE_ACCOUNT_AFTER_PROJECTION,
    AMBIGUOUS_COMMITTED_READBACK
  }

  private record CommitHarness(
      TestContext context,
      DSLContext dsl,
      UUID accountId,
      AccountAuthoritySourceEvidenceRepository sources,
      PendingIntent pending,
      RedisHarness redis,
      AccountGameplayDelegationIssuanceCommitService service,
      AccountGameplayDelegationIssuanceRepository issuance,
      AccountGameplayDelegationSigner signer,
      AccountGameplayCanonicalLoginOwner loginOwner,
      CanonicalGameplayLoginRequest loginRequest) {}

  private static final class TransactionHookManager implements PlatformTransactionManager {
    private final PlatformTransactionManager delegate;
    private final AtomicReference<Runnable> beforeTargetTransaction =
        new AtomicReference<>(() -> {});

    private TransactionHookManager(PlatformTransactionManager delegate) {
      this.delegate = delegate;
    }

    private void setBeforeTargetTransaction(Runnable operation) {
      beforeTargetTransaction.set(operation);
    }

    @Override
    public org.springframework.transaction.TransactionStatus getTransaction(
        org.springframework.transaction.TransactionDefinition definition) {
      beforeTargetTransaction.getAndSet(() -> {}).run();
      return delegate.getTransaction(definition);
    }

    @Override
    public void commit(org.springframework.transaction.TransactionStatus status) {
      delegate.commit(status);
    }

    @Override
    public void rollback(org.springframework.transaction.TransactionStatus status) {
      delegate.rollback(status);
    }
  }

  private static final class RedisHarness {
    private final UUID accountId;
    private final boolean mismatchPendingReadback;
    private final byte[][] authorityProjectionBytes;
    private final AtomicReference<byte[]> pendingRecord = new AtomicReference<>();
    private final AtomicLong absoluteExpiryMillis = new AtomicLong();
    private final AtomicInteger connectionCloseCount = new AtomicInteger();
    private final AtomicInteger scriptLoadCount = new AtomicInteger();
    private final StatefulRedisConnection<byte[], byte[]> connection =
        mock(StatefulRedisConnection.class);
    private final RedisCommands<byte[], byte[]> commands = mock(RedisCommands.class);
    private final AccountGameplayDelegationRedisClient client;

    private RedisHarness(
        UUID accountId,
        IssuerAccountSourceSnapshot source,
        boolean mismatchPendingReadback,
        ClassLoader resourceClassLoader)
        throws Exception {
      this.accountId = accountId;
      this.mismatchPendingReadback = mismatchPendingReadback;
      authorityProjectionBytes =
          AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(source);
      when(connection.getOptions())
          .thenReturn(ClientOptions.builder().autoReconnect(false).build());
      when(connection.isOpen()).thenReturn(true);
      when(connection.sync()).thenReturn(commands);
      when(commands.aclWhoami())
          .thenReturn(AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY);
      when(commands.scriptLoad(any(byte[].class)))
          .thenAnswer(
              invocation -> {
                scriptLoadCount.incrementAndGet();
                return sha1Hex(invocation.getArgument(0));
              });
      when(commands.<Long>evalsha(
              anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class)))
          .thenAnswer(
              invocation -> {
                byte[][] arguments = (byte[][]) invocation.getRawArguments()[3];
                pendingRecord.set(arguments[0].clone());
                absoluteExpiryMillis.set(
                    Long.parseLong(new String(arguments[1], StandardCharsets.US_ASCII)));
                return 1L;
              });
      when(commands.get(any(byte[].class)))
          .thenAnswer(
              invocation -> {
                String key = new String(invocation.getArgument(0), StandardCharsets.US_ASCII);
                if (key.startsWith(AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX)) {
                  byte[] value = pendingRecord.get();
                  if (value == null) return null;
                  return mismatchPendingReadback
                      ? ascii("different-pending-record")
                      : value.clone();
                }
                if ((AccountGameplayDelegationAuthorityProjection.ISSUER_KEY_PREFIX
                        + GameSessionAccountDelegationProfile.ISSUER)
                    .equals(key)) {
                  return authorityProjectionBytes[0].clone();
                }
                if ((AccountGameplayDelegationAuthorityProjection.ACCOUNT_KEY_PREFIX + accountId)
                    .equals(key)) {
                  return authorityProjectionBytes[1].clone();
                }
                return null;
              });
      when(commands.pexpiretime(any(byte[].class)))
          .thenAnswer(invocation -> absoluteExpiryMillis.get());
      org.mockito.Mockito.doReturn(List.of(1L, 1L))
          .when(commands)
          .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
      doAnswer(
              invocation -> {
                connectionCloseCount.incrementAndGet();
                return null;
              })
          .when(connection)
          .close();

      AccountCoordinationPinnedConnectionProvider provider = () -> connection;
      client =
          new AccountGameplayDelegationRedisClient(
              provider,
              RedisScriptCatalog.loadInstalled(resourceClassLoader),
              new AcknowledgementRequirements(1, 1, 1_000),
              GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
              resourceClassLoader);
    }

    private AccountGameplayDelegationRedisClient client() {
      return client;
    }

    private byte[] registeredRecord() {
      byte[] value = pendingRecord.get();
      return value == null ? null : value.clone();
    }

    private int connectionCloseCount() {
      return connectionCloseCount.get();
    }

    private int scriptLoadCount() {
      return scriptLoadCount.get();
    }
  }

  private record BindReadSeal(
      BoundTokenCandidate bound,
      PendingRegistryCandidate persisted,
      SealedCandidateObservation sealed) {}

  private record RecordData(
      String status, String tokenHash, byte[] candidateBytes, byte[] authorityTupleBytes) {}
}
