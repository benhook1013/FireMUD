package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.client.StartSessionReservationEvidenceClient;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.IssuanceRecord;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.Status;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService.Current;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiIssuanceRepository.OwnerLinearization;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService.ReadRedeemedOperationProjectionRequest;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService.RedeemedOperationProjection;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceResponse;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Focused flow proof; JWT validation/source locking remain owned by the real actor service. */
class AccountStartSessionOperatorAuthorizationServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-08T11:00:00Z");
  private static final String LOGGING_PEER = "spiffe://firemud/ns/test/sa/logging-admin-service";
  private static final String GAME_SESSION_PEER =
      "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String GAME_DESIGN_PEER = "spiffe://firemud/ns/test/sa/game-design-service";

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void issuePerformsCryptoOutsideSqlAndRejectsSourceChangeBeforeDurableInsert() {
    UUID actorId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID requestOwnerId = UUID.randomUUID();
    UUID issuerOperationId = UUID.randomUUID();
    UUID tokenJti = UUID.randomUUID();
    StartSessionPreAuthorizationReservationTuple tuple = tuple(actorId, tenantId);
    AccountControlUiAuthority.Snapshot originalSource =
        source(actorId, tenantId, 7L, new byte[] {1});
    AccountControlUiAuthority.Snapshot changedSource =
        source(actorId, tenantId, 8L, new byte[] {2});
    AccountControlUiIssuanceRepository.Stored stored =
        stored(actorId, tenantId, tokenJti, issuerOperationId, originalSource);
    Current original = new Current(stored, originalSource);
    Current changed = new Current(stored, changedSource);

    var actors = org.mockito.Mockito.mock(AccountControlUiActorService.class);
    var issuanceOperations = org.mockito.Mockito.mock(AccountControlUiIssuanceRepository.class);
    var claims = org.mockito.Mockito.mock(StartSessionReservationEvidenceClient.class);
    var repository =
        org.mockito.Mockito.mock(AccountStartSessionOperatorAuthorizationRepository.class);
    var fingerprintKeys =
        org.mockito.Mockito.mock(AccountOperatorAuthorizationFingerprintKeyring.class);
    var responseCrypto = org.mockito.Mockito.mock(AccountResponseEnvelopeCryptography.class);
    var hostedTerms = org.mockito.Mockito.mock(AccountHostedTermsService.class);
    var transactions = org.mockito.Mockito.mock(PlatformTransactionManager.class);
    var environment =
        org.mockito.Mockito.mock(AccountHostedTermsService.CapturedEnvironmentBoundary.class);
    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(new SimpleTransactionStatus());
    when(hostedTerms.captureCurrentEnvironmentBoundary()).thenReturn(environment);
    when(repository.findByControlPlaneRequestId(tuple.controlPlaneRequestId()))
        .thenReturn(Optional.empty());
    doAnswer(
            ignored -> {
              org.assertj.core.api.Assertions.assertThat(
                      TransactionSynchronizationManager.isActualTransactionActive())
                  .isTrue();
              return new OwnerLinearization("123456", 99L);
            })
        .when(issuanceOperations)
        .captureOwnerLinearization();
    when(claims.readCurrentClaimEvidence(any(), any(), anyLong(), any(), anyLong(), any()))
        .thenAnswer(
            ignored -> {
              org.assertj.core.api.Assertions.assertThat(
                      TransactionSynchronizationManager.isActualTransactionActive())
                  .isFalse();
              return claimEvidence(tuple, requestOwnerId);
            });
    doAnswer(
            invocation -> {
              org.assertj.core.api.Assertions.assertThat(
                      TransactionSynchronizationManager.isActualTransactionActive())
                  .isFalse();
              return "arfp/v1/test-key/" + "a".repeat(64);
            })
        .when(fingerprintKeys)
        .fingerprintForIssue(any(), any());
    when(responseCrypto.encrypt(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              org.assertj.core.api.Assertions.assertThat(
                      TransactionSynchronizationManager.isActualTransactionActive())
                  .isFalse();
              return new AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope(
                  new byte[] {3, 4, 5});
            });

    AtomicInteger ownerPass = new AtomicInteger();
    doAnswer(
            invocation -> {
              int pass = ownerPass.getAndIncrement();
              Current current = pass == 0 ? original : changed;
              TransactionSynchronizationManager.setActualTransactionActive(true);
              try {
                @SuppressWarnings("unchecked")
                Function<Current, Object> action = invocation.getArgument(3);
                return action.apply(current);
              } finally {
                TransactionSynchronizationManager.clear();
              }
            })
        .when(actors)
        .withCurrent(anyString(), any(), any(), any());

    var service =
        new AccountStartSessionOperatorAuthorizationService(
            actors,
            issuanceOperations,
            claims,
            repository,
            fingerprintKeys,
            responseCrypto,
            hostedTerms,
            transactions,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new java.security.SecureRandom(),
            LOGGING_PEER,
            GAME_SESSION_PEER,
            java.time.Duration.ofSeconds(60),
            java.time.Duration.ofSeconds(20));
    var request =
        new AccountStartSessionOperatorAuthorizationService.IssueRequest(
            "test-only-forwarded-control-ui-credential",
            tuple.canonicalJson().getBytes(StandardCharsets.UTF_8),
            requestOwnerId,
            10L,
            requestOwnerId,
            10L);

    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(LOGGING_PEER).orElseThrow())
        .run(
            () ->
                assertThatThrownBy(() -> service.issue(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("source changed"));

    verify(repository, never()).createOrReadExact(any());
  }

  @Test
  void exactRepeatedReadReturnsOriginalRedeemedProjectionAfterCurrentnessRevalidation() {
    ReadHarness harness = readHarness(NOW.plusSeconds(30));
    allowCurrent(harness, harness.source());
    AtomicReference<RedeemedOperationProjection> first = new AtomicReference<>();
    AtomicReference<RedeemedOperationProjection> retry = new AtomicReference<>();

    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(GAME_DESIGN_PEER).orElseThrow())
        .run(
            () -> {
              first.set(harness.service().readRedeemedOperationProjection(harness.request()));
              retry.set(harness.service().readRedeemedOperationProjection(harness.request()));
            });

    RedeemedOperationProjection original = first.get();
    RedeemedOperationProjection exactRetry = retry.get();
    assertThat(original.controlPlaneRequestId()).isEqualTo(harness.tuple().controlPlaneRequestId());
    assertThat(original.canonicalPreAuthorizationTuple())
        .isEqualTo(harness.record().preAuthorizationTuple());
    assertThat(original.mutationDigest()).isEqualTo(harness.record().mutationDigest());
    assertThat(original.authorizationReferenceFingerprint())
        .isEqualTo(harness.record().authorizationReferenceFingerprint());
    assertThat(original.reservationOwnerId()).isEqualTo(harness.record().reservationOwnerId());
    assertThat(original.reservationClaimFence())
        .isEqualTo(harness.record().reservationClaimFence());
    assertThat(original.redeemerWorkloadUri()).isEqualTo(GAME_SESSION_PEER);
    assertThat(original.ownerAttemptId()).isEqualTo(harness.record().redemptionOwnerAttemptId());
    assertThat(original.ownerFence()).isEqualTo(harness.record().redemptionOwnerFence());
    assertThat(original.referenceExpiresAt()).isEqualTo(harness.record().referenceExpiresAt());
    assertThat(original.redeemedAt()).isEqualTo(harness.record().redeemedAt());
    assertThat(original.issuanceOperationId()).isEqualTo(harness.record().issuanceOperationId());
    assertThat(original.issuanceFence()).isEqualTo(harness.record().issuanceFence());
    assertThat(original.bundleReference())
        .isEqualTo(
            AccountStartSessionOperatorAuthorityBundle.fromSharedReference(
                harness.record().bundleReference()));
    assertThat(original.authorityEvidenceBundle())
        .isEqualTo(harness.record().authorityEvidenceBundle());
    assertThat(exactRetry.controlPlaneRequestId()).isEqualTo(original.controlPlaneRequestId());
    assertThat(exactRetry.canonicalPreAuthorizationTuple())
        .isEqualTo(original.canonicalPreAuthorizationTuple());
    assertThat(exactRetry.authorityEvidenceBundle()).isEqualTo(original.authorityEvidenceBundle());

    byte[] mutatedCopy = original.authorityEvidenceBundle();
    mutatedCopy[0] ^= 0x01;
    assertThat(original.authorityEvidenceBundle())
        .isEqualTo(harness.record().authorityEvidenceBundle());
    assertThat(original.toString()).doesNotContain("operatorAuthorizationReference");
    assertThat(original.toString()).doesNotContain("controlUiJwt");
    verify(harness.actors(), times(2))
        .withCurrentCommitted(
            eq(harness.actorId()),
            eq(harness.tenantId()),
            eq(harness.tokenJti()),
            eq(harness.environment()),
            any());
    verify(harness.repository(), times(4))
        .findByControlPlaneRequestId(harness.tuple().controlPlaneRequestId());
    verify(harness.repository(), never()).createOrReadExact(any());
    verify(harness.repository(), never()).redeemExact(any());
    verifyNoInteractions(harness.claims(), harness.fingerprintKeys(), harness.responseCrypto());
  }

  @Test
  void projectionRequiresExactSameNamespaceGameDesignPeerBeforeTupleParsingOrLookup() {
    ReadHarness harness = readHarness(NOW.plusSeconds(30));
    ReadRedeemedOperationProjectionRequest malformed =
        new ReadRedeemedOperationProjectionRequest(
            "not canonical tuple".getBytes(StandardCharsets.UTF_8),
            harness.request().authorizationReferenceFingerprint(),
            harness.request().reservationOwnerId(),
            harness.request().reservationClaimFence(),
            harness.request().ownerAttemptId(),
            harness.request().ownerFence());

    for (String wrongPeerUri :
        List.of(
            "spiffe://firemud/ns/other/sa/game-design-service", GAME_SESSION_PEER, LOGGING_PEER)) {
      Context.current()
          .withValue(
              GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(wrongPeerUri).orElseThrow())
          .run(
              () ->
                  assertThatThrownBy(
                          () -> harness.service().readRedeemedOperationProjection(malformed))
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessageContaining("Exact authenticated internal workload identity"));
    }

    verify(harness.repository(), never()).findByControlPlaneRequestId(anyString());
    verify(harness.actors(), never()).withCurrentCommitted(any(), any(), any(), any(), any());
    verify(harness.hostedTerms(), never()).captureCurrentEnvironmentBoundary();
  }

  @Test
  void projectionRejectsMissingUnredeemedAndWrongRedeemerRecords() {
    Context gameDesignContext = gameDesignContext();

    ReadHarness missingHarness = readHarness(NOW.plusSeconds(30));
    when(missingHarness
            .repository()
            .findByControlPlaneRequestId(missingHarness.tuple().controlPlaneRequestId()))
        .thenReturn(Optional.empty());
    gameDesignContext.run(
        () ->
            assertThatThrownBy(
                    () ->
                        missingHarness
                            .service()
                            .readRedeemedOperationProjection(missingHarness.request()))
                .isInstanceOf(
                    AccountStartSessionOperatorAuthorizationService.NotFoundException.class));
    verify(missingHarness.actors(), never())
        .withCurrentCommitted(any(), any(), any(), any(), any());

    ReadHarness issuedHarness = readHarness(NOW.plusSeconds(30));
    IssuanceRecord issued = asIssued(issuedHarness.record());
    when(issuedHarness
            .repository()
            .findByControlPlaneRequestId(issuedHarness.tuple().controlPlaneRequestId()))
        .thenReturn(Optional.of(issued));
    gameDesignContext.run(
        () ->
            assertThatThrownBy(
                    () ->
                        issuedHarness
                            .service()
                            .readRedeemedOperationProjection(issuedHarness.request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stored redeemed operation"));
    verify(issuedHarness.actors(), never()).withCurrentCommitted(any(), any(), any(), any(), any());

    ReadHarness wrongRedeemerHarness = readHarness(NOW.plusSeconds(30));
    IssuanceRecord wrongRedeemer =
        withRedeemer(
            wrongRedeemerHarness.record(), "spiffe://firemud/ns/other/sa/game-session-service");
    when(wrongRedeemerHarness
            .repository()
            .findByControlPlaneRequestId(wrongRedeemerHarness.tuple().controlPlaneRequestId()))
        .thenReturn(Optional.of(wrongRedeemer));
    gameDesignContext.run(
        () ->
            assertThatThrownBy(
                    () ->
                        wrongRedeemerHarness
                            .service()
                            .readRedeemedOperationProjection(wrongRedeemerHarness.request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stored redeemed operation"));
    verify(wrongRedeemerHarness.actors(), never())
        .withCurrentCommitted(any(), any(), any(), any(), any());
  }

  @Test
  void projectionRejectsTupleOwnerAttemptFenceAndFingerprintSubstitutionBeforeCurrentActorLookup() {
    ReadHarness harness = readHarness(NOW.plusSeconds(30));
    ReadRedeemedOperationProjectionRequest original = harness.request();
    UUID substitutedActorId = UUID.randomUUID();
    StartSessionOperatorAction originalAction = harness.tuple().action();
    StartSessionOperatorAction substitutedAction =
        new StartSessionOperatorAction(
            originalAction.actionFamilySchemaId(),
            originalAction.actionFamilySchemaVersion(),
            originalAction.scope(),
            new StartSessionOperatorAction.Target(
                originalAction.target().gameTemplateId(), substitutedActorId),
            originalAction.expectedVersion(),
            originalAction.mutation(),
            originalAction.auditReason());
    byte[] substitutedTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
                harness.tuple().controlPlaneRequestId(), substitutedActorId, substitutedAction)
            .canonicalJson()
            .getBytes(StandardCharsets.UTF_8);
    var requests =
        List.of(
            new ReadRedeemedOperationProjectionRequest(
                substitutedTuple,
                original.authorizationReferenceFingerprint(),
                original.reservationOwnerId(),
                original.reservationClaimFence(),
                original.ownerAttemptId(),
                original.ownerFence()),
            new ReadRedeemedOperationProjectionRequest(
                original.canonicalPreAuthorizationTuple(),
                original.authorizationReferenceFingerprint(),
                UUID.randomUUID(),
                original.reservationClaimFence(),
                original.ownerAttemptId(),
                original.ownerFence()),
            new ReadRedeemedOperationProjectionRequest(
                original.canonicalPreAuthorizationTuple(),
                original.authorizationReferenceFingerprint(),
                original.reservationOwnerId(),
                original.reservationClaimFence() + 1,
                original.ownerAttemptId(),
                original.ownerFence()),
            new ReadRedeemedOperationProjectionRequest(
                original.canonicalPreAuthorizationTuple(),
                original.authorizationReferenceFingerprint(),
                original.reservationOwnerId(),
                original.reservationClaimFence(),
                UUID.randomUUID(),
                original.ownerFence()),
            new ReadRedeemedOperationProjectionRequest(
                original.canonicalPreAuthorizationTuple(),
                "arfp/v1/test-key/" + "b".repeat(64),
                original.reservationOwnerId(),
                original.reservationClaimFence(),
                original.ownerAttemptId(),
                original.ownerFence()),
            new ReadRedeemedOperationProjectionRequest(
                original.canonicalPreAuthorizationTuple(),
                original.authorizationReferenceFingerprint(),
                original.reservationOwnerId(),
                original.reservationClaimFence(),
                original.ownerAttemptId(),
                original.ownerFence() + 1));
    Context gameDesignContext = gameDesignContext();

    for (ReadRedeemedOperationProjectionRequest request : requests) {
      gameDesignContext.run(
          () ->
              assertThatThrownBy(() -> harness.service().readRedeemedOperationProjection(request))
                  .isInstanceOf(IllegalStateException.class));
    }

    verify(harness.actors(), never()).withCurrentCommitted(any(), any(), any(), any(), any());

    ReadHarness expiredHarness = readHarness(NOW.minusSeconds(10));
    gameDesignContext.run(
        () ->
            assertThatThrownBy(
                    () ->
                        expiredHarness
                            .service()
                            .readRedeemedOperationProjection(expiredHarness.request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stored redeemed operation"));
    verify(expiredHarness.actors(), never())
        .withCurrentCommitted(any(), any(), any(), any(), any());
  }

  @Test
  void projectionRejectsRevokedOrChangedCurrentAccountSource() {
    ReadHarness harness = readHarness(NOW.plusSeconds(30));
    AccountControlUiAuthority.Snapshot changedSource =
        source(harness.actorId(), harness.tenantId(), 8L, new byte[] {2});
    allowCurrent(harness, changedSource);

    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(GAME_DESIGN_PEER).orElseThrow())
        .run(
            () ->
                assertThatThrownBy(
                        () -> harness.service().readRedeemedOperationProjection(harness.request()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Current Account source"));

    verify(harness.actors())
        .withCurrentCommitted(
            eq(harness.actorId()),
            eq(harness.tenantId()),
            eq(harness.tokenJti()),
            eq(harness.environment()),
            any());
    verifyNoInteractions(harness.claims(), harness.fingerprintKeys(), harness.responseCrypto());
  }

  private static ReadHarness readHarness(Instant referenceExpiresAt) {
    UUID actorId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID reservationOwnerId = UUID.randomUUID();
    UUID ownerAttemptId = UUID.randomUUID();
    UUID issuanceOperationId = UUID.randomUUID();
    UUID tokenJti = UUID.randomUUID();
    StartSessionPreAuthorizationReservationTuple tuple = tuple(actorId, tenantId);
    AccountControlUiAuthority.Snapshot source = source(actorId, tenantId, 7L, new byte[] {1});
    AccountControlUiIssuanceRepository.Stored stored =
        stored(actorId, tenantId, tokenJti, issuanceOperationId, source);
    OwnerLinearization linearization = new OwnerLinearization("123456", 99L);
    Instant issuedAt = referenceExpiresAt.minusSeconds(50);
    Instant redeemedAt =
        referenceExpiresAt.isAfter(NOW) ? NOW.minusSeconds(1) : referenceExpiresAt.minusSeconds(1);
    var bundle =
        AccountStartSessionOperatorAuthorityBundle.create(
            tuple,
            source,
            stored,
            issuanceOperationId,
            linearization,
            issuedAt,
            referenceExpiresAt);
    var bundleReference = bundle.referenceForSource(source, linearization);
    String fingerprint = "arfp/v1/test-key/" + "a".repeat(64);
    IssuanceRecord record =
        new IssuanceRecord(
            tuple.controlPlaneRequestId(),
            tuple.canonicalJson().getBytes(StandardCharsets.UTF_8),
            tuple.mutationDigest(),
            LOGGING_PEER,
            reservationOwnerId,
            10L,
            issuanceOperationId,
            linearization.sourceFence(),
            AccountStartSessionOperatorAuthorityBundle.toSharedReference(bundleReference),
            bundle.canonicalBytes(),
            fingerprint,
            new byte[] {9, 8, 7},
            issuedAt,
            referenceExpiresAt,
            referenceExpiresAt.plusSeconds(20),
            Status.REDEEMED,
            GAME_SESSION_PEER,
            ownerAttemptId,
            18L,
            redeemedAt,
            fingerprint,
            bundle.canonicalBytes());

    var actors = org.mockito.Mockito.mock(AccountControlUiActorService.class);
    var issuanceOperations = org.mockito.Mockito.mock(AccountControlUiIssuanceRepository.class);
    var claims = org.mockito.Mockito.mock(StartSessionReservationEvidenceClient.class);
    var repository =
        org.mockito.Mockito.mock(AccountStartSessionOperatorAuthorizationRepository.class);
    var fingerprintKeys =
        org.mockito.Mockito.mock(AccountOperatorAuthorizationFingerprintKeyring.class);
    var responseCrypto = org.mockito.Mockito.mock(AccountResponseEnvelopeCryptography.class);
    var hostedTerms = org.mockito.Mockito.mock(AccountHostedTermsService.class);
    var transactions = org.mockito.Mockito.mock(PlatformTransactionManager.class);
    var environment =
        org.mockito.Mockito.mock(AccountHostedTermsService.CapturedEnvironmentBoundary.class);
    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(ignored -> new SimpleTransactionStatus());
    when(hostedTerms.captureCurrentEnvironmentBoundary()).thenReturn(environment);
    when(repository.findByControlPlaneRequestId(tuple.controlPlaneRequestId()))
        .thenReturn(Optional.of(record));
    var service =
        new AccountStartSessionOperatorAuthorizationService(
            actors,
            issuanceOperations,
            claims,
            repository,
            fingerprintKeys,
            responseCrypto,
            hostedTerms,
            transactions,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new java.security.SecureRandom(),
            LOGGING_PEER,
            GAME_SESSION_PEER,
            java.time.Duration.ofSeconds(60),
            java.time.Duration.ofSeconds(20));
    ReadRedeemedOperationProjectionRequest request =
        new ReadRedeemedOperationProjectionRequest(
            tuple.canonicalJson().getBytes(StandardCharsets.UTF_8),
            fingerprint,
            reservationOwnerId,
            10L,
            ownerAttemptId,
            18L);
    return new ReadHarness(
        service,
        actors,
        claims,
        repository,
        fingerprintKeys,
        responseCrypto,
        hostedTerms,
        environment,
        tuple,
        source,
        stored,
        record,
        request,
        actorId,
        tenantId,
        tokenJti);
  }

  private static void allowCurrent(
      ReadHarness harness, AccountControlUiAuthority.Snapshot currentSource) {
    doAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(true);
              try {
                @SuppressWarnings("unchecked")
                Function<Current, Object> action = invocation.getArgument(4);
                return action.apply(new Current(harness.stored(), currentSource));
              } finally {
                TransactionSynchronizationManager.clear();
              }
            })
        .when(harness.actors())
        .withCurrentCommitted(any(), any(), any(), any(), any());
  }

  private static IssuanceRecord asIssued(IssuanceRecord record) {
    return new IssuanceRecord(
        record.controlPlaneRequestId(),
        record.preAuthorizationTuple(),
        record.mutationDigest(),
        record.issuanceWorkloadUri(),
        record.reservationOwnerId(),
        record.reservationClaimFence(),
        record.issuanceOperationId(),
        record.issuanceFence(),
        record.bundleReference(),
        record.authorityEvidenceBundle(),
        record.authorizationReferenceFingerprint(),
        record.encryptedResponseEnvelope(),
        record.issuedAt(),
        record.referenceExpiresAt(),
        record.responseEnvelopeExpiresAt(),
        Status.ISSUED,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static IssuanceRecord withRedeemer(IssuanceRecord record, String redeemerWorkloadUri) {
    return new IssuanceRecord(
        record.controlPlaneRequestId(),
        record.preAuthorizationTuple(),
        record.mutationDigest(),
        record.issuanceWorkloadUri(),
        record.reservationOwnerId(),
        record.reservationClaimFence(),
        record.issuanceOperationId(),
        record.issuanceFence(),
        record.bundleReference(),
        record.authorityEvidenceBundle(),
        record.authorizationReferenceFingerprint(),
        record.encryptedResponseEnvelope(),
        record.issuedAt(),
        record.referenceExpiresAt(),
        record.responseEnvelopeExpiresAt(),
        Status.REDEEMED,
        redeemerWorkloadUri,
        record.redemptionOwnerAttemptId(),
        record.redemptionOwnerFence(),
        record.redeemedAt(),
        record.redemptionReferenceFingerprint(),
        record.redemptionAuthorityEvidenceBundle());
  }

  private static Context gameDesignContext() {
    return Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri(GAME_DESIGN_PEER).orElseThrow());
  }

  private record ReadHarness(
      AccountStartSessionOperatorAuthorizationService service,
      AccountControlUiActorService actors,
      StartSessionReservationEvidenceClient claims,
      AccountStartSessionOperatorAuthorizationRepository repository,
      AccountOperatorAuthorizationFingerprintKeyring fingerprintKeys,
      AccountResponseEnvelopeCryptography responseCrypto,
      AccountHostedTermsService hostedTerms,
      AccountHostedTermsService.CapturedEnvironmentBoundary environment,
      StartSessionPreAuthorizationReservationTuple tuple,
      AccountControlUiAuthority.Snapshot source,
      AccountControlUiIssuanceRepository.Stored stored,
      IssuanceRecord record,
      ReadRedeemedOperationProjectionRequest request,
      UUID actorId,
      UUID tenantId,
      UUID tokenJti) {}

  private static StartSessionPreAuthorizationReservationTuple tuple(UUID actorId, UUID tenantId) {
    var action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(tenantId, "test"),
            new StartSessionOperatorAction.Target(1L, actorId),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "authorization test");
    return StartSessionPreAuthorizationReservationTuple.createHuman(
        "request-" + UUID.randomUUID(), actorId, action);
  }

  private static AccountControlUiAuthority.Snapshot source(
      UUID actorId, UUID tenantId, long sourceVersion, byte[] evidence) {
    var source = org.mockito.Mockito.mock(AccountControlUiAuthority.Snapshot.class);
    Map<String, Object> authorityTuple =
        Map.of(
            "issuerAuthGeneration",
            1L,
            "accountAuthorityGeneration",
            1L,
            "tenantAuthorityGeneration",
            Map.of(tenantId.toString(), 1L),
            "membershipAuthorityGeneration",
            Map.of(tenantId.toString(), 1L),
            "privateRealmGrantVersions",
            List.of());
    Map<String, Long> membershipVersion = Map.of(tenantId.toString(), 2L);
    when(source.actor()).thenReturn(actorId);
    when(source.tenant()).thenReturn(tenantId);
    when(source.authorityTuple()).thenReturn(authorityTuple);
    when(source.membershipVersion()).thenReturn(membershipVersion);
    when(source.evidence()).thenReturn(evidence.clone());
    when(source.sources())
        .thenReturn(
            List.of(
                new SourceEvidence(
                    SourceKind.ACCOUNT,
                    actorId.toString(),
                    "1",
                    Long.toString(sourceVersion),
                    null,
                    null,
                    evidence)));
    return source;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static AccountControlUiIssuanceRepository.Stored stored(
      UUID actorId,
      UUID tenantId,
      UUID tokenJti,
      UUID operationId,
      AccountControlUiAuthority.Snapshot source) {
    UUID requestId = UUID.randomUUID();
    Map<String, Object> claims =
        Map.of(
            "jti",
            tokenJti.toString(),
            "sub",
            actorId.toString(),
            "accountId",
            actorId.toString(),
            "aud",
            "control-ui",
            "tokenGeneration",
            1L,
            "authorityTuple",
            source.authorityTuple(),
            "membershipVersion",
            source.membershipVersion(),
            "scopedRoles",
            Map.of(tenantId.toString(), List.of("tenantAdmin")));
    Map<String, Object> values =
        Map.ofEntries(
            Map.entry("request_id", requestId),
            Map.entry("operation_id", operationId),
            Map.entry("token_jti", tokenJti),
            Map.entry("account_uuid", actorId),
            Map.entry("tenant_uuid", tenantId),
            Map.entry("caller_context_id", UUID.randomUUID()),
            Map.entry("caller_workload", "spiffe://firemud/ns/test/sa/account-service"),
            Map.entry("request_mac_key_id", "test-key"),
            Map.entry("request_digest", "b".repeat(64)),
            Map.entry("status", "COMMITTED"),
            Map.entry("token_hash", "c".repeat(64)),
            Map.entry("claims_payload", AccountControlUiAuthority.canonical(claims)),
            Map.entry("source_payload", source.evidence()),
            Map.entry("bundle_payload", AccountControlUiAuthority.canonical(Map.of("v", 1))),
            Map.entry("signer_receipt", AccountControlUiAuthority.canonical(Map.of("v", 1))),
            Map.entry("pending_registry", new byte[] {1}),
            Map.entry("active_registry", new byte[] {2}),
            Map.entry("issued_at_epoch_second", NOW.minusSeconds(10).getEpochSecond()),
            Map.entry("expires_at_epoch_second", NOW.plusSeconds(300).getEpochSecond()),
            Map.entry(
                "recovery_expires_at",
                OffsetDateTime.ofInstant(NOW.plusSeconds(300), ZoneOffset.UTC)));
    Record row = org.mockito.Mockito.mock(Record.class);
    org.mockito.Mockito.doAnswer(invocation -> values.get(invocation.getArgument(0)))
        .when(row)
        .get(anyString(), any(Class.class));
    return new AccountControlUiIssuanceRepository.Stored(row);
  }

  private static ReadCurrentClaimEvidenceResponse claimEvidence(
      StartSessionPreAuthorizationReservationTuple tuple, UUID ownerId) {
    return ReadCurrentClaimEvidenceResponse.newBuilder()
        .setControlPlaneRequestId(tuple.controlPlaneRequestId())
        .setPreAuthorizationTupleJson(
            ByteString.copyFrom(tuple.canonicalJson(), StandardCharsets.UTF_8))
        .setMutationDigest(tuple.mutationDigest())
        .setReservationOwnerId(ownerId.toString())
        .setReservationClaimFence(10L)
        .setClaimOwnerId(ownerId.toString())
        .setClaimFence(10L)
        .build();
  }
}
