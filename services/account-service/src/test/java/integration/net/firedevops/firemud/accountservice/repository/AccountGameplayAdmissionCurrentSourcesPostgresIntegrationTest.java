package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.ArrayOutput;
import io.lettuce.core.output.CommandOutput;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.ProtocolKeyword;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseResponse;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseServiceGrpc;
import net.firedevops.firemud.account.v1.AcquireGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.TokenIdentity;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.RecoveredCredential;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.service.AccountCanonicalFirstJoinTerminalCoordinator;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionLeaseManualFactory;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationAuthorityProjection;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationCommitSignerFixture;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationCommittedIssuanceOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationIssuanceCommitService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseEnvelopeService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationResponseRecoveryOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationTokenRegistry;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeKeyring;
import net.firedevops.firemud.accountservice.service.session.LettuceAccountCoordinationPinnedConnectionProvider;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.State;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OriginKind;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityClientInterceptor;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile.AccountSecurityCutoff;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

/**
 * PostgreSQL proof for the Account-local current-source graph and its authenticated acquire owner.
 *
 * <p>The fixture creates a controlled fresh tenant, typed demo-entitlement event, first active
 * membership/event, and real committed SQL token issuance through their existing owner writers. The
 * source-lock cases retain their command-controlled Redis fixture. The authenticated acquire case
 * uses physical test Redis and actual cryptographic verification over the manual mTLS receiver. The
 * signer performs real RSA/JWS operations with its exact test JWKS, while protected
 * signer/materializer evidence and Game Session published/provisional reads remain controlled
 * boundaries. The fresh Account finalization path uses physical PostgreSQL 18 COMMIT and durable
 * ACK insertion; PG18 retained-receipt replay remains intentionally unavailable. This proves
 * neither production custody, real Game Session owner authority, runtime registration, nor an
 * admitted Game Session binding.
 */
class AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest {
  private static final String SCHEMA_PREFIX = "gameplay_admission_current_";
  private static final String TEST_NAMESPACE = "test";
  private static final String CALLER_WORKLOAD = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String ACCOUNT_WORKLOAD = "spiffe://firemud/ns/test/sa/account-service";
  private static final String ACCOUNT_ISSUER = "firemud-account-service";

  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture(true);

  @TempDir Path temporaryDirectory;
  private final Set<String> runOwnedSchemas = java.util.concurrent.ConcurrentHashMap.newKeySet();

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @AfterEach
  void dropRunOwnedSchemas() {
    JdbcTemplate root = new JdbcTemplate(POSTGRES.dataSource());
    for (String schema : runOwnedSchemas) {
      if (!schema.startsWith(SCHEMA_PREFIX) || !schema.matches("[a-z][a-z0-9_]{0,62}")) {
        throw new IllegalStateException("Refusing to clean a schema not owned by this test");
      }
      root.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }
    runOwnedSchemas.clear();
  }

  @Test
  void positiveCurrentReadReturnsExactFiveSourcesAndOriginalCommitPreservesOwners()
      throws Exception {
    Fixture fixture = newFixture();
    List<String> sourceImageBeforeRead = fixture.sourceAndIssuanceImage();
    CommittedCandidateVerificationData independentCandidate = fixture.readCommittedCandidate();
    var current = fixture.readCurrent(independentCandidate);

    assertThat(fixture.sourceAndIssuanceImage()).isEqualTo(sourceImageBeforeRead);
    assertThat(
            CommittedCandidateVerificationData.hasSameOwnerEvidence(
                independentCandidate, current.currentCommittedCandidate()))
        .isTrue();
    assertThat(current.typedTenantEvent().gameplayAvailable()).isTrue();
    assertThat(current.typedTenantEvent().allowNewGameplayBindings()).isTrue();
    assertThat(current.leaseSourceFields().keySet())
        .containsExactlyInAnyOrder(
            "authorityTuple",
            "issuanceFence",
            "membershipBaseline",
            "outboxCheckpoints",
            "tokenIdentityEvidence");
    assertThat(current.leaseSourceFields()).isEqualTo(expectedLeaseSourceFields(fixture, current));

    AccountGameplayAdmissionLeaseEvidence originalLease =
        fixture.beginPendingAdmissionLease(current);
    List<String> ownerImageBeforeOriginalCommit = fixture.sourceAndIssuanceImage();
    UUID decisionId = UUID.randomUUID();
    var acknowledgement =
        new AccountGameplayAdmissionOriginalCommitExecutor(fixture.dataSource)
            .executeWithCurrentSources(
                originalLease, decisionId, TEST_NAMESPACE, independentCandidate);

    assertThat(acknowledgement.evidence()).isEqualTo(originalLease);
    assertThat(acknowledgement.decisionId()).isEqualTo(decisionId);
    assertThat(acknowledgement.finalizationXid()).matches("[1-9][0-9]{0,19}");
    assertThat(fixture.readLeaseOperation(originalLease).state())
        .isEqualTo(AccountGameplayAdmissionLeaseOperation.State.COMMITTED);
    assertThat(fixture.sourceAndIssuanceImage()).isEqualTo(ownerImageBeforeOriginalCommit);
    assertThat(fixture.readCurrent(fixture.readCommittedCandidate()).leaseSourceFields())
        .isEqualTo(current.leaseSourceFields());
  }

  @Test
  void authenticatedAccountOwnerAcquiresAndRetriesTheExactRecoveredAccountToken() throws Exception {
    try (PhysicalRedisFixture redis = PhysicalRedisFixture.start()) {
      Fixture fixture = newFixture(redis.client());
      var issuanceIdentity = fixture.committedCandidate.identity();
      CallerIdentity recoveryCaller =
          new CallerIdentity(issuanceIdentity.callerWorkload(), issuanceIdentity.callerContextId());
      // Recovery returns the original committed JWT; this test never signs a replacement token.
      RecoveredCredential recovered =
          fixture.responseRecoveryOwner.recoverInitialLoginResponse(
              issuanceIdentity.requestId(), issuanceIdentity.accountId(), recoveryCaller);
      String originalJwt = new String(recovered.compactJwtBytes(), StandardCharsets.US_ASCII);

      assertThat(recovered.profile()).isEqualTo(GameSessionAccountDelegationProfile.PROFILE);
      assertThat(recovered.accountId()).isEqualTo(fixture.account.getAccountUuid());
      assertThat(recovered.tokenJti()).isEqualTo(issuanceIdentity.tokenJti());
      assertThat(recovered.tokenSha256()).isEqualTo(fixture.committedCandidate.tokenSha256());

      Candidate candidate = admissionCandidate(fixture);
      UUID requestId = UUID.randomUUID();
      Target target = admissionTarget(candidate, UUID.randomUUID());
      // This exact typed return is a controlled Game Session transport boundary, not GS authority.
      CanonicalGameplayAdmissionDecisionReadClient decisionClient =
          controlledPublishedTargetClient(candidate, target, requestId);
      controlProvisionalDecisionRead(decisionClient, candidate, target);
      AccountGameplayAdmissionCallerVerifier callerVerifier =
          new AccountGameplayAdmissionCallerVerifier(
              fixture.signerFixture.trustedJwksSource(),
              fixture.committedIssuanceOwner,
              Clock.systemUTC());
      AdmissionClockCapture clockCapture = new AdmissionClockCapture();
      DataSource ownerDataSource = captureAdmissionClockReads(fixture.dataSource, clockCapture);
      AccountGameplayAdmissionLeaseOwner owner =
          new AccountGameplayAdmissionLeaseOwner(
              callerVerifier, decisionClient, TEST_NAMESPACE, ownerDataSource);
      var wireRequest =
          AccountGameplayAdmissionLeaseGrpcCodec.toAcquireRequest(
              requestId, originalJwt, candidate, target, BigInteger.ONE);
      var parsedRequest = AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(wireRequest);
      GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(CALLER_WORKLOAD).orElseThrow();
      UUID accountId = fixture.account.getAccountUuid();
      var sourceImageBeforeAcquire = fixture.sourceAndIssuanceImageForAuthenticatedAcquire();
      var allocatorBeforeAcquire = fixture.leaseAllocationState(requestId, accountId);
      assertThat(allocatorBeforeAcquire).isEqualTo(new LeaseAllocationState(0L, 0L, null, null));

      AtomicReference<AccountGameplayAdmissionLeaseResponse> first = new AtomicReference<>();
      withPeer(peer, () -> first.set(owner.acquire(wireRequest)));
      var firstEvidence =
          AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireResponse(
              parsedRequest, Objects.requireNonNull(first.get()));

      var originalLease = firstEvidence.originalLeaseEvidence();
      long evaluatedAt = Long.parseLong((String) originalLease.carrier().get("evaluatedAt"));
      assertThat(clockCapture.databaseMillis.get()).isEqualTo(evaluatedAt);
      assertThat(clockCapture.clockConnection.get())
          .isNotNull()
          .isSameAs(clockCapture.operationInsertConnection.get());
      assertThat(clockCapture.clockReadUsedEnlistedConnection.get()).isTrue();
      assertThat(clockCapture.operationInsertUsedEnlistedConnection.get()).isTrue();
      long originalExpiry = Long.parseLong((String) originalLease.carrier().get("expiresAt"));
      assertThat(originalExpiry)
          .isEqualTo(
              Math.min(
                  Math.addExact(
                      evaluatedAt, AccountGameplayAdmissionLeaseEvidence.MAX_DURATION_MILLIS),
                  Math.multiplyExact(issuanceIdentity.expiresAtEpochSecond(), 1_000L)));
      UUID originalRequestId = UUID.fromString((String) originalLease.carrier().get("requestId"));
      UUID originalLeaseId = UUID.fromString((String) originalLease.carrier().get("leaseId"));
      long originalFence = originalLease.leaseFence().longValueExact();
      var allocationAfterFirst = fixture.leaseAllocationState(originalRequestId, accountId);
      assertThat(allocationAfterFirst.allocationCount()).isEqualTo(1L);
      assertThat(allocationAfterFirst.fenceCount()).isEqualTo(1L);
      assertThat(allocationAfterFirst.allocation())
          .isEqualTo(
              new LeaseAllocationRow(accountId, originalRequestId, originalLeaseId, originalFence));
      assertThat(allocationAfterFirst.fence())
          .isEqualTo(new LeaseFenceRow(accountId, originalFence));
      assertThat(originalRequestId).isEqualTo(requestId);
      Map<?, ?> originalScope = (Map<?, ?>) originalLease.carrier().get("bindingScope");
      assertThat(originalScope.get("accountId")).isEqualTo(accountId.toString());
      assertThat(fixture.sourceAndIssuanceImageForAuthenticatedAcquire())
          .isEqualTo(sourceImageBeforeAcquire);
      assertThat(fixture.leaseOperationCount()).isEqualTo(1L);

      AtomicReference<AccountGameplayAdmissionLeaseResponse> exactRetry = new AtomicReference<>();
      withPeer(peer, () -> exactRetry.set(owner.acquire(wireRequest)));
      var retryEvidence =
          AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireResponse(
              parsedRequest, Objects.requireNonNull(exactRetry.get()));
      assertThat(firstEvidence.state()).isEqualTo(State.PENDING);
      assertThat(retryEvidence.state()).isEqualTo(State.PENDING);
      assertThat(retryEvidence.originalLeaseEvidence().canonicalJson())
          .isEqualTo(firstEvidence.originalLeaseEvidence().canonicalJson());
      assertThat(exactRetry.get().getOriginalLeaseEvidenceUtf8List())
          .containsExactlyElementsOf(first.get().getOriginalLeaseEvidenceUtf8List());
      assertThat(firstEvidence.originalLeaseEvidence().carrier().get("callerWorkload"))
          .isEqualTo(CALLER_WORKLOAD);
      var currentSources = fixture.readCurrent(fixture.readCommittedCandidate());
      assertThat(firstEvidence.originalLeaseEvidence().carrier())
          .containsAllEntriesOf(expectedLeaseSourceFields(fixture, currentSources));
      assertThat(fixture.leaseAllocationState(originalRequestId, accountId))
          .isEqualTo(allocationAfterFirst);
      assertThat(fixture.sourceAndIssuanceImageForAuthenticatedAcquire())
          .isEqualTo(sourceImageBeforeAcquire);
      assertThat(fixture.leaseOperationCount()).isEqualTo(1L);

      Candidate mismatchedCaller = candidateWithAccount(candidate, UUID.randomUUID());
      var mismatchedRequest =
          AccountGameplayAdmissionLeaseGrpcCodec.toAcquireRequest(
              requestId, originalJwt, mismatchedCaller, target, BigInteger.ONE);
      assertThatThrownBy(() -> withPeer(peer, () -> owner.acquire(mismatchedRequest)))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(((StatusRuntimeException) failure).getStatus().getCode())
                      .isEqualTo(Status.Code.UNAVAILABLE));
      assertThat(fixture.leaseAllocationState(originalRequestId, accountId))
          .isEqualTo(allocationAfterFirst);
      assertThat(fixture.sourceAndIssuanceImageForAuthenticatedAcquire())
          .isEqualTo(sourceImageBeforeAcquire);
      assertThat(fixture.leaseOperationCount()).isEqualTo(1L);
      // Revoking tenant eligibility prevents new admission; it must not prevent the same
      // authenticated original caller from retaining and recovering its cleanup obligation.
      fixture.disableTenantGameplay(fixture.readCurrentEntitlement());
      var sourceImageAfterEntitlementChange =
          fixture.sourceAndIssuanceImageForAuthenticatedAcquire();
      byte[] originalBytes = originalLease.canonicalJson().getBytes(StandardCharsets.UTF_8);
      var readWire =
          AccountGameplayAdmissionLeaseGrpcCodec.toReadRequest(originalJwt, originalBytes);
      var parsedRead = AccountGameplayAdmissionLeaseGrpcCodec.parseReadRequest(readWire);
      var pendingRead = new AtomicReference<AccountGameplayAdmissionLeaseResponse>();
      withPeer(peer, () -> pendingRead.set(owner.read(readWire)));
      assertThat(
              AccountGameplayAdmissionLeaseGrpcCodec.parseReadResponse(
                      parsedRead, pendingRead.get())
                  .state())
          .isEqualTo(State.PENDING);
      assertThatThrownBy(() -> withPeer(peer, () -> owner.acquire(wireRequest)))
          .isInstanceOf(StatusRuntimeException.class);

      var finalizeWire =
          AccountGameplayAdmissionLeaseGrpcCodec.toFinalizeRequest(
              originalJwt, originalBytes, UUID.randomUUID());
      assertThatThrownBy(() -> withPeer(peer, () -> owner.finalizeLease(finalizeWire)))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure ->
                  assertThat(((StatusRuntimeException) failure).getStatus().getCode())
                      .isEqualTo(Status.Code.UNAVAILABLE));
      assertThat(fixture.readLeaseOperation(originalLease).state())
          .isEqualTo(AccountGameplayAdmissionLeaseOperation.State.PENDING);

