package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Context;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.client.AccountOriginalNewFinalAdmissionReadClient;
import net.firedevops.firemud.accountservice.client.AccountOriginalNewFinalAdmissionReadClient.AuthenticatedFinalAdmissionReadback;
import net.firedevops.firemud.accountservice.client.AccountOriginalNewFinalAdmissionReadClient.ExpectedOriginalAdmission;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.SourceKind;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.TokenIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.RecoveredCredential;
import net.firedevops.firemud.accountservice.repository.AccountOriginalNewBoundGameplayIssuanceRepository.Genesis;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.ActiveRegistryReadback;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationTokenRegistry;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeKeyring;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.OriginalNewCredential;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Actual Account ORIGINAL_NEW owner proof over the existing current-source PostgreSQL fixture and
 * its primary/replica Redis fixture.
 *
 * <p>The first LOGIN credential, current Account sources, original NEW lease COMMIT and durable ACK
 * receipt, Account ORIGINAL_NEW signer operation, encrypted result recovery, token identity fence,
 * and Redis pending/active transition use their existing owners. The signer fixture retains its
 * controlled signer-custody/materializer inputs. The explicitly controlled Game Session final-read
 * callback supplies schema-v2 COMPLETE results and exercises Account's activation and durable
 * confirmation owner; it is not evidence of a real Game Session final CAS or protected final-result
 * reader.
 */
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountOriginalNewBoundGameplayCredentialOwnerIntegrationTest {
  private static final String NAMESPACE = "test";
  private static final String GAME_SESSION_WORKLOAD =
      "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String CONFIRMATION_FAULT_FUNCTION =
      "account_original_new_test_fail_confirmation_update";
  private static final String CONFIRMATION_FAULT_TRIGGER =
      "account_original_new_test_confirmation_update_failure";
  private static final long SESSION_DURATION_MILLIS = 120_000L;
  private static final long REDIS_CLEANUP_MARGIN_MILLIS = 30_000L;
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temporaryDirectory;
  private AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest fixtureHarness;
  private AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.Fixture sourceFixture;
  private AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.PhysicalRedisFixture
      redisFixture;

  @BeforeAll
  static void startPostgres() {
    AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.startPostgres();
  }

  @AfterAll
  static void stopPostgres() {
    AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.stopPostgres();
  }

  @BeforeEach
  void createActualSourceFixture() throws Exception {
    fixtureHarness = new AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest();
    fixtureHarness.temporaryDirectory = temporaryDirectory;
    redisFixture =
        AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.PhysicalRedisFixture.start();
    sourceFixture = fixtureHarness.newFixture(redisFixture.client());
  }

  @AfterEach
  void cleanOwnedFixture() {
    if (redisFixture != null) redisFixture.close();
    if (fixtureHarness != null) fixtureHarness.dropRunOwnedSchemas();
  }

  @Test
  void lostSqlConfirmationRecoversOnlyAfterFreshExactControlledFinalReadback() throws Exception {
    DSLContext dsl =
        DSL.using(
            new TransactionAwareDataSourceProxy(sourceFixture.dataSource), SQLDialect.POSTGRES);
    DataSourceTransactionManager transactionManager =
        new DataSourceTransactionManager(sourceFixture.dataSource);
    TransactionTemplate serializable = new TransactionTemplate(transactionManager);
    serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    var initialIdentity = sourceFixture.committedCandidate.identity();
    CallerIdentity recoveryCaller =
        new CallerIdentity(initialIdentity.callerWorkload(), initialIdentity.callerContextId());
    RecoveredCredential recoveredLogin =
        sourceFixture.responseRecoveryOwner.recoverInitialLoginResponse(
            initialIdentity.requestId(), initialIdentity.accountId(), recoveryCaller);
    byte[] loginJwtBytes = recoveredLogin.compactJwtBytes();
    String loginJwt = new String(loginJwtBytes, StandardCharsets.US_ASCII);
    Arrays.fill(loginJwtBytes, (byte) 0);

    var authorityGenerations = new AccountAuthorityGenerationRepository(dsl);
    var authorityOutbox = new AccountAuthorityOutboxRepository(dsl);
    var sourceEvidence =
        new AccountAuthoritySourceEvidenceRepository(dsl, authorityGenerations, authorityOutbox);
    var authEvidenceBundles =
        new AccountAuthEvidenceBundleRepository(dsl, authorityGenerations, authorityOutbox);
    var issuanceRepository =
        new AccountGameplayDelegationIssuanceRepository(dsl, sourceEvidence, authEvidenceBundles);
    var tokenRegistry =
        new AccountGameplayDelegationTokenRegistry(
            issuanceRepository,
            transactionManager,
            redisFixture.client(),
            Clock.systemUTC(),
            GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
            REDIS_CLEANUP_MARGIN_MILLIS);
    AccountResponseEnvelopeCryptography responseCryptography =
        testResponseCryptography(temporaryDirectory.resolve("original-new-response-keyring"));
    AtomicInteger finalReadCalls = new AtomicInteger();
    AtomicReference<ExpectedOriginalAdmission> expectedAdmission = new AtomicReference<>();
    AtomicReference<FinalReadMode> finalReadMode = new AtomicReference<>(FinalReadMode.EXACT);
    List<ExpectedOriginalAdmission> finalReadExpectations = new ArrayList<>();
    List<FinalReadMode> observedFinalReadModes = new ArrayList<>();
    List<AuthenticatedFinalAdmissionReadback> finalReadResults = new ArrayList<>();
    long controlledAdmissionAtMillis = System.currentTimeMillis() - 100L;
    AccountOriginalNewFinalAdmissionReadClient controlledFinalReadback =
        expected -> {
          finalReadCalls.incrementAndGet();
          expectedAdmission.set(expected);
          finalReadExpectations.add(expected);
          FinalReadMode mode = finalReadMode.get();
          observedFinalReadModes.add(mode);
          if (mode == FinalReadMode.UNAVAILABLE) {
            throw new IllegalStateException("controlled Game Session final read unavailable");
          }
          AuthenticatedFinalAdmissionReadback readback =
              mock(AuthenticatedFinalAdmissionReadback.class);
          when(readback.sessionSchemaVersion()).thenReturn(2);
          when(readback.admissionState()).thenReturn("COMPLETE");
          when(readback.anchors())
              .thenReturn(mode == FinalReadMode.CHANGED ? changedTokenHash(expected) : expected);
          when(readback.admissionAtMillis()).thenReturn(controlledAdmissionAtMillis);
          when(readback.continuityExpiresAtMillis())
              .thenReturn(controlledAdmissionAtMillis + SESSION_DURATION_MILLIS);
          when(readback.canonicalSessionDurationMillis()).thenReturn(SESSION_DURATION_MILLIS);
          when(readback.canonicalReadbackSha256()).thenReturn("f".repeat(64));
          finalReadResults.add(readback);
          return readback;
        };
    var owner =
        new AccountOriginalNewBoundGameplayCredentialOwner(
            sourceFixture.dataSource,
            NAMESPACE,
            new AccountGameplayAdmissionCallerVerifier(
                sourceFixture.signerFixture.trustedJwksSource(),
                sourceFixture.committedIssuanceOwner,
                Clock.systemUTC()),
            sourceFixture.signerFixture.signer(),
            tokenRegistry,
            responseCryptography,
            Clock.systemUTC(),
            controlledFinalReadback);
    GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(GAME_SESSION_WORKLOAD).orElseThrow();
    AccountGameplayAdmissionLeaseEvidence originalLease =
        commitActualOriginalNewLease(dsl, serializable);
    UUID originalRequestId = UUID.fromString((String) originalLease.carrier().get("requestId"));
    UUID originalDecisionId =
        dsl.fetchSingle(
                "SELECT binding_decision_id FROM account_gameplay_admission_lease_operations "
                    + "WHERE request_id = ?",
                originalRequestId)
            .get(0, UUID.class);
    if (originalDecisionId == null) throw new IllegalStateException("Original decision is absent");
    String originalEvidenceSha256 = originalLease.sha256();

    OriginalNewCredential first =
        Context.current()
            .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
            .call(() -> owner.issue(loginJwt, originalLease));
    OriginalNewCredential lostReplyRetry =
        Context.current()
            .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
            .call(() -> owner.issue(loginJwt, originalLease));
    try (first;
        lostReplyRetry) {
      Genesis committed = readGenesis(dsl, serializable, originalLease);
      byte[] firstHandle = first.opaqueRebindHandle();
      byte[] retryHandle = lostReplyRetry.opaqueRebindHandle();
      byte[] pendingRecord = committed.pendingRegistryRecordBytes();
      byte[] encryptedResult = committed.encryptedResultEnvelopeBytes();
      try {
        assertThat(committed.status())
            .isEqualTo(AccountOriginalNewBoundGameplayIssuanceRepository.Status.COMMITTED);
        assertThat(committed.tokenGeneration()).isEqualTo(1L);
        assertThat(committed.originalLeaseEvidenceSha256()).isEqualTo(originalEvidenceSha256);
        assertThat(committed.bindingDecisionId()).isEqualTo(originalDecisionId);
        assertThat(committed.issuanceFence())
            .isEqualTo(Long.parseLong((String) originalLease.carrier().get("issuanceFence")));
        assertExactLeaseSource(committed, originalLease, "authorityTuple");
        assertExactLeaseSource(committed, originalLease, "membershipBaseline");
        assertExactLeaseSource(committed, originalLease, "outboxCheckpoints");
        assertThat(committed.lineageId()).isEqualTo(first.lineageId());
        assertThat(committed.tokenJti()).isEqualTo(first.tokenJti());
        assertThat(first.lineageId()).isNotEqualTo(first.tokenJti());
        assertThat(first.tokenJti()).isNotEqualTo(initialIdentity.tokenJti());
        assertThat(first.originalLeaseId())
            .isEqualTo(UUID.fromString((String) originalLease.carrier().get("leaseId")));
        assertThat(first.originalLeaseFence())
            .isEqualTo(originalLease.leaseFence().longValueExact());
        assertThat(first.issuanceFence()).isEqualTo(committed.issuanceFence());
        assertThat(first.tokenSha256()).isEqualTo(committed.tokenHash());
        assertThat(first.checkpointsSha256())
            .isEqualTo(sha256AndWipe(committed.outboxCheckpointsCanonicalBytes()));
        assertThat(sha256(firstHandle)).isEqualTo(committed.rebindHandleSha256());
        assertThat(MessageDigest.isEqual(firstHandle, retryHandle)).isTrue();
        assertThat(lostReplyRetry.tokenSha256()).isEqualTo(first.tokenSha256());
        assertThat(lostReplyRetry.lineageId()).isEqualTo(first.lineageId());
        assertThat(lostReplyRetry.tokenJti()).isEqualTo(first.tokenJti());
        assertThat(lostReplyRetry.issuanceFence()).isEqualTo(first.issuanceFence());
        assertThat(lostReplyRetry.originalAckReceiptXid()).isEqualTo(first.originalAckReceiptXid());
        assertThat(lostReplyRetry.checkpointsSha256()).isEqualTo(first.checkpointsSha256());
        assertThat(committed.confirmedAdmissionAtMillis()).isNull();
        assertThat(committed.handleConsumedAtMillis()).isNull();
        assertThat(encryptedResult.length > 0).isTrue();
        assertThat(first.toString())
            .isEqualTo("AccountGameplayAdmissionLeaseGrpcCodec.OriginalNewCredential[redacted]");
        redisFixture
            .client()
            .requirePendingRecord(
                committed.tokenHash(),
                pendingRecord,
                Math.addExact(
                    Math.multiplyExact(committed.expiresAtEpochSecond(), 1_000L),
                    REDIS_CLEANUP_MARGIN_MILLIS));
        TokenIdentity registeredIdentity = activeIdentity(dsl, committed);
        assertThat(registeredIdentity.sourceKind()).isEqualTo(SourceKind.ORIGINAL_NEW);
        assertThat(registeredIdentity.operationId()).isEqualTo(committed.lineageId());
        assertThat(registeredIdentity.tokenJti()).isEqualTo(first.tokenJti());
        assertThat(registeredIdentity.tokenSha256()).isEqualTo(first.tokenSha256());
        assertThat(registeredIdentity.tokenGeneration()).isEqualTo(1L);
        assertThat(registeredIdentity.issuanceFence()).isEqualTo(committed.issuanceFence());
        assertThat(dsl.fetchCount(DSL.table("account_original_new_bound_gameplay_issuances")))
            .isOne();
      } finally {
        Arrays.fill(firstHandle, (byte) 0);
        Arrays.fill(retryHandle, (byte) 0);
        Arrays.fill(pendingRecord, (byte) 0);
        Arrays.fill(encryptedResult, (byte) 0);
      }

      long exactRedisExpiryMillis =
          Math.addExact(
              Math.multiplyExact(committed.expiresAtEpochSecond(), 1_000L),
              REDIS_CLEANUP_MARGIN_MILLIS);
      installOwnedConfirmationUpdateFailure(dsl);
      try {
        assertThatThrownBy(
                () ->
                    Context.current()
                        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
                        .call(() -> owner.confirmOriginalFinalAdmission(loginJwt, originalLease)))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("controlled owned original-new confirmation SQL fault");
      } finally {
        removeOwnedConfirmationUpdateFailure(dsl);
      }
      Genesis afterLostConfirmation = assertUnconfirmed(dsl, serializable, originalLease);
      assertThat(afterLostConfirmation.tokenHash()).isEqualTo(first.tokenSha256());
      var activeAfterLostConfirmation =
          tokenRegistry.requireExactOriginalNewActiveRecord(afterLostConfirmation);
      assertThat(activeAfterLostConfirmation.absoluteExpiryMillis())
          .isEqualTo(exactRedisExpiryMillis);
      assertThat(finalReadCalls.get()).isEqualTo(1);

      // Recover the unchanged issuance while Redis is ACTIVE but SQL confirmation is absent.
      // Both API forms must return the same original credential without a second final read.
      String issuanceAfterActivation =
          dsl.fetchSingle(
                  "SELECT xmin::text || ':' || to_jsonb(issuance)::text"
                      + " FROM account_original_new_bound_gameplay_issuances issuance")
              .get(0, String.class);
      var rpcRequest =
          AccountGameplayAdmissionLeaseGrpcCodec.parseIssueOriginalNewCredentialRequest(
              AccountGameplayAdmissionLeaseGrpcCodec.toIssueOriginalNewCredentialRequest(
                  loginJwt, originalLease.canonicalJson().getBytes(StandardCharsets.UTF_8)));
      try (var directRetry =
              Context.current()
                  .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
                  .call(() -> owner.issue(loginJwt, originalLease));
          var rpcRetry =
              AccountGameplayAdmissionLeaseGrpcCodec.parseIssueOriginalNewCredentialResponse(
                  rpcRequest,
                  Context.current()
                      .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
                      .call(() -> owner.issue(rpcRequest)))) {
        for (var retry : List.of(directRetry, rpcRetry)) {
          assertThat(retry.signedBoundGsACompactJwt()).isEqualTo(first.signedBoundGsACompactJwt());
          assertThat(retry.lineageId()).isEqualTo(first.lineageId());
          assertThat(retry.tokenJti()).isEqualTo(first.tokenJti());
          assertThat(retry.tokenSha256()).isEqualTo(first.tokenSha256());
          assertThat(retry.originalAckReceiptXid()).isEqualTo(first.originalAckReceiptXid());
          byte[] expectedHandle = first.opaqueRebindHandle();
          byte[] actualHandle = retry.opaqueRebindHandle();
          try {
            assertThat(MessageDigest.isEqual(expectedHandle, actualHandle)).isTrue();
          } finally {
            Arrays.fill(expectedHandle, (byte) 0);
            Arrays.fill(actualHandle, (byte) 0);
          }
        }
      }
      Genesis afterIssuanceRetries = assertUnconfirmed(dsl, serializable, originalLease);
      assertThat(afterIssuanceRetries.committedRegistryReceiptSha256())
          .isEqualTo(afterLostConfirmation.committedRegistryReceiptSha256());
      assertThat(
              dsl.fetchSingle(
                      "SELECT xmin::text || ':' || to_jsonb(issuance)::text"
                          + " FROM account_original_new_bound_gameplay_issuances issuance")
                  .get(0, String.class))
          .isEqualTo(issuanceAfterActivation);
      assertSameActiveRegistryReadback(
          activeAfterLostConfirmation,
          tokenRegistry.requireExactOriginalNewActiveRecord(afterIssuanceRetries));
      assertThat(finalReadCalls.get()).isEqualTo(1);

      // ACTIVE Redis state cannot substitute for a new authenticated Game Session final read.
      finalReadMode.set(FinalReadMode.CHANGED);
      assertFinalConfirmationUnavailable(owner, peer, loginJwt, originalLease);
      Genesis afterChangedRead = assertUnconfirmed(dsl, serializable, originalLease);
      assertSameActiveRegistryReadback(
          activeAfterLostConfirmation,
          tokenRegistry.requireExactOriginalNewActiveRecord(afterChangedRead));

      finalReadMode.set(FinalReadMode.UNAVAILABLE);
      assertFinalConfirmationUnavailable(owner, peer, loginJwt, originalLease);
      Genesis afterUnavailableRead = assertUnconfirmed(dsl, serializable, originalLease);
      assertSameActiveRegistryReadback(
          activeAfterLostConfirmation,
          tokenRegistry.requireExactOriginalNewActiveRecord(afterUnavailableRead));

      // Recovery makes a fresh read of the same controlled final result and confirms the same
      // frozen lineage, token, anchors, continuity deadline, and physical registry expiry.
      finalReadMode.set(FinalReadMode.EXACT);
      var confirmed = confirm(owner, peer, loginJwt, originalLease);
      assertThat(confirmed.lineageId()).isEqualTo(first.lineageId());
      assertThat(confirmed.originalLeaseId())
          .isEqualTo(UUID.fromString((String) originalLease.carrier().get("leaseId")));
      assertThat(confirmed.admissionAtMillis()).isEqualTo(controlledAdmissionAtMillis);
      assertThat(confirmed.continuityExpiresAtMillis())
          .isEqualTo(controlledAdmissionAtMillis + SESSION_DURATION_MILLIS);
      assertThat(expectedAdmission.get()).isNotNull();
      assertThat(expectedAdmission.get().tokenSha256()).isEqualTo(first.tokenSha256());
      assertThat(expectedAdmission.get().lineageId()).isEqualTo(first.lineageId());
      assertThat(expectedAdmission.get().tokenJti()).isEqualTo(first.tokenJti());
      assertThat(expectedAdmission.get().originalLeaseEvidenceSha256())
          .isEqualTo(originalEvidenceSha256);
      assertThat(expectedAdmission.get().tokenGeneration()).isEqualTo(1L);
      assertThat(expectedAdmission.get().issuanceFence()).isEqualTo(committed.issuanceFence());
      assertThat(expectedAdmission.get().bindingScopeSha256())
          .isEqualTo(committed.bindingScopeSha256());
      assertThat(expectedAdmission.get().membershipBaselineSha256())
          .isEqualTo(sha256AndWipe(committed.membershipBaselineCanonicalBytes()));
      assertThat(expectedAdmission.get().checkpointsSha256())
          .isEqualTo(sha256AndWipe(committed.outboxCheckpointsCanonicalBytes()));
      assertThat(expectedAdmission.get().rebindHandleSha256())
          .isEqualTo(committed.rebindHandleSha256());
      assertThat(finalReadCalls.get()).isEqualTo(4);
      assertThat(observedFinalReadModes)
          .containsExactly(
              FinalReadMode.EXACT,
              FinalReadMode.CHANGED,
              FinalReadMode.UNAVAILABLE,
              FinalReadMode.EXACT);
      assertThat(finalReadExpectations).hasSize(4).containsOnly(expectedAdmission.get());
      assertThat(finalReadResults).hasSize(3);
      assertThat(finalReadResults.get(0).anchors()).isEqualTo(finalReadResults.get(2).anchors());
      assertThat(finalReadResults.get(0).admissionAtMillis())
          .isEqualTo(finalReadResults.get(2).admissionAtMillis());
      assertThat(finalReadResults.get(0).continuityExpiresAtMillis())
          .isEqualTo(finalReadResults.get(2).continuityExpiresAtMillis());
      assertThat(finalReadResults.get(0).canonicalSessionDurationMillis())
          .isEqualTo(finalReadResults.get(2).canonicalSessionDurationMillis());
      assertThat(finalReadResults.get(0).canonicalReadbackSha256())
          .isEqualTo(finalReadResults.get(2).canonicalReadbackSha256());

      Genesis confirmedGenesis = readGenesis(dsl, serializable, originalLease);
      assertThat(confirmedGenesis.lineageId()).isEqualTo(first.lineageId());
      assertThat(confirmedGenesis.tokenJti()).isEqualTo(first.tokenJti());
      assertThat(confirmedGenesis.tokenHash()).isEqualTo(first.tokenSha256());
      assertThat(confirmedGenesis.expiresAtEpochSecond())
          .isEqualTo(committed.expiresAtEpochSecond());
      assertThat(confirmedGenesis.confirmedAdmissionAtMillis())
          .isEqualTo(controlledAdmissionAtMillis);
      assertThat(confirmedGenesis.confirmedContinuityExpiresAtMillis())
          .isEqualTo(controlledAdmissionAtMillis + SESSION_DURATION_MILLIS);
      assertSameActiveRegistryReadback(
          activeAfterLostConfirmation,
          tokenRegistry.requireExactOriginalNewActiveRecord(confirmedGenesis));

      var sameConfirmation = confirm(owner, peer, loginJwt, originalLease);
      assertThat(sameConfirmation.lineageId()).isEqualTo(confirmed.lineageId());
      assertThat(sameConfirmation.authenticatedConfirmationSha256())
          .isEqualTo(confirmed.authenticatedConfirmationSha256());
      assertThat(finalReadCalls.get()).isEqualTo(4);

      OriginalNewCredential committedRetry =
          Context.current()
              .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
              .call(() -> owner.issue(loginJwt, originalLease));
      try (committedRetry) {
        assertThat(committedRetry.tokenSha256()).isEqualTo(first.tokenSha256());
        assertThat(committedRetry.lineageId()).isEqualTo(first.lineageId());
        assertThat(committedRetry.tokenJti()).isEqualTo(first.tokenJti());
        assertThat(committedRetry.issuanceFence()).isEqualTo(first.issuanceFence());
        assertThat(committedRetry.originalAckReceiptXid()).isEqualTo(first.originalAckReceiptXid());
        assertThat(finalReadCalls.get()).isEqualTo(4);
      }
      assertOriginalLeaseAndAckRemainExact(dsl, originalLease, originalEvidenceSha256);
    }
  }

  private AccountGameplayAdmissionLeaseEvidence commitActualOriginalNewLease(
      DSLContext dsl, TransactionTemplate serializable) {
    UUID accountId = sourceFixture.committedCandidate.identity().accountId();
    UUID tenantId =
        dsl.fetchSingle(
                "SELECT membership.tenant_uuid FROM account_tenant_membership membership "
                    + "JOIN accounts account ON account.id = membership.account_id "
                    + "WHERE account.account_uuid = ? AND membership.tenant_uuid IS NOT NULL",
                accountId)
            .get(0, UUID.class);
    if (tenantId == null)
      throw new IllegalStateException("Current-source fixture tenant is absent");

    AccountGameplayAdmissionLeaseEvidence evidence =
        serializable.execute(
            status -> {
              var current =
                  AccountGameplayAdmissionCurrentSources.bind(dsl, NAMESPACE)
                      .readCurrentPublicProductionNew(
                          tenantId,
                          sourceFixture.committedCandidate.identity().tokenJti(),
                          sourceFixture.committedCandidate);
              UUID requestId = UUID.randomUUID();
              UUID leaseId = UUID.randomUUID();
              var leaseRepository = new AccountGameplayAdmissionLeaseRepository(dsl);
              var allocation = leaseRepository.allocate(accountId, requestId, leaseId);
              long evaluatedAt =
                  dsl.fetchSingle(
                          "SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint")
                      .get(0, Long.class);
              long maxLeaseExpiry =
                  Math.addExact(
                      evaluatedAt, AccountGameplayAdmissionLeaseEvidence.MAX_DURATION_MILLIS);
              long initialTokenExpiry =
                  Math.multiplyExact(
                      sourceFixture.committedCandidate.identity().expiresAtEpochSecond(), 1_000L);
              Map<String, Object> carrier = new LinkedHashMap<>();
              carrier.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
              carrier.put("schemaVersion", "1");
              carrier.put("mode", "PUBLIC_PRODUCTION");
              carrier.put("targetNamespace", NAMESPACE);
              carrier.put("callerWorkload", GAME_SESSION_WORKLOAD);
              carrier.put("requestId", allocation.requestId().toString());
              carrier.put("leaseId", allocation.leaseId().toString());
              carrier.put("leaseFence", Long.toString(allocation.leaseFence()));
              carrier.put("leaseKind", "NEW_BINDING");
              carrier.put(
                  "bindingScope",
                  Map.ofEntries(
                      Map.entry("accountId", accountId.toString()),
                      Map.entry("tenantId", tenantId.toString()),
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
                      Map.entry("regionEpoch", "1")));
              carrier.putAll(current.leaseSourceFields());
              carrier.put("evaluatedAt", Long.toString(evaluatedAt));
              carrier.put("expiresAt", Long.toString(Math.min(maxLeaseExpiry, initialTokenExpiry)));
              var originalLease = AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
              leaseRepository.beginPending(originalLease);
              return originalLease;
            });

    UUID decisionId = UUID.randomUUID();
    var acknowledgement =
        new AccountGameplayAdmissionOriginalCommitExecutor(sourceFixture.dataSource)
            .executeWithCurrentSources(
                evidence, decisionId, NAMESPACE, sourceFixture.committedCandidate);
    var receipt =
        new AccountGameplayAdmissionOriginalAckReceiptCommitExecutor(sourceFixture.dataSource)
            .confirm(acknowledgement);
    assertThat(receipt.evidenceSha256()).isEqualTo(evidence.sha256());
    assertThat(receipt.bindingDecisionId()).isEqualTo(decisionId);
    return evidence;
  }

  private static Genesis readGenesis(
      DSLContext dsl,
      TransactionTemplate transaction,
      AccountGameplayAdmissionLeaseEvidence originalLease) {
    return transaction.execute(
        status ->
            new AccountOriginalNewBoundGameplayIssuanceRepository(dsl)
                .readByLease(UUID.fromString((String) originalLease.carrier().get("leaseId"))));
  }

  private static AccountOriginalNewBoundGameplayCredentialOwner.OriginalAdmissionConfirmation
      confirm(
          AccountOriginalNewBoundGameplayCredentialOwner owner,
          GrpcPeerIdentity peer,
          String loginJwt,
          AccountGameplayAdmissionLeaseEvidence originalLease)
          throws Exception {
    return Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .call(() -> owner.confirmOriginalFinalAdmission(loginJwt, originalLease));
  }

  private static void assertFinalConfirmationUnavailable(
      AccountOriginalNewBoundGameplayCredentialOwner owner,
      GrpcPeerIdentity peer,
      String loginJwt,
      AccountGameplayAdmissionLeaseEvidence originalLease)
      throws Exception {
    assertThatThrownBy(() -> confirm(owner, peer, loginJwt, originalLease))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Original-new bound gameplay credential is unavailable");
  }

  private static Genesis assertUnconfirmed(
      DSLContext dsl,
      TransactionTemplate transaction,
      AccountGameplayAdmissionLeaseEvidence originalLease) {
    Genesis unconfirmed = readGenesis(dsl, transaction, originalLease);
    assertThat(unconfirmed.confirmedAdmissionAtMillis()).isNull();
    assertThat(unconfirmed.confirmedContinuityExpiresAtMillis()).isNull();
    assertThat(unconfirmed.confirmationReadbackSha256()).isNull();
    return unconfirmed;
  }

  private static void assertSameActiveRegistryReadback(
      ActiveRegistryReadback expected, ActiveRegistryReadback actual) {
    assertThat(actual.tokenHash()).isEqualTo(expected.tokenHash());
    assertThat(actual.accountId()).isEqualTo(expected.accountId());
    assertThat(actual.jti()).isEqualTo(expected.jti());
    assertThat(actual.tokenExpiryEpochSecond()).isEqualTo(expected.tokenExpiryEpochSecond());
    assertThat(actual.absoluteExpiryMillis()).isEqualTo(expected.absoluteExpiryMillis());
    assertThat(actual.canonicalPendingRecordSha256())
        .isEqualTo(expected.canonicalPendingRecordSha256());
  }

  private static ExpectedOriginalAdmission changedTokenHash(ExpectedOriginalAdmission expected) {
    String changedTokenHash =
        (expected.tokenSha256().startsWith("0") ? "1" : "0") + expected.tokenSha256().substring(1);
    return new ExpectedOriginalAdmission(
        expected.requestId(),
        expected.accountId(),
        expected.tenantId(),
        expected.sessionId(),
        expected.bindingDecisionId(),
        expected.originalLeaseId(),
        expected.originalLeaseFence(),
        expected.originalLeaseEvidenceSha256(),
        expected.lineageId(),
        expected.tokenJti(),
        changedTokenHash,
        expected.tokenGeneration(),
        expected.issuanceFence(),
        expected.bindingScopeSha256(),
        expected.membershipBaselineSha256(),
        expected.checkpointsSha256(),
        expected.rebindHandleSha256());
  }

  private static void installOwnedConfirmationUpdateFailure(DSLContext dsl) {
    dsl.execute(
        "CREATE FUNCTION "
            + CONFIRMATION_FAULT_FUNCTION
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
            + "RAISE EXCEPTION 'controlled owned original-new confirmation SQL fault'; "
            + "RETURN NEW; END; $$");
    dsl.execute(
        "CREATE TRIGGER "
            + CONFIRMATION_FAULT_TRIGGER
            + " BEFORE UPDATE ON account_original_new_bound_gameplay_issuances "
            + "FOR EACH ROW WHEN (OLD.confirmed_admission_at_ms IS NULL "
            + "AND NEW.confirmed_admission_at_ms IS NOT NULL) EXECUTE FUNCTION "
            + CONFIRMATION_FAULT_FUNCTION
            + "()");
  }

  private static void removeOwnedConfirmationUpdateFailure(DSLContext dsl) {
    dsl.execute(
        "DROP TRIGGER "
            + CONFIRMATION_FAULT_TRIGGER
            + " ON account_original_new_bound_gameplay_issuances");
    dsl.execute("DROP FUNCTION " + CONFIRMATION_FAULT_FUNCTION + "()");
  }

  private static TokenIdentity activeIdentity(DSLContext dsl, Genesis genesis) {
    TokenIdentity exact =
        new TokenIdentity(
            genesis.accountId(),
            genesis.lineageId(),
            genesis.requestId(),
            genesis.tokenHash(),
            genesis.tokenJti(),
            genesis.notBeforeEpochSecond(),
            genesis.tokenGeneration(),
            genesis.issuanceFence(),
            SourceKind.ORIGINAL_NEW);
    return new AccountGameplayTokenIdentityFenceRepository(dsl)
        .requireActiveReadback(exact)
        .identity();
  }

  private static void assertExactLeaseSource(
      Genesis genesis, AccountGameplayAdmissionLeaseEvidence lease, String field) throws Exception {
    byte[] actual =
        switch (field) {
          case "authorityTuple" -> genesis.authorityTupleCanonicalBytes();
          case "membershipBaseline" -> genesis.membershipBaselineCanonicalBytes();
          case "outboxCheckpoints" -> genesis.outboxCheckpointsCanonicalBytes();
          default -> throw new IllegalArgumentException("Unsupported original lease source field");
        };
    byte[] expected = canonicalBytes(lease.carrier().get(field));
    try {
      assertThat(actual).containsExactly(expected);
    } finally {
      Arrays.fill(actual, (byte) 0);
      Arrays.fill(expected, (byte) 0);
    }
  }

  private static void assertOriginalLeaseAndAckRemainExact(
      DSLContext dsl,
      AccountGameplayAdmissionLeaseEvidence originalLease,
      String originalEvidenceSha256) {
    UUID requestId = UUID.fromString((String) originalLease.carrier().get("requestId"));
    var operation =
        dsl.fetchSingle(
            "SELECT evidence_sha256, evidence_json, status "
                + "FROM account_gameplay_admission_lease_operations WHERE request_id = ?",
            requestId);
    assertThat(operation.get("evidence_sha256", String.class)).isEqualTo(originalEvidenceSha256);
    assertThat(operation.get("evidence_json", String.class))
        .isEqualTo(originalLease.canonicalJson());
    assertThat(operation.get("status", String.class)).isEqualTo("COMMITTED");
    var receipt =
        dsl.fetchSingle(
            "SELECT evidence_sha256, lease_id, lease_fence FROM "
                + "account_gameplay_admission_original_commit_ack_receipts WHERE request_id = ?",
            requestId);
    assertThat(receipt.get("evidence_sha256", String.class)).isEqualTo(originalEvidenceSha256);
    assertThat(receipt.get("lease_id", UUID.class))
        .isEqualTo(UUID.fromString((String) originalLease.carrier().get("leaseId")));
    assertThat(receipt.get("lease_fence", Long.class))
        .isEqualTo(originalLease.leaseFence().longValueExact());
  }

  private static AccountResponseEnvelopeCryptography testResponseCryptography(Path keyringRoot)
      throws Exception {
    Files.createDirectories(keyringRoot);
    byte[] key = new byte[32];
    for (int index = 0; index < key.length; index++) key[index] = (byte) (index + 1);
    try {
      String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
      Files.writeString(
          keyringRoot.resolve("keyring"),
          "firemud-account-response-envelope-keyring-v1\nactive integration-key " + encoded + "\n",
          StandardCharsets.US_ASCII);
      return new AccountResponseEnvelopeCryptography(
          new AccountResponseEnvelopeKeyring(keyringRoot.toString()));
    } finally {
      Arrays.fill(key, (byte) 0);
    }
  }

  private static String sha256(byte[] bytes) throws Exception {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static byte[] canonicalBytes(Object value) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static String sha256AndWipe(byte[] bytes) throws Exception {
    try {
      return sha256(bytes);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  private enum FinalReadMode {
    EXACT,
    CHANGED,
    UNAVAILABLE
  }
}