      var abortWire =
          AccountGameplayAdmissionLeaseGrpcCodec.toAbortRequest(originalJwt, originalBytes, null);
      var parsedAbort = AccountGameplayAdmissionLeaseGrpcCodec.parseAbortRequest(abortWire);
      var abortResponse = new AtomicReference<AccountGameplayAdmissionLeaseResponse>();
      withPeer(peer, () -> abortResponse.set(owner.abort(abortWire)));
      var aborted =
          AccountGameplayAdmissionLeaseGrpcCodec.parseAbortResponse(
              parsedAbort, abortResponse.get());
      assertThat(aborted.state()).isEqualTo(State.ABORTED);
      assertThat(aborted.orphanCleanupId()).isNotNull();
      assertThat(aborted.bindingDecisionId()).isNull();
      assertThat(aborted.originalLeaseEvidence().canonicalJson())
          .isEqualTo(originalLease.canonicalJson());
      withPeer(peer, () -> pendingRead.set(owner.read(readWire)));
      var retainedAbort =
          AccountGameplayAdmissionLeaseGrpcCodec.parseReadResponse(parsedRead, pendingRead.get());
      assertThat(retainedAbort.state()).isEqualTo(State.ABORTED);
      assertThat(retainedAbort.orphanCleanupId()).isEqualTo(aborted.orphanCleanupId());
      assertThat(retainedAbort.originalLeaseEvidence().canonicalJson())
          .isEqualTo(originalLease.canonicalJson());
      assertThat(fixture.leaseAllocationState(originalRequestId, accountId))
          .isEqualTo(allocationAfterFirst);
      assertThat(fixture.sourceAndIssuanceImageForAuthenticatedAcquire())
          .isEqualTo(sourceImageAfterEntitlementChange);
      assertThat(fixture.leaseOperationCount()).isEqualTo(1L);
    }
  }

  @Test
  void authenticatedMtlsOwnerComposesAllFourRpcMethodsWithOriginalCommitAndAck() throws Exception {
    try (PhysicalRedisFixture redis = PhysicalRedisFixture.start()) {
      Fixture fixture = newFixture(redis.client());
      var issuanceIdentity = fixture.committedCandidate.identity();
      CallerIdentity recoveryCaller =
          new CallerIdentity(issuanceIdentity.callerWorkload(), issuanceIdentity.callerContextId());
      // This is the exact JWT recovered from the real committed issuance, never a replacement.
      RecoveredCredential recovered =
          fixture.responseRecoveryOwner.recoverInitialLoginResponse(
              issuanceIdentity.requestId(), issuanceIdentity.accountId(), recoveryCaller);
      String originalJwt = new String(recovered.compactJwtBytes(), StandardCharsets.US_ASCII);
      Candidate candidate = admissionCandidate(fixture);
      UUID accountRequestId = UUID.randomUUID();
      UUID worldInitialAdmissionRequestId = UUID.randomUUID();
      assertThat(worldInitialAdmissionRequestId).isNotEqualTo(accountRequestId);
      assertThat(issuanceIdentity.requestId())
          .isNotEqualTo(accountRequestId)
          .isNotEqualTo(worldInitialAdmissionRequestId);
      Target target = admissionTarget(candidate, worldInitialAdmissionRequestId);
      assertThat(target.initialAdmissionRequestId())
          .isEqualTo(worldInitialAdmissionRequestId.toString());

      // Both Game Session reverse reads stay at a controlled remote boundary. Provisional
      // evidence is nevertheless produced and validated by the canonical closed wire codec using
      // the exact acquired lease; this does not stand in for a Game Session owner or CAS.
      CanonicalGameplayAdmissionDecisionReadClient decisionClient =
          controlledPublishedTargetClient(candidate, target, accountRequestId);
      controlProvisionalDecisionRead(decisionClient, candidate, target);
      AccountGameplayAdmissionCallerVerifier callerVerifier =
          new AccountGameplayAdmissionCallerVerifier(
              fixture.signerFixture.trustedJwksSource(),
              fixture.committedIssuanceOwner,
              Clock.systemUTC());
      AccountGameplayAdmissionLeaseOwner owner =
          new AccountGameplayAdmissionLeaseOwner(
              callerVerifier, decisionClient, TEST_NAMESPACE, fixture.dataSource);

      Path pkiDirectory = Files.createTempDirectory(temporaryDirectory, "gameplay-admission-mtls-");
      var pki =
          LoopbackMtlsTestPki.create(
              pkiDirectory,
              "Gameplay admission Account test CA",
              new LoopbackMtlsTestPki.IdentitySpec("account-server", ACCOUNT_WORKLOAD, true),
              new LoopbackMtlsTestPki.IdentitySpec("game-session-client", CALLER_WORKLOAD, false),
              new LoopbackMtlsTestPki.IdentitySpec(
                  "wrong-role-client",
                  "spiffe://firemud/ns/test/sa/world-management-service",
                  false));
      Server server =
          AccountGameplayAdmissionLeaseManualFactory.createUnregisteredServer(
              "127.0.0.1:0",
              pki.keyManagerFactory(pki.identity("account-server")),
              pki.trustManagerFactory(),
              owner,
              TEST_NAMESPACE);
      ManagedChannel gameSessionChannel = null;
      ManagedChannel wrongRoleChannel = null;
      boolean serverStarted = false;
      try {
        server.start();
        serverStarted = true;
        int port = server.getPort();
        String targetAddress = "127.0.0.1:" + port;
        var channelFactory = new GrpcChannelFactory();
        gameSessionChannel =
            channelFactory.buildChannel(
                targetAddress,
                port,
                pki.clientProperties(pkiDirectory, pki.identity("game-session-client")),
                false);
        var stub =
            AccountGameplayAdmissionLeaseServiceGrpc.newBlockingStub(gameSessionChannel)
                .withMaxInboundMessageSize(
                    AccountGameplayAdmissionLeaseGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
                .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(ACCOUNT_WORKLOAD))
                .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(ACCOUNT_WORKLOAD));

        wrongRoleChannel =
            channelFactory.buildChannel(
                targetAddress,
                port,
                pki.clientProperties(pkiDirectory, pki.identity("wrong-role-client")),
                false);
        var wrongRoleStub =
            AccountGameplayAdmissionLeaseServiceGrpc.newBlockingStub(wrongRoleChannel)
                .withMaxInboundMessageSize(
                    AccountGameplayAdmissionLeaseGrpcCodec.MAX_RESPONSE_WIRE_BYTES)
                .withCallCredentials(new GrpcServerPeerIdentityCallCredentials(ACCOUNT_WORKLOAD))
                .withInterceptors(new GrpcServerPeerIdentityClientInterceptor(ACCOUNT_WORKLOAD));
        UUID accountId = fixture.account.getAccountUuid();
        var sourceImageBeforeAnyRpc = fixture.sourceAndIssuanceImageForAuthenticatedAcquire();
        var allocatorBeforeAnyRpc = fixture.leaseAllocationState(accountRequestId, accountId);
        assertThat(allocatorBeforeAnyRpc).isEqualTo(new LeaseAllocationState(0L, 0L, null, null));
        assertThatThrownBy(
                () ->
                    wrongRoleStub
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .acquireGameplayAdmissionLease(
                            AcquireGameplayAdmissionLeaseRequest.getDefaultInstance()))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(((StatusRuntimeException) failure).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
        assertThatThrownBy(
                () ->
                    ClientCalls.blockingUnaryCall(
                        wrongRoleStub.getChannel(),
                        malformedAcquireMethod(),
                        wrongRoleStub.getCallOptions().withDeadlineAfter(5, TimeUnit.SECONDS),
                        new byte[] {0x0f}))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(((StatusRuntimeException) failure).getStatus().getCode())
                        .isEqualTo(Status.Code.PERMISSION_DENIED));
        assertThat(fixture.leaseOperationCount()).isZero();
        assertThat(fixture.leaseAllocationState(accountRequestId, accountId))
            .isEqualTo(allocatorBeforeAnyRpc);
        assertThat(fixture.sourceAndIssuanceImageForAuthenticatedAcquire())
            .isEqualTo(sourceImageBeforeAnyRpc);

        var sourceImageBeforeAcquire = fixture.sourceAndIssuanceImageForAuthenticatedAcquire();
        var sourceImageBeforeFreshCommit = fixture.sourceAndIssuanceImageForAuthenticatedCommit();
        var allocatorBeforeAcquire = fixture.leaseAllocationState(accountRequestId, accountId);
        assertThat(allocatorBeforeAcquire).isEqualTo(new LeaseAllocationState(0L, 0L, null, null));
        var acquireWire =
            AccountGameplayAdmissionLeaseGrpcCodec.toAcquireRequest(
                accountRequestId, originalJwt, candidate, target, BigInteger.ONE);
        var parsedAcquire = AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(acquireWire);
        var firstResponse =
            stub.withDeadlineAfter(5, TimeUnit.SECONDS).acquireGameplayAdmissionLease(acquireWire);
        var first =
            AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireResponse(
                parsedAcquire, firstResponse);
        assertThat(first.state()).isEqualTo(State.PENDING);
        AccountGameplayAdmissionLeaseEvidence originalLease = first.originalLeaseEvidence();
        byte[] originalLeaseBytes = originalLease.canonicalJson().getBytes(StandardCharsets.UTF_8);
        UUID originalLeaseRequestId =
            UUID.fromString((String) originalLease.carrier().get("requestId"));
        UUID originalLeaseId = UUID.fromString((String) originalLease.carrier().get("leaseId"));
        long originalFence = originalLease.leaseFence().longValueExact();
        assertThat(originalLeaseRequestId).isEqualTo(accountRequestId);
        var allocationAfterAcquire =
            fixture.leaseAllocationState(originalLeaseRequestId, accountId);
        assertThat(allocationAfterAcquire.allocationCount()).isEqualTo(1L);
        assertThat(allocationAfterAcquire.fenceCount()).isEqualTo(1L);
        assertThat(allocationAfterAcquire.allocation())
            .isEqualTo(
                new LeaseAllocationRow(
                    accountId, accountRequestId, originalLeaseId, originalFence));
        assertThat(allocationAfterAcquire.fence())
            .isEqualTo(new LeaseFenceRow(accountId, originalFence));
        assertThat(fixture.sourceAndIssuanceImageForAuthenticatedAcquire())
            .isEqualTo(sourceImageBeforeAcquire);

        var retryResponse =
            stub.withDeadlineAfter(5, TimeUnit.SECONDS).acquireGameplayAdmissionLease(acquireWire);
        var retry =
            AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireResponse(
                parsedAcquire, retryResponse);
        assertThat(retry.state()).isEqualTo(State.PENDING);
        assertThat(retry.originalLeaseEvidence().canonicalJson())
            .isEqualTo(originalLease.canonicalJson());
        assertThat(fixture.leaseAllocationState(originalLeaseRequestId, accountId))
            .isEqualTo(allocationAfterAcquire);
        assertThat(fixture.leaseOperationCount()).isEqualTo(1L);
        assertThat(fixture.sourceAndIssuanceImageForAuthenticatedAcquire())
            .isEqualTo(sourceImageBeforeAcquire);

        var readPendingWire =
            AccountGameplayAdmissionLeaseGrpcCodec.toReadRequest(originalJwt, originalLeaseBytes);
        var parsedReadPending =
            AccountGameplayAdmissionLeaseGrpcCodec.parseReadRequest(readPendingWire);
        var readPendingResponse =
            stub.withDeadlineAfter(5, TimeUnit.SECONDS).readGameplayAdmissionLease(readPendingWire);
        var readPending =
            AccountGameplayAdmissionLeaseGrpcCodec.parseReadResponse(
                parsedReadPending, readPendingResponse);
        assertThat(readPending.state()).isEqualTo(State.PENDING);
        assertThat(readPending.originalLeaseEvidence().canonicalJson())
            .isEqualTo(originalLease.canonicalJson());

        UUID decisionId = UUID.randomUUID();
        var finalizeWire =
            AccountGameplayAdmissionLeaseGrpcCodec.toFinalizeRequest(
                originalJwt, originalLeaseBytes, decisionId);
        var parsedFinalize =
            AccountGameplayAdmissionLeaseGrpcCodec.parseFinalizeRequest(finalizeWire);
        var finalizedResponse =
            stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                .finalizeGameplayAdmissionLease(finalizeWire);
        var finalized =
            AccountGameplayAdmissionLeaseGrpcCodec.parseFinalizeResponse(
                parsedFinalize, finalizedResponse);
        assertThat(finalized.state()).isEqualTo(State.COMMITTED);
        assertThat(finalized.bindingDecisionId()).isEqualTo(decisionId);
        var originalAck = finalized.originalAckReceipt();
        assertThat(originalAck).isNotNull();
        assertThat(originalAck.requestId()).isEqualTo(accountRequestId);
        assertThat(originalAck.accountId()).isEqualTo(accountId);
        assertThat(originalAck.leaseId()).isEqualTo(originalLeaseId);
        assertThat(originalAck.leaseFence()).isEqualTo(originalFence);
        assertThat(originalAck.bindingDecisionId()).isEqualTo(decisionId);
        assertThat(originalAck.committedBeforeMs())
            .isPositive()
            .isLessThan(originalAck.expiresAtMs());
        assertThat(originalAck.finalizationXid()).matches("[1-9][0-9]{0,19}");
        assertThat(originalAck.receiptXid()).matches("[1-9][0-9]{0,19}");
        assertThat(originalAck.receiptXid()).isNotEqualTo(originalAck.finalizationXid());
        assertThat(fixture.readLeaseOperation(originalLease).state())
            .isEqualTo(AccountGameplayAdmissionLeaseOperation.State.COMMITTED);
        assertThat(fixture.leaseAllocationState(originalLeaseRequestId, accountId))
            .isEqualTo(allocationAfterAcquire);
        assertThat(fixture.sourceAndIssuanceImageForAuthenticatedCommit())
            .isEqualTo(sourceImageBeforeFreshCommit);
        assertThat(fixture.originalAckReceiptImage(accountRequestId))
            .isEqualTo(
                List.of(
                    (short) 1,
                    accountRequestId,
                    accountId,
                    originalLeaseId,
                    originalFence,
                    originalLease.sha256(),
                    decisionId,
                    originalAck.expiresAtMs(),
                    originalAck.finalizationXid(),
                    originalAck.committedBeforeMs(),
                    originalAck.receiptXid()));
        var durableReceiptImage = fixture.originalAckReceiptImage(accountRequestId);

        // PostgreSQL 18 supports the fresh receipt COMMIT above. Retained receipt reads
        // intentionally
        // remain on their PG16 contract, so these genuine owner calls must fail closed without
        // restamping or replacing the committed operation and its durable original receipt.
        assertThatThrownBy(
                () ->
                    stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                        .finalizeGameplayAdmissionLease(finalizeWire))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(((StatusRuntimeException) failure).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAVAILABLE));
        var readCommittedWire =
            AccountGameplayAdmissionLeaseGrpcCodec.toReadRequest(originalJwt, originalLeaseBytes);
        assertThatThrownBy(
                () ->
                    stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                        .readGameplayAdmissionLease(readCommittedWire))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(((StatusRuntimeException) failure).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAVAILABLE));
        var abortCommittedWire =
            AccountGameplayAdmissionLeaseGrpcCodec.toAbortRequest(
                originalJwt, originalLeaseBytes, decisionId);
        assertThatThrownBy(
                () ->
                    stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                        .abortGameplayAdmissionLease(abortCommittedWire))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(((StatusRuntimeException) failure).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAVAILABLE));
        assertThat(fixture.readLeaseOperation(originalLease).state())
            .isEqualTo(AccountGameplayAdmissionLeaseOperation.State.COMMITTED);
        assertThat(fixture.originalAckReceiptImage(accountRequestId))
            .isEqualTo(durableReceiptImage);
        assertThat(fixture.leaseAllocationState(originalLeaseRequestId, accountId))
            .isEqualTo(allocationAfterAcquire);
        assertThat(fixture.sourceAndIssuanceImageForAuthenticatedCommit())
            .isEqualTo(sourceImageBeforeFreshCommit);

        UUID abortRequestId = UUID.randomUUID();
        var abortPublished = controlledPublishedTarget(candidate, target, abortRequestId);
        when(decisionClient.readPublishedRealmAdmissionOwner(eq(abortPublished.selector())))
            .thenReturn(abortPublished);
        var allocatorBeforeAbort = fixture.leaseAllocationState(abortRequestId, accountId);
        var sourceImageBeforeAbort = fixture.sourceAndIssuanceImageForAuthenticatedAcquire();
        assertThat(allocatorBeforeAbort)
            .isEqualTo(new LeaseAllocationState(1L, 1L, null, new LeaseFenceRow(accountId, 1L)));
        var abortAcquireWire =
            AccountGameplayAdmissionLeaseGrpcCodec.toAcquireRequest(
                abortRequestId, originalJwt, candidate, target, BigInteger.ONE);
        var parsedAbortAcquire =
            AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(abortAcquireWire);
        var abortAcquireResponse =
            stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                .acquireGameplayAdmissionLease(abortAcquireWire);
        var abortAcquire =
            AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireResponse(
                parsedAbortAcquire, abortAcquireResponse);
        assertThat(abortAcquire.state()).isEqualTo(State.PENDING);
        AccountGameplayAdmissionLeaseEvidence abortedLease = abortAcquire.originalLeaseEvidence();
        UUID abortedLeaseId = UUID.fromString((String) abortedLease.carrier().get("leaseId"));
        long abortedLeaseFence = abortedLease.leaseFence().longValueExact();
        byte[] abortedLeaseBytes = abortedLease.canonicalJson().getBytes(StandardCharsets.UTF_8);
        var allocationAfterAbortAcquire = fixture.leaseAllocationState(abortRequestId, accountId);
        assertThat(allocationAfterAbortAcquire.allocationCount()).isEqualTo(2L);
        assertThat(allocationAfterAbortAcquire.fenceCount()).isEqualTo(1L);
        assertThat(allocationAfterAbortAcquire.allocation())
            .isEqualTo(
                new LeaseAllocationRow(
                    accountId, abortRequestId, abortedLeaseId, abortedLeaseFence));
        assertThat(allocationAfterAbortAcquire.fence()).isEqualTo(new LeaseFenceRow(accountId, 2L));
        assertThat(fixture.sourceAndIssuanceImageForAuthenticatedAcquire())
            .isEqualTo(sourceImageBeforeAbort);

        var abortWire =
            AccountGameplayAdmissionLeaseGrpcCodec.toAbortRequest(
                originalJwt, abortedLeaseBytes, null);
        var parsedAbort = AccountGameplayAdmissionLeaseGrpcCodec.parseAbortRequest(abortWire);
        var abortedResponse =
            stub.withDeadlineAfter(5, TimeUnit.SECONDS).abortGameplayAdmissionLease(abortWire);
        var aborted =
            AccountGameplayAdmissionLeaseGrpcCodec.parseAbortResponse(parsedAbort, abortedResponse);
        assertThat(aborted.state()).isEqualTo(State.ABORTED);
        assertThat(aborted.bindingDecisionId()).isNull();
        assertThat(aborted.orphanCleanupId()).isNotNull();
        assertThat(aborted.originalLeaseEvidence().canonicalJson())
            .isEqualTo(abortedLease.canonicalJson());
        var retainedAbortBeforeFinalize = fixture.readLeaseOperation(abortedLease);
        assertThat(retainedAbortBeforeFinalize.state())
            .isEqualTo(AccountGameplayAdmissionLeaseOperation.State.ABORTED);
        assertThat(retainedAbortBeforeFinalize.orphanCleanupId())
            .isEqualTo(aborted.orphanCleanupId());
        UUID abortDecisionId = UUID.randomUUID();
        AtomicInteger abortDecisionReads = new AtomicInteger();
        doAnswer(
                invocation -> {
                  abortDecisionReads.incrementAndGet();
                  throw Status.UNAVAILABLE
                      .withDescription("Aborted Game Session decision is no longer readable")
                      .asRuntimeException();
                })
            .when(decisionClient)
            .read(eq(abortDecisionId), any());
        var abortFinalizeWire =
            AccountGameplayAdmissionLeaseGrpcCodec.toFinalizeRequest(
                originalJwt, abortedLeaseBytes, abortDecisionId);
        var parsedAbortFinalize =
            AccountGameplayAdmissionLeaseGrpcCodec.parseFinalizeRequest(abortFinalizeWire);
        long operationCountBeforeAbortFinalize = fixture.leaseOperationCount();
        var allocatorBeforeAbortFinalize = fixture.leaseAllocationState(abortRequestId, accountId);
        var sourceImageBeforeAbortFinalize =
            fixture.sourceAndIssuanceImageForAuthenticatedAcquire();
        var abortedFinalizeResponse =
            stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                .finalizeGameplayAdmissionLease(abortFinalizeWire);
        var abortedFinalize =
            AccountGameplayAdmissionLeaseGrpcCodec.parseFinalizeResponse(
                parsedAbortFinalize, abortedFinalizeResponse);
        assertThat(abortedFinalize.state()).isEqualTo(State.ABORTED);
        assertThat(abortedFinalize.bindingDecisionId()).isNull();
        assertThat(abortedFinalize.orphanCleanupId()).isEqualTo(aborted.orphanCleanupId());
        assertThat(abortedFinalize.originalAckReceipt()).isNull();
        assertThat(abortedFinalize.originalLeaseEvidence().canonicalJson())
            .isEqualTo(abortedLease.canonicalJson());
        assertThat(abortDecisionReads.get()).isZero();
        assertThat(fixture.readLeaseOperation(abortedLease)).isEqualTo(retainedAbortBeforeFinalize);
        assertThat(fixture.leaseOperationCount()).isEqualTo(operationCountBeforeAbortFinalize);
        assertThat(fixture.leaseAllocationState(abortRequestId, accountId))
            .isEqualTo(allocatorBeforeAbortFinalize);
        assertThat(fixture.sourceAndIssuanceImageForAuthenticatedAcquire())
            .isEqualTo(sourceImageBeforeAbortFinalize);

        TokenIdentity tokenIdentity = fixture.tokenIdentity(fixture.committedCandidate);
        UUID revocationRequestId = UUID.randomUUID();
        fixture.inSerializableTransaction(
            () ->
                fixture.tokenFences.beginRevocationIntent(
                    tokenIdentity,
                    revocationRequestId,
                    AccountGameplayTokenIdentityFenceRepository.revocationDigest(
                        tokenIdentity, revocationRequestId)));
        assertThatThrownBy(
                () ->
                    stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                        .finalizeGameplayAdmissionLease(abortFinalizeWire))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(((StatusRuntimeException) failure).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAVAILABLE));
        assertThat(abortDecisionReads.get()).isZero();
        assertThat(fixture.readLeaseOperation(abortedLease)).isEqualTo(retainedAbortBeforeFinalize);
        assertThat(fixture.leaseOperationCount()).isEqualTo(operationCountBeforeAbortFinalize);
        assertThat(fixture.leaseAllocationState(abortRequestId, accountId))
            .isEqualTo(allocatorBeforeAbortFinalize);
      } finally {
        if (wrongRoleChannel != null) {
          wrongRoleChannel.shutdownNow();
          assertThat(wrongRoleChannel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        if (gameSessionChannel != null) {
          gameSessionChannel.shutdownNow();
          assertThat(gameSessionChannel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        if (serverStarted) {
          server.shutdownNow();
          assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
      }
    }
  }

  @Test
  void tenantEntitlementWriterWaitsForCurrentSourceLocksUntilOriginalPhysicalCommit()
      throws Exception {
    Fixture fixture = newFixture();
    PreparedAdmission prepared = fixture.prepareAdmission();
    DemoTenantEntitlementSnapshot currentEntitlement = fixture.readCurrentEntitlement();
    CandidateRow candidateBefore = fixture.readCandidateRow(prepared.candidate());
    UUID decisionId = UUID.randomUUID();
    CountDownLatch originalCommitReached = new CountDownLatch(1);
    CountDownLatch allowOriginalCommit = new CountDownLatch(1);
    CountDownLatch writerStarted = new CountDownLatch(1);
    CountDownLatch writerCompleted = new CountDownLatch(1);
    AtomicInteger originalBackendPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    var commitBarrierDataSource =
        holdOriginalCommitBeforePhysicalCommit(
            fixture.dataSource, originalCommitReached, allowOriginalCommit, originalBackendPid);

    try {
      Future<AccountGameplayAdmissionOriginalCommitExecutor.OriginalCommitAcknowledgement>
          originalCommit =
              executor.submit(
                  () ->
                      new AccountGameplayAdmissionOriginalCommitExecutor(commitBarrierDataSource)
                          .executeWithCurrentSources(
                              prepared.lease(), decisionId, TEST_NAMESPACE, prepared.candidate()));
      assertThat(originalCommitReached.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(originalBackendPid.get()).isPositive();

      Future<?> tenantWriter =
          executor.submit(
              () -> {
                writerStarted.countDown();
                try {
                  fixture.disableTenantGameplay(currentEntitlement);
                  return null;
                } finally {
                  writerCompleted.countDown();
                }
              });
      assertThat(writerStarted.await(5, TimeUnit.SECONDS)).isTrue();

      int blockedWriterPid =
          awaitBackendBlockedBy(fixture.dsl, originalBackendPid.get(), 3, TimeUnit.SECONDS);
      assertThat(blockedWriterPid).isPositive();
      assertThat(blockedWriterPid).isNotEqualTo(originalBackendPid.get());
      assertThat(tenantWriter.isDone()).isFalse();
      assertThat(writerCompleted.getCount()).isEqualTo(1L);

      allowOriginalCommit.countDown();
      var acknowledgement = originalCommit.get(5, TimeUnit.SECONDS);
      assertThat(acknowledgement.evidence()).isEqualTo(prepared.lease());
      assertThat(acknowledgement.decisionId()).isEqualTo(decisionId);
      assertThat(acknowledgement.committedBeforeMs())
          .isLessThan(Long.parseLong((String) prepared.lease().carrier().get("expiresAt")));
      tenantWriter.get(5, TimeUnit.SECONDS);
      assertThat(writerCompleted.getCount()).isZero();

      assertThat(fixture.readLeaseOperation(prepared.lease()).state())
          .isEqualTo(AccountGameplayAdmissionLeaseOperation.State.COMMITTED);
      DemoTenantEntitlementSnapshot disabled = fixture.readCurrentEntitlement();
      assertThat(disabled.tenantAuthorityGeneration())
          .isGreaterThan(currentEntitlement.tenantAuthorityGeneration());
      assertThat(disabled.gameplayAvailable()).isFalse();
      assertThat(disabled.allowNewGameplayBindings()).isFalse();
      assertThat(fixture.readCandidateRow(prepared.candidate()))
          .usingRecursiveComparison()
          .isEqualTo(candidateBefore);
    } finally {
      allowOriginalCommit.countDown();
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
    }
  }

  @Test
  void accountAuthorityWriterWaitsForOriginalPhysicalCommitAndInvalidatesOldLease()
      throws Exception {
    Fixture fixture = newFixture();
    PreparedAdmission prepared = fixture.prepareAdmission();
    CandidateRow candidateBefore = fixture.readCandidateRow(prepared.candidate());
    CountDownLatch originalCommitReached = new CountDownLatch(1);
    CountDownLatch allowOriginalCommit = new CountDownLatch(1);
    CountDownLatch writerStarted = new CountDownLatch(1);
    CountDownLatch writerCompleted = new CountDownLatch(1);
    AtomicInteger originalBackendPid = new AtomicInteger();
    UUID decisionId = UUID.randomUUID();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    var commitBarrierDataSource =
        holdOriginalCommitBeforePhysicalCommit(
            fixture.dataSource, originalCommitReached, allowOriginalCommit, originalBackendPid);

    try {
      Future<AccountGameplayAdmissionOriginalCommitExecutor.OriginalCommitAcknowledgement>
          originalCommit =
              executor.submit(
                  () ->
                      new AccountGameplayAdmissionOriginalCommitExecutor(commitBarrierDataSource)
                          .executeWithCurrentSources(
                              prepared.lease(), decisionId, TEST_NAMESPACE, prepared.candidate()));
      assertThat(originalCommitReached.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(originalBackendPid.get()).isPositive();

      Future<?> accountWriter =
          executor.submit(
              () -> {
                writerStarted.countDown();
                try {
                  fixture.inSerializableTransaction(
                      () -> {
                        Account updated =
                            fixture.accounts.findById(fixture.account.getId()).orElseThrow();
                        updated.setPasswordHash("authority-writer-updated-password-hash");
                        fixture.accounts.save(updated);
                        return null;
                      });
                  return null;
                } finally {
                  writerCompleted.countDown();
                }
              });
      assertThat(writerStarted.await(5, TimeUnit.SECONDS)).isTrue();

      int blockedWriterPid =
          awaitBackendBlockedBy(fixture.dsl, originalBackendPid.get(), 3, TimeUnit.SECONDS);
      assertThat(blockedWriterPid).isPositive();
      assertThat(blockedWriterPid).isNotEqualTo(originalBackendPid.get());
      assertThat(accountWriter.isDone()).isFalse();
      assertThat(writerCompleted.getCount()).isEqualTo(1L);

      allowOriginalCommit.countDown();
      var acknowledgement = originalCommit.get(5, TimeUnit.SECONDS);
      assertThat(acknowledgement.evidence()).isEqualTo(prepared.lease());
      assertThat(acknowledgement.evidence().canonicalJson())
          .isEqualTo(prepared.lease().canonicalJson());
      assertThat(acknowledgement.decisionId()).isEqualTo(decisionId);
      assertThat(acknowledgement.finalizationXid()).matches("[1-9][0-9]{0,19}");
      assertThat(acknowledgement.committedBeforeMs())
          .isLessThan(Long.parseLong((String) prepared.lease().carrier().get("expiresAt")));
      accountWriter.get(5, TimeUnit.SECONDS);
      assertThat(writerCompleted.getCount()).isZero();

      assertThat(fixture.accounts.findById(fixture.account.getId()).orElseThrow().getPasswordHash())
          .isEqualTo("authority-writer-updated-password-hash");
      assertThat(fixture.readCandidateRow(prepared.candidate()))
          .usingRecursiveComparison()
          .isEqualTo(candidateBefore);
      assertThat(fixture.readLeaseOperation(prepared.lease()).state())
          .isEqualTo(AccountGameplayAdmissionLeaseOperation.State.COMMITTED);
      assertThatThrownBy(() -> fixture.readCurrent(prepared.candidate()))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      allowOriginalCommit.countDown();
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
    }
  }

  @Test
  void tokenRevocationWriterWaitsForOriginalPhysicalCommitAndInvalidatesOldLease()
      throws Exception {
    Fixture fixture = newFixture();
    PreparedAdmission prepared = fixture.prepareAdmission();
    CandidateRow candidateBefore = fixture.readCandidateRow(prepared.candidate());
    TokenIdentity identity = fixture.tokenIdentity(prepared.candidate());
    UUID revocationRequestId = UUID.randomUUID();
    CountDownLatch originalCommitReached = new CountDownLatch(1);
    CountDownLatch allowOriginalCommit = new CountDownLatch(1);
    CountDownLatch writerStarted = new CountDownLatch(1);
    CountDownLatch writerCompleted = new CountDownLatch(1);
    AtomicInteger originalBackendPid = new AtomicInteger();
    AtomicReference<AccountGameplayTokenIdentityFence> revocation = new AtomicReference<>();
    UUID decisionId = UUID.randomUUID();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    var commitBarrierDataSource =
        holdOriginalCommitBeforePhysicalCommit(
            fixture.dataSource, originalCommitReached, allowOriginalCommit, originalBackendPid);

    try {
      Future<AccountGameplayAdmissionOriginalCommitExecutor.OriginalCommitAcknowledgement>
          originalCommit =
              executor.submit(
                  () ->
                      new AccountGameplayAdmissionOriginalCommitExecutor(commitBarrierDataSource)
                          .executeWithCurrentSources(
                              prepared.lease(), decisionId, TEST_NAMESPACE, prepared.candidate()));
      assertThat(originalCommitReached.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(originalBackendPid.get()).isPositive();

      Future<?> tokenWriter =
          executor.submit(
              () -> {
                writerStarted.countDown();
                try {
                  revocation.set(
                      fixture.inSerializableTransaction(
                          () ->
                              fixture.tokenFences.beginRevocationIntent(
                                  identity,
                                  revocationRequestId,
                                  AccountGameplayTokenIdentityFenceRepository.revocationDigest(
                                      identity, revocationRequestId))));
                  return null;
                } finally {
                  writerCompleted.countDown();
                }
              });
      assertThat(writerStarted.await(5, TimeUnit.SECONDS)).isTrue();

      int blockedWriterPid =
          awaitBackendBlockedBy(fixture.dsl, originalBackendPid.get(), 3, TimeUnit.SECONDS);
      assertThat(blockedWriterPid).isPositive();
      assertThat(blockedWriterPid).isNotEqualTo(originalBackendPid.get());
      assertThat(tokenWriter.isDone()).isFalse();
      assertThat(writerCompleted.getCount()).isEqualTo(1L);

      allowOriginalCommit.countDown();
      var acknowledgement = originalCommit.get(5, TimeUnit.SECONDS);
      assertThat(acknowledgement.evidence()).isEqualTo(prepared.lease());
      assertThat(acknowledgement.evidence().canonicalJson())
          .isEqualTo(prepared.lease().canonicalJson());
      assertThat(acknowledgement.decisionId()).isEqualTo(decisionId);
      assertThat(acknowledgement.finalizationXid()).matches("[1-9][0-9]{0,19}");
      assertThat(acknowledgement.committedBeforeMs())
          .isLessThan(Long.parseLong((String) prepared.lease().carrier().get("expiresAt")));
      tokenWriter.get(5, TimeUnit.SECONDS);
      assertThat(writerCompleted.getCount()).isZero();

      assertThat(revocation.get()).isNotNull();
      assertThat(revocation.get().state())
          .isEqualTo(AccountGameplayTokenIdentityFence.State.PENDING);
      assertThat(revocation.get().tokenIdentityFence()).isEqualTo(2L);
      assertThat(fixture.readCandidateRow(prepared.candidate()))
          .usingRecursiveComparison()
          .isEqualTo(candidateBefore);
      assertThat(fixture.readLeaseOperation(prepared.lease()).state())
          .isEqualTo(AccountGameplayAdmissionLeaseOperation.State.COMMITTED);
      assertThatThrownBy(() -> fixture.readCurrent(prepared.candidate()))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      allowOriginalCommit.countDown();
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
    }
  }

  @Test
  void tenantEntitlementOwnerUpdateRefusesRetainedOriginalLeaseWithoutChangingIssuance()
      throws Exception {
    Fixture fixture = newFixture();
    PreparedAdmission prepared = fixture.prepareAdmission();
    CandidateRow retainedCandidateBefore = fixture.readCandidateRow(prepared.candidate());

    DemoTenantEntitlementSnapshot active = fixture.readCurrentEntitlement();
    fixture.disableTenantGameplay(active);
    DemoTenantEntitlementSnapshot disabled = fixture.readCurrentEntitlement();
    assertThat(disabled.gameplayAvailable()).isFalse();
    assertThat(disabled.allowNewGameplayBindings()).isFalse();

    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalCommitExecutor(fixture.dataSource)
                    .executeWithCurrentSources(
                        prepared.lease(), UUID.randomUUID(), TEST_NAMESPACE, prepared.candidate()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("membership source tenantAuthorityGeneration current equality");

    assertThat(fixture.readCandidateRow(prepared.candidate()))
        .usingRecursiveComparison()
        .isEqualTo(retainedCandidateBefore);
    assertThat(fixture.readLeaseOperation(prepared.lease()).state())
        .isEqualTo(AccountGameplayAdmissionLeaseOperation.State.PENDING);
  }

  @Test
  void changedSqlTokenIdentityFenceRefusesRetainedOriginalLeaseAndCommittedCandidate()
      throws Exception {
    Fixture fixture = newFixture();
    PreparedAdmission prepared = fixture.prepareAdmission();
    CandidateRow retainedCandidateBefore = fixture.readCandidateRow(prepared.candidate());
    TokenIdentity identity = fixture.tokenIdentity(prepared.candidate());
    UUID revocationRequestId = UUID.randomUUID();
    var pendingRevocation =
        fixture.inSerializableTransaction(
            () ->
                fixture.tokenFences.beginRevocationIntent(
                    identity,
                    revocationRequestId,
                    AccountGameplayTokenIdentityFenceRepository.revocationDigest(
                        identity, revocationRequestId)));
    assertThat(pendingRevocation.state())
        .isEqualTo(AccountGameplayTokenIdentityFence.State.PENDING);
    assertThat(pendingRevocation.tokenIdentityFence()).isEqualTo(2L);

    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalCommitExecutor(fixture.dataSource)
                    .executeWithCurrentSources(
                        prepared.lease(), UUID.randomUUID(), TEST_NAMESPACE, prepared.candidate()))
        .isInstanceOf(IllegalStateException.class);

    assertThat(fixture.readCandidateRow(prepared.candidate()))
        .usingRecursiveComparison()
        .isEqualTo(retainedCandidateBefore);
    assertThat(fixture.readLeaseOperation(prepared.lease()).state())
        .isEqualTo(AccountGameplayAdmissionLeaseOperation.State.PENDING);
  }

  private Fixture newFixture() throws Exception {
    return newFixture(null);
  }

  Fixture newFixture(AccountGameplayDelegationRedisClient redisClient) throws Exception {
    String schema = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    if (schema.length() > 63) throw new IllegalStateException("Generated schema name is too long");
    runOwnedSchemas.add(schema);
    DriverManagerDataSource dataSource = POSTGRES.dataSource(schema);
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
    DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
    return new Fixture(schema, dataSource, dsl, manager, temporaryDirectory, redisClient);
  }

  private static int awaitBackendBlockedBy(
      DSLContext observerDsl, int blockingBackendPid, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    do {
      Record waiter =
          observerDsl.fetchOne(
              "SELECT pid FROM pg_stat_activity WHERE datname = current_database() "
                  + "AND pid <> pg_backend_pid() AND wait_event_type = 'Lock' "
                  + "AND ? = ANY(pg_blocking_pids(pid)) AND query LIKE '%account_%' "
                  + "ORDER BY pid LIMIT 1",
              blockingBackendPid);
      if (waiter != null) return waiter.get("pid", Integer.class);
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    return -1;
  }

  private static DataSource holdOriginalCommitBeforePhysicalCommit(
      DataSource delegate,
      CountDownLatch commitReached,
      CountDownLatch allowCommit,
      AtomicInteger backendPid) {
    return new DelegatingDataSource(delegate) {
      @SuppressFBWarnings(
          value = "ODR_OPEN_DATABASE_RESOURCE",
          justification =
              "The returned proxy owns this physical connection and delegates close; "
                  + "construction failure closes the connection before rethrowing.")
      @Override
      public Connection getConnection() throws SQLException {
        Connection physical = super.getConnection();
        try {
          return (Connection)
              java.lang.reflect.Proxy.newProxyInstance(
                  Connection.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (proxy, method, arguments) -> {
                    if (method.getName().equals("commit") && method.getParameterCount() == 0) {
                      backendPid.set(
                          DSL.using(physical, SQLDialect.POSTGRES)
                              .fetchSingle("SELECT pg_backend_pid()")
                              .get(0, Integer.class));
                      commitReached.countDown();
                      try {
                        if (!allowCommit.await(5, TimeUnit.SECONDS)) {
                          throw new SQLException(
                              "Original COMMIT barrier was not released", "57014");
                        }
                      } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new SQLException(
                            "Original COMMIT barrier was interrupted", interrupted);
                      }
                    }
                    if (method.getName().equals("close") && method.getParameterCount() == 0) {
                      physical.close();
                      return null;
                    }
                    try {
                      return method.invoke(physical, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
        } catch (RuntimeException | Error constructionFailure) {
          try {
            physical.close();
          } catch (SQLException closeFailure) {
            constructionFailure.addSuppressed(closeFailure);
          }
          throw constructionFailure;
        }
      }
    };
  }

  private static DataSource captureAdmissionClockReads(
      DataSource delegate, AdmissionClockCapture capture) {
    DelegatingDataSource recordingDataSource =
        new DelegatingDataSource(delegate) {
          @SuppressFBWarnings(
              value = "ODR_OPEN_DATABASE_RESOURCE",
              justification =
                  "The returned proxy owns this physical connection and delegates close; "
                      + "construction failure closes the connection before rethrowing.")
          @Override
          public Connection getConnection() throws SQLException {
            Connection physical = super.getConnection();
            try {
              return (Connection)
                  java.lang.reflect.Proxy.newProxyInstance(
                      Connection.class.getClassLoader(),
                      new Class<?>[] {Connection.class},
                      (proxy, method, arguments) -> {
                        if (method.getName().equals("prepareStatement")
                            && arguments != null
                            && arguments.length > 0
                            && arguments[0] instanceof String sql) {
                          PreparedStatement statement =
                              (PreparedStatement) invokeJdbc(method, physical, arguments);
                          Connection enlistedConnection = (Connection) proxy;
                          if (isAdmissionDatabaseClockQuery(sql)) {
                            capture.clockConnection.set(enlistedConnection);
                            capture.clockReadUsedEnlistedConnection.set(
                                isEnlistedConnection(capture.dataSource, enlistedConnection));
                            return captureClockResult(statement, capture);
                          }
                          if (isAdmissionLeaseInsert(sql)) {
                            capture.operationInsertConnection.set(enlistedConnection);
                            capture.operationInsertUsedEnlistedConnection.set(
                                isEnlistedConnection(capture.dataSource, enlistedConnection));
                          }
                          return statement;
                        }
                        if (method.getName().equals("createStatement")) {
                          Statement statement = (Statement) invokeJdbc(method, physical, arguments);
                          return captureClockStatement(statement, (Connection) proxy, capture);
                        }
                        return invokeJdbc(method, physical, arguments);
                      });
            } catch (RuntimeException | Error constructionFailure) {
              try {
                physical.close();
              } catch (SQLException closeFailure) {
                constructionFailure.addSuppressed(closeFailure);
              }
              throw constructionFailure;
            }
          }
        };
    capture.dataSource = recordingDataSource;
    return recordingDataSource;
  }

  private static PreparedStatement captureClockResult(
      PreparedStatement statement, AdmissionClockCapture capture) {
    AtomicBoolean clockResultPending = new AtomicBoolean();
    return (PreparedStatement)
        java.lang.reflect.Proxy.newProxyInstance(
            PreparedStatement.class.getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            (proxy, method, arguments) -> {
              Object result = invokeJdbc(method, statement, arguments);
              if (method.getName().equals("executeQuery") && result instanceof ResultSet rows) {
                return captureClockRows(rows, capture);
              }
              if (method.getName().equals("execute") && Boolean.TRUE.equals(result)) {
                clockResultPending.set(true);
              }
              if (method.getName().equals("getResultSet")
                  && clockResultPending.getAndSet(false)
                  && result instanceof ResultSet rows) {
                return captureClockRows(rows, capture);
              }
              return result;
            });
  }

  private static Statement captureClockStatement(
      Statement statement, Connection connection, AdmissionClockCapture capture) {
    AtomicBoolean clockResultPending = new AtomicBoolean();
    return (Statement)
        java.lang.reflect.Proxy.newProxyInstance(
            Statement.class.getClassLoader(),
            new Class<?>[] {Statement.class},
            (proxy, method, arguments) -> {
              boolean clockQuery = false;
              if (arguments != null
                  && arguments.length > 0
                  && arguments[0] instanceof String sql
                  && method.getName().startsWith("execute")) {
                clockQuery = isAdmissionDatabaseClockQuery(sql);
                if (clockQuery) {
                  capture.clockConnection.set(connection);
                  capture.clockReadUsedEnlistedConnection.set(
                      isEnlistedConnection(capture.dataSource, connection));
                } else if (isAdmissionLeaseInsert(sql)) {
                  capture.operationInsertConnection.set(connection);
                  capture.operationInsertUsedEnlistedConnection.set(
                      isEnlistedConnection(capture.dataSource, connection));
                }
              }
              Object result = invokeJdbc(method, statement, arguments);
              if (clockQuery && result instanceof ResultSet rows) {
                return captureClockRows(rows, capture);
              }
              if (clockQuery && method.getName().equals("execute") && Boolean.TRUE.equals(result)) {
                clockResultPending.set(true);
              }
              if (method.getName().equals("getResultSet")
                  && clockResultPending.getAndSet(false)
                  && result instanceof ResultSet rows) {
                return captureClockRows(rows, capture);
              }
              return result;
            });
  }

  private static ResultSet captureClockRows(ResultSet rows, AdmissionClockCapture capture) {
    return (ResultSet)
        java.lang.reflect.Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (proxy, method, arguments) -> {
              Object value = invokeJdbc(method, rows, arguments);
              boolean columnRead =
                  method.getName().startsWith("get")
                      && arguments != null
                      && arguments.length > 0
                      && (arguments[0] instanceof Integer || arguments[0] instanceof String);
              if (columnRead && value instanceof Number number) {
                capture.databaseMillis.set(number.longValue());
              } else if (columnRead
                  && value instanceof String text
                  && text.matches("[1-9][0-9]*")) {
                capture.databaseMillis.set(Long.parseLong(text));
              }
              return value;
            });
  }

  private static boolean isAdmissionDatabaseClockQuery(String sql) {
    return sql.toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", "")
        .startsWith("selectfloor(extract(epochfromclock_timestamp())*1000)::bigint");
  }

  private static boolean isAdmissionLeaseInsert(String sql) {
    String normalizedSql = sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    return normalizedSql.startsWith("insert")
        && normalizedSql.contains("account_gameplay_admission_lease_operations");
  }

  private static boolean isEnlistedConnection(DataSource dataSource, Connection connection) {
    Object resource = TransactionSynchronizationManager.getResource(dataSource);
    return resource instanceof ConnectionHolder holder && holder.getConnection() == connection;
  }

  private static Object invokeJdbc(
      java.lang.reflect.Method method, Object target, Object[] arguments) throws Throwable {
    try {
      return method.invoke(target, arguments);
    } catch (java.lang.reflect.InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static final class AdmissionClockCapture {
    private final AtomicLong databaseMillis = new AtomicLong();
    private final AtomicReference<Connection> clockConnection = new AtomicReference<>();
    private final AtomicReference<Connection> operationInsertConnection = new AtomicReference<>();
    private final AtomicBoolean clockReadUsedEnlistedConnection = new AtomicBoolean();
    private final AtomicBoolean operationInsertUsedEnlistedConnection = new AtomicBoolean();
    private DataSource dataSource;
  }

  static Candidate admissionCandidate(Fixture fixture) {
    var identity = fixture.committedCandidate.identity();
    var authority = fixture.committedCandidate.authoritySnapshot();
    return new Candidate(
        identity.accountId(),
        fixture.tenantUuid,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "SHARED",
        UUID.randomUUID(),
        71L,
        UUID.randomUUID(),
        UUID.randomUUID(),
        BigInteger.ONE,
        ACCOUNT_ISSUER,
        BigInteger.valueOf(authority.issuerGeneration()),
        BigInteger.ONE,
        BigInteger.ONE,
        BigInteger.ONE);
  }

  private static Candidate candidateWithAccount(Candidate candidate, UUID accountId) {
    return new Candidate(
        accountId,
        candidate.tenantId(),
        candidate.playableStateNamespaceId(),
        candidate.characterId(),
        candidate.playableStateScope(),
        candidate.gameInstanceId(),
        candidate.runtimeGameInstanceId(),
        candidate.sessionId(),
        candidate.regionId(),
        candidate.regionEpoch(),
        candidate.issuerId(),
        candidate.issuerAuthGeneration(),
        candidate.issuerIndexLayoutVersion(),
        candidate.issuerIndexPartitionCount(),
        candidate.issuerIndexPartitionCapacity());
  }

  private static Target admissionTarget(Candidate candidate, UUID requestId) {
    UUID realmId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID holdId = UUID.randomUUID();
    UUID holdFence = UUID.randomUUID();
    long catalogRevision = 1L;
    long pointerVersion = 1L;
    long activeWorldEpoch = 1L;
    String requestDigest = "c".repeat(64);
    String pointerDigest = "d".repeat(64);
    var holdRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            TEST_NAMESPACE,
            candidate.tenantId(),
            "world",
            realmId,
            candidate.playableStateNamespaceId(),
            candidate.playableStateScope(),
            candidate.gameInstanceId(),
            versionId,
            activeWorldEpoch,
            requestId.toString(),
            requestDigest,
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            catalogRevision,
            null);
    var hold = new WorldCanonicalInitialAdmissionHold.HoldIdentity(holdRequest, holdId, holdFence);
    return new Target(
        TEST_NAMESPACE,
        "tenant",
        candidate.tenantId(),
        "world",
        "World",
        realmId,
        "realm",
        "Realm",
        11L,
        candidate.runtimeGameInstanceId(),
        candidate.playableStateNamespaceId(),
        candidate.playableStateScope(),
        candidate.gameInstanceId(),
        versionId,
        99L,
        catalogRevision,
        pointerVersion,
        pointerDigest,
        activeWorldEpoch,
        requestId.toString(),
        requestDigest,
        OriginKind.NO_PRIOR_POINTER,
        null,
        holdId,
        holdFence,
        hold.holdBindingDigest(),
        101L,
        "sha256:" + "a".repeat(64),
        OwnerProofOutcome.COMMITTED,
        false,
        Instant.parse("2026-10-25T00:00:00Z"),
        "PRESEEDED_ONLY");
  }

  private static CanonicalGameplayAdmissionDecisionReadClient controlledPublishedTargetClient(
      Candidate candidate, Target target, UUID requestId) {
    var published = controlledPublishedTarget(candidate, target, requestId);
    var decisionClient = mock(CanonicalGameplayAdmissionDecisionReadClient.class);
    when(decisionClient.readPublishedRealmAdmissionOwner(any())).thenReturn(published);
    return decisionClient;
  }

  static PublishedRealmEvidence controlledPublishedTarget(
      Candidate candidate, Target target, UUID requestId) {
    var selector =
        new CanonicalGameplayRosterOwnerReadEvidence.Request(
            requestId,
            candidate.accountId(),
            TEST_NAMESPACE,
            target.canonicalTenantId(),
            target.worldSlug(),
            target.realmId(),
            target.realmSlug(),
            target.playableStateNamespaceId(),
            target.playableStateScope(),
            target.canonicalGameInstanceId(),
            target.canonicalVersionId(),
            target.catalogRevision(),
            target.pointerVersion(),
            target.activeWorldEpoch());
    var published = mock(PublishedRealmEvidence.class);
    when(published.selector()).thenReturn(selector);
    when(published.currentTarget()).thenReturn(target);
    return published;
  }

  private static void controlProvisionalDecisionRead(
      CanonicalGameplayAdmissionDecisionReadClient decisionClient,
      Candidate candidate,
      Target target) {
    when(decisionClient.read(any(), any()))
        .thenAnswer(
            invocation -> {
              UUID decisionId = invocation.getArgument(0);
              AccountGameplayAdmissionLeaseEvidence expectedLease = invocation.getArgument(1);
              var wireRead =
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.toRequest(
                      decisionId, expectedLease);
              var parsedRead =
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(wireRead);
              var controlledWireEvidence =
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.toResponse(
                      parsedRead,
                      decisionId,
                      expectedLease,
                      BigInteger.ONE,
                      null,
                      null,
                      candidate,
                      target);
              return CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromResponse(
                  parsedRead, controlledWireEvidence);
            });
  }

  private static MethodDescriptor<byte[], AccountGameplayAdmissionLeaseResponse>
      malformedAcquireMethod() {
    return MethodDescriptor.<byte[], AccountGameplayAdmissionLeaseResponse>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(
            AccountGameplayAdmissionLeaseServiceGrpc.getAcquireGameplayAdmissionLeaseMethod()
                .getFullMethodName())
        .setRequestMarshaller(
            new MethodDescriptor.Marshaller<byte[]>() {
              @Override
              public InputStream stream(byte[] value) {
                return new ByteArrayInputStream(value);
              }

              @Override
              public byte[] parse(InputStream value) {
                throw new UnsupportedOperationException("Request-only raw test marshaller");
              }
            })
        .setResponseMarshaller(
            AccountGameplayAdmissionLeaseServiceGrpc.getAcquireGameplayAdmissionLeaseMethod()
                .getResponseMarshaller())
        .build();
  }

  private static void withPeer(GrpcPeerIdentity peer, Runnable invocation) {
    Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(invocation);
  }

  private static Map<String, Object> expectedLeaseSourceFields(
      Fixture fixture, AccountGameplayAdmissionCurrentSources.Current current) {
    var snapshot = current.snapshot();
    var candidate = current.currentCommittedCandidate();
    var tuple = snapshot.authorityTuple();
    Optional<AccountSecurityCutoff> accountCutoff =
        tuple
            .accountSecurityCutoff()
            .map(
                cutoff ->
                    new AccountSecurityCutoff(
                        cutoff.accountAuthorityGeneration(),
                        cutoff.outboxStreamKey(),
                        cutoff.outboxSequence()));
    Map<String, Object> authorityTuple =
        new LinkedHashMap<>(
            GameSessionAccountDelegationProfile.authorityTuple(
                Long.parseLong(tuple.issuerAuthGeneration()),
                Long.parseLong(tuple.accountAuthorityGeneration()),
                accountCutoff));
    authorityTuple.put("tenantAuthorityGeneration", tuple.tenantAuthorityGeneration());
    authorityTuple.put("membershipAuthorityGeneration", tuple.membershipAuthorityGeneration());
    authorityTuple.put("privateRealmGrantVersions", tuple.privateRealmGrantVersions());
    tuple
        .tenantBillingCutoff()
        .ifPresent(
            cutoffs -> {
              Map<String, Object> mapped = new LinkedHashMap<>();
              cutoffs.forEach(
                  (tenantId, cutoff) ->
                      mapped.put(
                          tenantId,
                          Map.of(
                              "tenantAuthorityGeneration", cutoff.tenantAuthorityGeneration(),
                              "tenantBillingSequence", cutoff.tenantBillingSequence(),
                              "outboxStreamKey", cutoff.outboxStreamKey(),
                              "outboxSequence", cutoff.outboxSequence())));
              authorityTuple.put("tenantBillingCutoff", Map.copyOf(mapped));
            });

    Map<String, Object> membershipBaseline =
        Map.of(
            "membershipLifecycleState", snapshot.membershipLifecycleState(),
            "membershipVersion", snapshot.membershipVersion(),
            "membershipAuthorityGeneration", snapshot.membershipAuthorityGeneration());
    List<Map<String, String>> outboxCheckpoints =
        snapshot.checkpoints().stream()
            .map(
                checkpoint ->
                    Map.of(
                        "outboxStreamKey", checkpoint.outboxStreamKey(),
                        "outboxSequence", checkpoint.outboxSequence()))
            .toList();
    TokenIdentity tokenIdentity = fixture.tokenIdentity(candidate);
    var activeFence = fixture.tokenFences.requireActiveReadback(tokenIdentity);
    assertThat(activeFence.state()).isEqualTo(AccountGameplayTokenIdentityFence.State.ACTIVE);
    assertThat(activeFence.tokenIdentityFence()).isEqualTo(1L);
    var identity = candidate.identity();
    Map<String, Object> tokenEvidence =
        Map.ofEntries(
            Map.entry("accountId", identity.accountId().toString()),
            Map.entry("operationId", identity.operationId().toString()),
            Map.entry("issuanceRequestId", identity.requestId().toString()),
            Map.entry("tokenJti", identity.tokenJti().toString()),
            Map.entry("tokenSHA256", candidate.tokenSha256()),
            Map.entry("tokenGeneration", "1"),
            Map.entry("issuanceFence", snapshot.issuanceFence()),
            Map.entry("tokenIdentityFence", Long.toString(activeFence.tokenIdentityFence())),
            Map.entry("tokenProfile", GameSessionAccountDelegationProfile.PROFILE),
            Map.entry("issuedAt", Long.toString(identity.issuedAtEpochSecond())),
            Map.entry("notBefore", Long.toString(identity.notBeforeEpochSecond())),
            Map.entry("expiresAt", Long.toString(identity.expiresAtEpochSecond())));
    return Map.of(
        "authorityTuple", Map.copyOf(authorityTuple),
        "issuanceFence", snapshot.issuanceFence(),
        "membershipBaseline", membershipBaseline,
        "outboxCheckpoints", outboxCheckpoints,
        "tokenIdentityEvidence", tokenEvidence);
  }

  private record PreparedAdmission(
      AccountGameplayAdmissionLeaseEvidence lease, CommittedCandidateVerificationData candidate) {}

  private record CandidateRow(
      String status, UUID tokenJti, String tokenHash, String proofSha256, byte[] proofBytes) {}

  private record LeaseAllocationRow(
      UUID accountId, UUID requestId, UUID leaseId, long leaseFence) {}

  private record LeaseFenceRow(UUID accountId, long lastFence) {}

  private record LeaseAllocationState(
      long allocationCount, long fenceCount, LeaseAllocationRow allocation, LeaseFenceRow fence) {}

  static final class Fixture {
    private final String schema;
    final DriverManagerDataSource dataSource;
    private final DSLContext dsl;
    private final DataSourceTransactionManager manager;
    private final TransactionTemplate transaction;
    private final TransactionTemplate serializable;
    private final Path temporaryDirectory;
    private final AccountAuthorityGenerationRepository generations;
    private final AccountAuthorityOutboxRepository authorityOutbox;
    private final AccountAuthoritySourceEvidenceRepository sourceEvidence;
    private final AccountRepository accounts;
    private final FreshTenantIdentityAssociationRepository freshTenants;
    private final AccountMembershipPairAuthorityRepository membershipPairs;
    private final AccountTenantMembershipRepository memberships;
    private final AccountTenantMembershipRoleSnapshotRepository membershipRoles;
    private final AccountTenantEntitlementOutboxRepository tenantEntitlementOutbox;
    private final AccountTenantAuthorityEventRepository tenantAuthorityEvents;
    private final AccountDemoTenantEntitlementRepository demoEntitlements;
    private final AccountJoinOperationRepository joinOperations;
    private final AccountCanonicalFirstJoinTerminalCoordinator joinTerminal;
    private final AccountGameplayAdmissionLeaseRepository leases;
    private final AccountGameplayTokenIdentityFenceRepository tokenFences;
    private final AccountGameplayDelegationIssuanceRepository issuance;
    AccountGameplayDelegationCommitSignerFixture signerFixture;
    AccountGameplayDelegationCommittedIssuanceOwner committedIssuanceOwner;
    AccountGameplayDelegationResponseRecoveryOwner responseRecoveryOwner;
    private final UUID tenantUuid = UUID.randomUUID();
    private final UUID joinRequestId = UUID.randomUUID();
    private final String callerBinding = "current-source-fixture-" + UUID.randomUUID();
    private final FreshTenantCreationEvidence tenantEvidence;
    private final VerifiedTenantProvenance tenantProvenance;
    private final CanonicalJoinScopeV2 joinScope;
    private final Account account;
    private final IssuerAccountSourceSnapshot issuanceSource;
    final CommittedCandidateVerificationData committedCandidate;

    private Fixture(
        String schema,
        DriverManagerDataSource dataSource,
        DSLContext dsl,
        DataSourceTransactionManager manager,
        Path temporaryDirectory,
        AccountGameplayDelegationRedisClient redisClient)
        throws Exception {
      this.schema = schema;
      this.dataSource = dataSource;
      this.dsl = dsl;
      this.manager = manager;
      this.transaction = new TransactionTemplate(manager);
      this.serializable = new TransactionTemplate(manager);
      this.serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
      this.temporaryDirectory = temporaryDirectory;
      this.generations = new AccountAuthorityGenerationRepository(dsl);
      this.authorityOutbox = new AccountAuthorityOutboxRepository(dsl);
      this.sourceEvidence =
          new AccountAuthoritySourceEvidenceRepository(dsl, generations, authorityOutbox);
      this.accounts = new AccountRepository(dsl, sourceEvidence);
      this.freshTenants = new FreshTenantIdentityAssociationRepository(dsl, TEST_NAMESPACE);
      this.membershipPairs = new AccountMembershipPairAuthorityRepository(dsl);
      this.memberships =
          new AccountTenantMembershipRepository(dsl, accounts, freshTenants, membershipPairs);
      this.membershipRoles = new AccountTenantMembershipRoleSnapshotRepository(dsl);
      this.tenantEntitlementOutbox = new AccountTenantEntitlementOutboxRepository(dsl);
      this.tenantAuthorityEvents =
          new AccountTenantAuthorityEventRepository(
              dsl, authorityOutbox, tenantEntitlementOutbox, freshTenants, generations);
      this.demoEntitlements =
          new AccountDemoTenantEntitlementRepository(
              dsl, freshTenants, generations, tenantEntitlementOutbox, tenantAuthorityEvents);
      var retainedAssociations = mock(ApprovedLegacyTenantAssociationRepository.class);
      var retainedResolver =
          new AccountTenantIdentityResolver(retainedAssociations, TEST_NAMESPACE);
      var connectScopes = new AccountConnectScopeRepository(dsl, retainedResolver, freshTenants);
      this.joinOperations = new AccountJoinOperationRepository(dsl, connectScopes);
      var membershipEvents =
          new AccountMembershipAuthorityEventProducer(
              joinOperations,
              accounts,
              membershipPairs,
              memberships,
              membershipRoles,
              generations,
              authorityOutbox,
              sourceEvidence,
              tenantAuthorityEvents,
              demoEntitlements);
      this.joinTerminal =
          new AccountCanonicalFirstJoinTerminalCoordinator(
              accounts,
              joinOperations,
              memberships,
              membershipRoles,
              authorityOutbox,
              membershipPairs,
              new AccountAuditOutboxRepository(dsl),
              membershipEvents);
      this.leases = new AccountGameplayAdmissionLeaseRepository(dsl);
      this.tokenFences = new AccountGameplayTokenIdentityFenceRepository(dsl);
      this.tenantEvidence = freshTenantEvidence(tenantUuid);
      this.tenantProvenance =
          new VerifiedTenantProvenance(
              null,
              TenantProvenanceKind.FRESH_GAME_DESIGN,
              tenantEvidence.operationId(),
              tenantEvidence.evidenceDigest());
      Account newAccount = new Account();
      String suffix = UUID.randomUUID().toString().replace("-", "");
      newAccount.setUsername("current-source-" + suffix);
      newAccount.setEmail("current-source-" + suffix + "@example.test");
      newAccount.setPasswordHash("integration-fixture-password-hash");
      newAccount.setRole("player");
      this.account = transaction.execute(status -> accounts.save(newAccount));
      this.joinScope = canonicalJoinScope(account.getAccountUuid(), tenantUuid);
      establishTenantEntitlementAndMembership(connectScopes);

      AccountAuthEvidenceBundleRepository bundles =
          new AccountAuthEvidenceBundleRepository(dsl, generations, authorityOutbox);
      this.issuance = new AccountGameplayDelegationIssuanceRepository(dsl, sourceEvidence, bundles);
      this.issuanceSource =
          transaction.execute(
              status ->
                  sourceEvidence.readCurrentIssuerAccountSources(
                      ACCOUNT_ISSUER, account.getAccountUuid()));
      this.committedCandidate = createCommittedIssuanceCandidate(bundles, redisClient);
    }

    private void establishTenantEntitlementAndMembership(
        AccountConnectScopeRepository connectScopes) {
      transaction.execute(
          status -> {
            freshTenants.importVerified(tenantEvidence);
            sourceEvidence.initializeIssuerIfAbsent(ACCOUNT_ISSUER);
            generations.initializeTenantIfAbsent(tenantUuid);
            generations.initialize(AuthorityScope.membership(account.getAccountUuid(), tenantUuid));
            connectScopes.insertCanonical(account.getId(), joinScope, tenantProvenance);
            joinOperations.insertCanonicalIntent(
                joinRequestId.toString(), joinScope, callerBinding);
            return null;
          });
      DemoTenantEntitlementSnapshot entitlement =
          transaction.execute(
              status ->
                  demoEntitlements.provision(
                      AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest
                          .entitlementRequest(tenantUuid, tenantEvidence),
                      tenantEvidence));
      transaction.execute(
          status -> {
            joinOperations.bindCanonicalPolicyEvidence(
                joinRequestId.toString(),
                joinScope,
                callerBinding,
                entitlement.allowPublicJoin(),
                entitlement.entitlementVersion());
            joinTerminal.commitCanonicalFirstJoin(
                joinScope, joinRequestId.toString(), callerBinding);
            return null;
          });
    }

    private CommittedCandidateVerificationData createCommittedIssuanceCandidate(
        AccountAuthEvidenceBundleRepository bundles,
        AccountGameplayDelegationRedisClient redisClient)
        throws Exception {
      long now = Math.floorDiv(System.currentTimeMillis(), 1_000L);
      Optional<AccountSecurityCutoff> cutoff =
          issuanceSource
              .account()
              .accountSecurityCutoff()
              .map(
                  value ->
                      new AccountSecurityCutoff(
                          value.accountAuthorityGeneration(),
                          value.outboxStreamKey(),
                          value.outboxSequence()));
      UUID operationId = UUID.randomUUID();
      UUID requestId = UUID.randomUUID();
      UUID callerContextId = UUID.randomUUID();
      UUID tokenJti = UUID.randomUUID();
      AccountAuthoritySnapshot authority =
          new AccountAuthoritySnapshot(
              account.getAccountUuid(),
              issuanceSource.issuer().generation(),
              issuanceSource.issuer().sourceVersion(),
              issuanceSource.account().generation(),
              issuanceSource.account().sourceVersion(),
              issuanceSource.issuanceFence().value(),
              issuanceSource.issuanceFence().sourceVersion(),
              cutoff);
      var pending =
          new AccountGameplayDelegationIssuanceRepository.PendingIntent(
              operationId,
              requestId,
              CALLER_WORKLOAD,
              callerContextId,
              tokenJti,
              now,
              now,
              now + 120L,
              authority,
              AccountGameplayCredentialRequestBindingFixture.binding());
      transaction.execute(status -> issuance.beginPending(pending));

      var transactionalIssuance =
          AccountPostgresIntegrationFixture.transactionalIssuanceOwner(issuance, manager);
      Path keyringRoot =
          Files.createDirectories(temporaryDirectory.resolve("response-key-" + UUID.randomUUID()));
      writeResponseKeyring(keyringRoot);
      var responseRepository =
          new AccountGameplayDelegationResponseEnvelopeRepository(
              dsl,
              sourceEvidence,
              bundles,
              new AccountResponseEnvelopeCryptography(
                  new AccountResponseEnvelopeKeyring(keyringRoot.toString())));
      var responseService =
          new AccountGameplayDelegationResponseEnvelopeService(responseRepository);
      this.signerFixture =
          AccountGameplayDelegationCommitSignerFixture.create(
              temporaryDirectory,
              transactionalIssuance,
              responseService,
              manager,
              Clock.systemUTC());
      signerFixture.observeDatabaseFailures(dsl);
      AccountGameplayDelegationRedisClient currentRedisClient = redisClient;
      if (currentRedisClient == null) {
        var redisHarness =
            new RedisHarness(account.getAccountUuid(), issuanceSource, getClass().getClassLoader());
        currentRedisClient = redisHarness.client();
      }
      var registry =
          new AccountGameplayDelegationTokenRegistry(
              transactionalIssuance,
              manager,
              currentRedisClient,
              Clock.systemUTC(),
              GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
              30_000L);
      var authorityProjection =
          new AccountGameplayDelegationAuthorityProjection(
              sourceEvidence, manager, currentRedisClient);
      var commitService =
          new AccountGameplayDelegationIssuanceCommitService(
              signerFixture.signer(),
              registry,
              authorityProjection,
              transactionalIssuance,
              manager,
              Clock.systemUTC());
      var result = commitService.commitPendingCandidate(requestId);
      assertThat(result.outcome())
          .isEqualTo(AccountGameplayDelegationIssuanceCommitService.Outcome.COMMITTED);
      this.committedIssuanceOwner =
          new AccountGameplayDelegationCommittedIssuanceOwner(
              transactionalIssuance, signerFixture.signer(), registry, manager, Clock.systemUTC());
      this.responseRecoveryOwner =
          new AccountGameplayDelegationResponseRecoveryOwner(
              responseRepository, committedIssuanceOwner, manager);
      return transaction.execute(
          status -> issuance.readCurrentCommittedCandidateByJti(tokenJti).orElseThrow());
    }

    private AccountGameplayAdmissionCurrentSources.Current readCurrent(
        CommittedCandidateVerificationData expectedCandidate) {
      return serializable.execute(
          status -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Integer.valueOf(Connection.TRANSACTION_SERIALIZABLE)
                    .equals(
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
              throw new IllegalStateException("Expected writable SERIALIZABLE Account transaction");
            }
            Object resource = TransactionSynchronizationManager.getResource(dataSource);
            if (!(resource instanceof ConnectionHolder holder)) {
              throw new IllegalStateException("Expected the manager-enlisted Account connection");
            }
            Connection connection = holder.getConnection();
            DSLContext exactDsl = DSL.using(connection, SQLDialect.POSTGRES);
            try {
              Integer backendBefore =
                  exactDsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class);
              var current =
                  AccountGameplayAdmissionCurrentSources.bind(exactDsl, TEST_NAMESPACE)
                      .readCurrentPublicProductionNew(
                          tenantUuid, expectedCandidate.identity().tokenJti(), expectedCandidate);
              Integer backendAfter =
                  exactDsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class);
              assertThat(backendAfter).isEqualTo(backendBefore);
              assertThat(connection.getAutoCommit()).isFalse();
              assertThat(connection.isReadOnly()).isFalse();
              assertThat(connection.getTransactionIsolation())
                  .isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
              return current;
            } catch (SQLException failure) {
              throw new IllegalStateException(
                  "Could not verify the enlisted Account connection", failure);
            }
          });
    }

    private PreparedAdmission prepareAdmission() {
      CommittedCandidateVerificationData candidate = readCommittedCandidate();
      var current = readCurrent(candidate);
      return new PreparedAdmission(beginPendingAdmissionLease(current), candidate);
    }

    private AccountGameplayAdmissionLeaseEvidence beginPendingAdmissionLease(
        AccountGameplayAdmissionCurrentSources.Current current) {
      return inSerializableTransaction(
          () -> {
            UUID requestId = UUID.randomUUID();
            UUID leaseId = UUID.randomUUID();
            var allocation = leases.allocate(account.getAccountUuid(), requestId, leaseId);
            long evaluatedAt = currentDatabaseMillisOnEnlistedConnection();
            Map<String, Object> carrier = new LinkedHashMap<>();
            carrier.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
            carrier.put("schemaVersion", "1");
            carrier.put("mode", "PUBLIC_PRODUCTION");
            carrier.put("targetNamespace", TEST_NAMESPACE);
            carrier.put("callerWorkload", CALLER_WORKLOAD);
            carrier.put("requestId", allocation.requestId().toString());
            carrier.put("leaseId", allocation.leaseId().toString());
            carrier.put("leaseFence", Long.toString(allocation.leaseFence()));
            carrier.put("leaseKind", "NEW_BINDING");
            carrier.put("bindingScope", bindingScope());
            carrier.putAll(current.leaseSourceFields());
            carrier.put("evaluatedAt", Long.toString(evaluatedAt));
            carrier.put("expiresAt", Long.toString(evaluatedAt + 14_000L));
            var evidence = AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
            leases.beginPending(evidence);
            return evidence;
          });
    }

    private Map<String, Object> bindingScope() {
      return Map.ofEntries(
          Map.entry("accountId", account.getAccountUuid().toString()),
          Map.entry("tenantId", tenantUuid.toString()),
          Map.entry("realmId", UUID.randomUUID().toString()),
          Map.entry("worldSlug", "world"),
          Map.entry("realmSlug", "realm"),
          Map.entry("playableStateNamespaceId", UUID.randomUUID().toString()),
          Map.entry("playableStateScope", "SHARED"),
          Map.entry("gameInstanceId", UUID.randomUUID().toString()),
          Map.entry("characterId", UUID.randomUUID().toString()),
          Map.entry("sessionId", UUID.randomUUID().toString()),
          Map.entry("bindingGeneration", "1"),
          Map.entry("catalogRevision", "1"),
          Map.entry("pointerVersion", "1"),
          Map.entry("regionId", UUID.randomUUID().toString()),
          Map.entry("regionEpoch", "1"));
    }

    private CommittedCandidateVerificationData readCommittedCandidate() {
      return transaction.execute(
          status ->
              issuance
                  .readCurrentCommittedCandidateByJti(committedCandidate.identity().tokenJti())
                  .orElseThrow());
    }

    private TokenIdentity tokenIdentity(CommittedCandidateVerificationData candidate) {
      var identity = candidate.identity();
      return new TokenIdentity(
          identity.accountId(),
          identity.operationId(),
          identity.requestId(),
          candidate.tokenSha256(),
          identity.tokenJti(),
          identity.notBeforeEpochSecond(),
          1L,
          candidate.authoritySnapshot().issuanceFence());
    }

    private DemoTenantEntitlementSnapshot readCurrentEntitlement() {
      return transaction.execute(status -> demoEntitlements.readCurrent(tenantUuid));
    }

    private void disableTenantGameplay(DemoTenantEntitlementSnapshot current) {
      var request =
          new DemoTenantEntitlementRequest(
              UUID.randomUUID(),
              tenantUuid,
              tenantEvidence.creationRequestId(),
              tenantEvidence.requestDigest(),
              current.entitlementVersion(),
              current.tenantAuthorityGeneration(),
              current.tenantAuthoritySourceVersion(),
              false,
              false,
              false,
              false,
              current.quotas());
      transaction.execute(status -> demoEntitlements.provision(request, tenantEvidence));
    }

    private AccountGameplayAdmissionLeaseOperation readLeaseOperation(
        AccountGameplayAdmissionLeaseEvidence evidence) {
      return inSerializableTransaction(
          () ->
              leases
                  .findByRequest(
                      account.getAccountUuid(),
                      UUID.fromString((String) evidence.carrier().get("requestId")),
                      CALLER_WORKLOAD)
                  .orElseThrow());
    }

    private CandidateRow readCandidateRow(CommittedCandidateVerificationData candidate) {
      Record row =
          Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT status, token_jti, token_hash, commit_proof_sha256, "
                      + "commit_proof_canonical_bytes "
                      + "FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                  candidate.identity().requestId()),
              "The retained committed candidate row is absent");
      return new CandidateRow(
          row.get("status", String.class),
          row.get("token_jti", UUID.class),
          row.get("token_hash", String.class),
          row.get("commit_proof_sha256", String.class),
          row.get("commit_proof_canonical_bytes", byte[].class));
    }

    private long leaseOperationCount() {
      return Objects.requireNonNull(
              dsl.fetchOne("SELECT count(*) FROM account_gameplay_admission_lease_operations"),
              "The Account lease operation count query must return a row")
          .get(0, Long.class);
    }

    private LeaseAllocationState leaseAllocationState(UUID requestId, UUID accountId) {
      long allocationCount =
          Objects.requireNonNull(
                  dsl.fetchOne("SELECT count(*) FROM account_gameplay_admission_lease_allocations"),
                  "The Account lease allocation count query must return a row")
              .get(0, Long.class);
      long fenceCount =
          Objects.requireNonNull(
                  dsl.fetchOne("SELECT count(*) FROM account_gameplay_admission_lease_fences"),
                  "The Account lease fence count query must return a row")
              .get(0, Long.class);
      Record allocationRecord =
          dsl.fetchOne(
              "SELECT account_uuid, request_id, lease_id, lease_fence "
                  + "FROM account_gameplay_admission_lease_allocations WHERE request_id = ?",
              requestId);
      Record fenceRecord =
          dsl.fetchOne(
              "SELECT account_uuid, last_fence "
                  + "FROM account_gameplay_admission_lease_fences WHERE account_uuid = ?",
              accountId);
      LeaseAllocationRow allocation =
          allocationRecord == null
              ? null
              : new LeaseAllocationRow(
                  allocationRecord.get("account_uuid", UUID.class),
                  allocationRecord.get("request_id", UUID.class),
                  allocationRecord.get("lease_id", UUID.class),
                  allocationRecord.get("lease_fence", Long.class));
      LeaseFenceRow fence =
          fenceRecord == null
              ? null
              : new LeaseFenceRow(
                  fenceRecord.get("account_uuid", UUID.class),
                  fenceRecord.get("last_fence", Long.class));
      return new LeaseAllocationState(allocationCount, fenceCount, allocation, fence);
    }

    private List<Object> originalAckReceiptImage(UUID requestId) {
      Record receipt =
          dsl.fetchOne(
              "SELECT schema_version, request_id, account_uuid, lease_id, lease_fence, "
                  + "evidence_sha256, binding_decision_id, expires_at_ms, finalization_xid, "
                  + "committed_before_ms, receipt_xid "
                  + "FROM account_gameplay_admission_original_commit_ack_receipts "
                  + "WHERE request_id = ?",
              requestId);
      if (receipt == null) return List.of();
      return List.of(
          receipt.get("schema_version", Short.class),
          receipt.get("request_id", UUID.class),
          receipt.get("account_uuid", UUID.class),
          receipt.get("lease_id", UUID.class),
          receipt.get("lease_fence", Long.class),
          receipt.get("evidence_sha256", String.class),
          receipt.get("binding_decision_id", UUID.class),
          receipt.get("expires_at_ms", Long.class),
          receipt.get("finalization_xid", String.class),
          receipt.get("committed_before_ms", Long.class),
          receipt.get("receipt_xid", String.class));
    }

    private List<String> sourceAndIssuanceImage() {
      List<String> image = new ArrayList<>();
      for (Record tableRow :
          dsl.fetch(
              "SELECT tablename FROM pg_tables WHERE schemaname = current_schema() "
                  + "AND tablename <> 'account_gameplay_admission_lease_operations' "
                  + "ORDER BY tablename")) {
        String table = tableRow.get("tablename", String.class).replace("\"", "\"\"");
        Record state =
            dsl.fetchOne(
                "SELECT count(*)::bigint, "
                    + "COALESCE(string_agg(xmin::text, ',' ORDER BY xmin::text), '') "
                    + "FROM \""
                    + table
                    + "\"");
        image.add(
            table
                + ":"
                + Objects.requireNonNull(state).get(0, Long.class)
                + ":"
                + state.get(1, String.class));
      }
      return List.copyOf(image);
    }

    /** Adds only the two legitimate allocator-table exclusions to the original strict image. */
    private List<String> sourceAndIssuanceImageForAuthenticatedAcquire() {
      return sourceAndIssuanceImage().stream()
          .filter(
              tableImage ->
                  !tableImage.startsWith("account_gameplay_admission_lease_allocations:")
                      && !tableImage.startsWith("account_gameplay_admission_lease_fences:"))
          .toList();
    }

    /** Also excludes only the durable ACK table legitimately written by fresh finalization. */
    private List<String> sourceAndIssuanceImageForAuthenticatedCommit() {
      return sourceAndIssuanceImage().stream()
          .filter(
              tableImage ->
                  !tableImage.startsWith("account_gameplay_admission_lease_allocations:")
                      && !tableImage.startsWith("account_gameplay_admission_lease_fences:")
                      && !tableImage.startsWith(
                          "account_gameplay_admission_original_commit_ack_receipts:"))
          .toList();
    }

    private long currentDatabaseMillisOnEnlistedConnection() {
      Object resource = TransactionSynchronizationManager.getResource(dataSource);
      if (!(resource instanceof ConnectionHolder holder)) {
        throw new IllegalStateException("Expected the manager-enlisted Account connection");
      }
      DSLContext exactDsl = DSL.using(holder.getConnection(), SQLDialect.POSTGRES);
      Long databaseMillis =
          exactDsl
              .fetchSingle("SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint")
              .get(0, Long.class);
      if (databaseMillis == null || databaseMillis <= 0L) {
        throw new IllegalStateException("PostgreSQL millisecond clock readback is unavailable");
      }
      return databaseMillis;
    }

    private <T> T inSerializableTransaction(java.util.function.Supplier<T> operation) {
      return serializable.execute(status -> operation.get());
    }

    private static FreshTenantCreationEvidence freshTenantEvidence(UUID tenantUuid) {
      UUID requestId = UUID.randomUUID();
      UUID operationId = UUID.randomUUID();
      String sourceTenantKey =
          "fresh-" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
      String requestDigest =
          GameTenantCreationDigest.requestDigest(
              TEST_NAMESPACE, requestId, sourceTenantKey, "Admission proof tenant", null);
      long sourceGameRowId =
          Math.max(1L, UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
      String provenanceKind = "NEW_GAME_ROW";
      return new FreshTenantCreationEvidence(
          1,
          TEST_NAMESPACE,
          requestId,
          operationId,
          requestDigest,
          tenantUuid,
          sourceGameRowId,
          sourceTenantKey,
          provenanceKind,
          GameTenantCreationDigest.evidenceDigest(
              TEST_NAMESPACE,
              requestId,
              operationId,
              requestDigest,
              tenantUuid,
              sourceGameRowId,
              sourceTenantKey,
              provenanceKind));
    }

    private static void writeResponseKeyring(Path root) throws Exception {
      byte[] key = new byte[32];
      for (int index = 0; index < key.length; index++) key[index] = (byte) (index + 1);
      String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
      Files.writeString(
          root.resolve("keyring"),
          "firemud-account-response-envelope-keyring-v1\nactive integration-key " + encoded + "\n",
          StandardCharsets.US_ASCII);
    }
  }

  /** Run-owned local primary/replica used only for the authenticated Account-owner case. */
  static final class PhysicalRedisFixture implements AutoCloseable {
    private static final int REDIS_PORT = 6379;
    private static final String ACCOUNT_PASSWORD = UUID.randomUUID().toString().replace("-", "");
    private static final String PRIMARY_ALIAS = "account-gameplay-admission-primary";
    private static final String ISSUER_OWNER = "gamesession_coord_app";
    private static final String ISSUER_OWNER_PASSWORD =
        UUID.randomUUID().toString().replace("-", "");

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> primary =
        new GenericContainer<>(TestContainerImages.redis())
            .withNetwork(network)
            .withNetworkAliases(PRIMARY_ALIAS)
            .withExposedPorts(REDIS_PORT)
            .withCommand(
                "redis-server",
                "--bind",
                "0.0.0.0",
                "--protected-mode",
                "no",
                "--appendonly",
                "yes",
                "--appendfsync",
                "always");
    private final GenericContainer<?> replica =
        new GenericContainer<>(TestContainerImages.redis())
            .withNetwork(network)
            .dependsOn(primary)
            .withCommand(
                "redis-server",
                "--bind",
                "0.0.0.0",
                "--protected-mode",
                "no",
                "--appendonly",
                "yes",
                "--appendfsync",
                "always",
                "--replicaof",
                PRIMARY_ALIAS,
                Integer.toString(REDIS_PORT));

    private RedisClient bootstrapClient;
    private RedisClient issuerOwnerClient;
    private LettuceAccountCoordinationPinnedConnectionProvider accountProvider;
    private AccountGameplayDelegationRedisClient accountClient;

    static PhysicalRedisFixture start() throws InterruptedException {
      assumeTrue(
          DockerClientFactory.instance().isDockerAvailable(),
          "Docker is unavailable; authenticated Account-owner Redis proof is skipped");
      PhysicalRedisFixture fixture = new PhysicalRedisFixture();
      try {
        fixture.startOwnedRedis();
        return fixture;
      } catch (RuntimeException | InterruptedException startFailure) {
        fixture.close();
        throw startFailure;
      }
    }

    private void startOwnedRedis() throws InterruptedException {
      primary.start();
      replica.start();
      int primaryPort = primary.getMappedPort(REDIS_PORT);
      bootstrapClient = redisClient("127.0.0.1", primaryPort, null);
      try (var connection = bootstrapClient.connect(ByteArrayCodec.INSTANCE)) {
        RedisCommands<byte[], byte[]> commands = connection.sync();
        configureAccountUser(commands);
        configureIssuerOwner(commands);
        awaitLocalAndReplicaAof(commands);
      }

      RedisURI issuerUri =
          RedisURI.Builder.redis("127.0.0.1", primaryPort)
              .withAuthentication(ISSUER_OWNER, ISSUER_OWNER_PASSWORD)
              .build();
      issuerUri.setTimeout(Duration.ofSeconds(5));
      issuerOwnerClient = RedisClient.create(issuerUri);
      issuerOwnerClient.setOptions(ClientOptions.builder().autoReconnect(false).build());

      RedisURI accountUri =
          RedisURI.Builder.redis("127.0.0.1", primaryPort)
              .withAuthentication(
                  AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY, ACCOUNT_PASSWORD)
              .build();
      accountUri.setTimeout(Duration.ofSeconds(5));
      accountProvider = new LettuceAccountCoordinationPinnedConnectionProvider(accountUri);
      ClassLoader classLoader = PhysicalRedisFixture.class.getClassLoader();
      accountClient =
          new AccountGameplayDelegationRedisClient(
              accountProvider,
              RedisScriptCatalog.loadInstalled(classLoader),
              new AcknowledgementRequirements(1, 1, 5_000),
              Clock.systemUTC(),
              GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
              classLoader);
    }

    AccountGameplayDelegationRedisClient client() {
      return Objects.requireNonNull(accountClient, "Owned Account Redis client is unavailable");
    }

    RedisClient issuerOwnerClient() {
      return Objects.requireNonNull(issuerOwnerClient, "Owned issuer Redis client is unavailable");
    }

    private static RedisClient redisClient(String host, int port, String password) {
      RedisURI.Builder uri = RedisURI.Builder.redis(host, port);
      if (password != null) {
        uri.withAuthentication(
            AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY, password);
      }
      RedisClient client = RedisClient.create(uri.build());
      client.setOptions(ClientOptions.builder().autoReconnect(false).build());
      return client;
    }

    private static void configureAccountUser(RedisCommands<byte[], byte[]> commands) {
      commands.aclSetuser(
          AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY,
          new AclSetuserArgs()
              .reset()
              .on()
              .addPassword(ACCOUNT_PASSWORD)
              .keyPattern("session:auth:token:*")
              .keyPattern("session:auth:generation:issuer:*")
              .keyPattern("session:auth:generation:account:*")
              .keyPattern("session:auth:generation:tenant:*")
              .keyPattern("session:auth:generation:membership:*")
              .addCommand(CommandType.HELLO)
              .addCommand(CommandType.PING)
              .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
              .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
              .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
              .addCommand(CommandType.EVALSHA)
              .addCommand(CommandType.GET)
              .addCommand(CommandType.SET)
              .addCommand(CommandType.PEXPIRETIME)
              .addCommand(CommandType.PTTL)
              .addCommand(CommandType.TYPE));
      commands.dispatch(
          TestAclSetuser.INSTANCE,
          new StatusOutput<>(ByteArrayCodec.INSTANCE),
          new CommandArgs<>(ByteArrayCodec.INSTANCE)
              .add("SETUSER")
              .add(AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY)
              .add("+waitaof"));
    }

    private static void configureIssuerOwner(RedisCommands<byte[], byte[]> commands) {
      commands.aclSetuser(
          ISSUER_OWNER,
          new AclSetuserArgs()
              .reset()
              .on()
              .addPassword(ISSUER_OWNER_PASSWORD)
              .keyPattern("session:game:index:issuer:*")
              .addCommand(CommandType.HELLO)
              .addCommand(CommandType.PING)
              .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
              .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
              .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
              .addCommand(CommandType.EVALSHA)
              .addCommand(CommandType.TYPE)
              .addCommand(CommandType.PTTL)
              .addCommand(CommandType.HGET)
              .addCommand(CommandType.HLEN)
              .addCommand(CommandType.HGETALL)
              .addCommand(CommandType.HSET)
              .addCommand(CommandType.HDEL));
    }

    private static void awaitLocalAndReplicaAof(RedisCommands<byte[], byte[]> commands)
        throws InterruptedException {
      byte[] probeKey =
          ("test-only:account-gameplay-admission-aof-probe:" + UUID.randomUUID())
              .getBytes(StandardCharsets.US_ASCII);
      commands.set(probeKey, "test-only-replication-probe".getBytes(StandardCharsets.US_ASCII));
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      try {
        while (System.nanoTime() < deadline) {
          List<Object> counts =
              commands.dispatch(
                  TestWaitAof.INSTANCE,
                  new ArrayOutput<>(ByteArrayCodec.INSTANCE),
                  new CommandArgs<>(ByteArrayCodec.INSTANCE).add(1).add(1).add(500));
          if (hasAofCounts(counts, 1, 1)) return;
          Thread.sleep(50);
        }
        throw new IllegalStateException("Test Account Coordination Redis replica lacks AOF proof");
      } finally {
        commands.del(List.of(probeKey).toArray(byte[][]::new));
      }
    }

    private static boolean hasAofCounts(List<Object> counts, long local, long replicas) {
      return counts != null
          && counts.size() == 2
          && counts.get(0) instanceof Long localCount
          && localCount >= local
          && counts.get(1) instanceof Long replicaCount
          && replicaCount >= replicas;
    }

    @Override
    public void close() {
      RuntimeException cleanupFailure = null;
      if (accountProvider != null) {
        try {
          accountProvider.close();
        } catch (RuntimeException failure) {
          cleanupFailure = failure;
        }
      }
      if (bootstrapClient != null) {
        try {
          bootstrapClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
        } catch (RuntimeException failure) {
          if (cleanupFailure == null) cleanupFailure = failure;
          else cleanupFailure.addSuppressed(failure);
        }
      }
      if (issuerOwnerClient != null) {
        try {
          issuerOwnerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
        } catch (RuntimeException failure) {
          if (cleanupFailure == null) cleanupFailure = failure;
          else cleanupFailure.addSuppressed(failure);
        }
      }
      if (replica.isRunning()) {
        try {
          replica.stop();
        } catch (RuntimeException failure) {
          if (cleanupFailure == null) cleanupFailure = failure;
          else cleanupFailure.addSuppressed(failure);
        }
      }
      if (primary.isRunning()) {
        try {
          primary.stop();
        } catch (RuntimeException failure) {
          if (cleanupFailure == null) cleanupFailure = failure;
          else cleanupFailure.addSuppressed(failure);
        }
      }
      try {
        network.close();
      } catch (RuntimeException failure) {
        if (cleanupFailure == null) cleanupFailure = failure;
        else cleanupFailure.addSuppressed(failure);
      }
      if (cleanupFailure != null) throw cleanupFailure;
    }
  }

  private enum TestWaitAof implements ProtocolKeyword {
    INSTANCE;

    @Override
    public byte[] getBytes() {
      return "WAITAOF".getBytes(StandardCharsets.US_ASCII);
    }
  }

  private enum TestAclSetuser implements ProtocolKeyword {
    INSTANCE;

    @Override
    public byte[] getBytes() {
      return "ACL".getBytes(StandardCharsets.US_ASCII);
    }
  }

  private static final class RedisHarness {
    private final UUID accountId;
    private final byte[][] authorityProjectionBytes;
    private final AtomicReference<byte[]> pendingRecord = new AtomicReference<>();
    private final AtomicLong absoluteExpiryMillis = new AtomicLong();
    private final StatefulRedisConnection<byte[], byte[]> connection =
        mock(StatefulRedisConnection.class);
    private final RedisCommands<byte[], byte[]> commands = mock(RedisCommands.class);
    private final AccountGameplayDelegationRedisClient client;

    private RedisHarness(
        UUID accountId, IssuerAccountSourceSnapshot source, ClassLoader resourceClassLoader)
        throws Exception {
      this.accountId = accountId;
      authorityProjectionBytes =
          AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(source);
      when(connection.getOptions())
          .thenReturn(ClientOptions.builder().autoReconnect(false).build());
      when(connection.isOpen()).thenReturn(true);
      when(connection.sync()).thenReturn(commands);
      when(commands.aclWhoami())
          .thenReturn(AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY);
      when(commands.scriptLoad(any(byte[].class)))
          .thenAnswer(invocation -> sha1(invocation.getArgument(0)));
      when(commands.<Long>evalsha(
              anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class)))
          .thenAnswer(
              invocation -> {
                byte[][] keys = (byte[][]) invocation.getRawArguments()[2];
                byte[][] arguments = (byte[][]) invocation.getRawArguments()[3];
                String key = new String(keys[0], StandardCharsets.US_ASCII);
                if (key.startsWith(AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX)) {
                  if (arguments.length == 2) pendingRecord.set(arguments[0].clone());
                  else if (arguments.length == 3) pendingRecord.set(arguments[1].clone());
                  else throw new AssertionError("Unexpected token registry script arguments");
                  absoluteExpiryMillis.set(
                      Long.parseLong(
                          new String(arguments[arguments.length - 1], StandardCharsets.US_ASCII)));
                } else if (key.equals(
                    AccountGameplayDelegationAuthorityProjection.ISSUER_KEY_PREFIX
                        + GameSessionAccountDelegationProfile.ISSUER)) {
                  authorityProjectionBytes[0] = arguments[0].clone();
                } else if (key.equals(
                    AccountGameplayDelegationAuthorityProjection.ACCOUNT_KEY_PREFIX + accountId)) {
                  if (arguments.length == 3 && arguments[2].length > 0) {
                    authorityProjectionBytes[1] = arguments[2].clone();
                  }
                }
                return 1L;
              });
      when(commands.get(any(byte[].class)))
          .thenAnswer(
              invocation -> {
                String key = new String(invocation.getArgument(0), StandardCharsets.US_ASCII);
                if (key.startsWith(AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX)) {
                  byte[] value = pendingRecord.get();
                  return value == null ? null : value.clone();
                }
                if ((AccountGameplayDelegationAuthorityProjection.ISSUER_KEY_PREFIX
                        + GameSessionAccountDelegationProfile.ISSUER)
                    .equals(key)) return authorityProjectionBytes[0].clone();
                if ((AccountGameplayDelegationAuthorityProjection.ACCOUNT_KEY_PREFIX + accountId)
                    .equals(key)) return authorityProjectionBytes[1].clone();
                return null;
              });
      when(commands.pexpiretime(any(byte[].class)))
          .thenAnswer(invocation -> absoluteExpiryMillis.get());
      when(commands.pttl(any(byte[].class))).thenReturn(-1L);
      doAnswer(invocation -> List.of(1L, 1L))
          .when(commands)
          .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
      AccountCoordinationPinnedConnectionProvider provider = () -> connection;
      client =
          new AccountGameplayDelegationRedisClient(
              provider,
              RedisScriptCatalog.loadInstalled(resourceClassLoader),
              new AcknowledgementRequirements(1, 1, 1_000),
              Clock.systemUTC(),
              GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
              resourceClassLoader);
    }

    AccountGameplayDelegationRedisClient client() {
      return client;
    }
  }

  private static String sha1(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
  }

  private static CanonicalJoinScopeV2 canonicalJoinScope(UUID accountId, UUID tenantId) {
    return new CanonicalJoinScopeV2(
        "current-source-scope-" + UUID.randomUUID(),
        accountId,
        tenantId,
        UUID.randomUUID(),
        "tenant",
        "world",
        "production",
        UUID.randomUUID(),
        "SHARED",
        UUID.randomUUID(),
        7L,
        3L,
        "2099-01-01T00:00:00Z",
        "2099-01-01T00:02:00Z");
  }

  private static DemoTenantEntitlementRequest entitlementRequest(
      UUID tenantUuid, FreshTenantCreationEvidence tenantEvidence) {
    return new DemoTenantEntitlementRequest(
        UUID.randomUUID(),
        tenantUuid,
        tenantEvidence.creationRequestId(),
        tenantEvidence.requestDigest(),
        null,
        null,
        null,
        true,
        true,
        true,
        true,
        new DemoTenantEntitlementRequest.Quotas(3L, 2L, 4096L));
  }
}
