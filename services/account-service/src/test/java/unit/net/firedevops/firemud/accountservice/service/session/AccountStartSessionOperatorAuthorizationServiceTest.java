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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.crypto.spec.SecretKeySpec;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.client.StartSessionReservationEvidenceClient;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.IssuanceRecord;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.RedemptionRequest;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.RedemptionResult;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.Status;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService.Current;
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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Focused flow proof; JWT validation/source locking remain owned by the real actor service. */
class AccountStartSessionOperatorAuthorizationServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-08T11:00:00Z");
  private static final String RESPONSE_KEYRING_HEADER =
      "firemud-account-response-envelope-keyring-v1";
  private static final String LOGGING_PEER = "spiffe://firemud/ns/test/sa/logging-admin-service";
  private static final String GAME_SESSION_PEER =
      "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String GAME_DESIGN_PEER = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final DateTimeFormatter CAPTURED_AT_FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  @TempDir Path temporaryDirectory;

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
    var captureRepository =
        org.mockito.Mockito.mock(AccountStartSessionAuthorityCaptureRepository.class);
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
    AccountStartSessionAuthorityCapture capture =
        capture(tuple, originalSource, stored, requestOwnerId, 10L);
    when(captureRepository.prepareOrReadExact(any(), any(), anyString(), any(), anyLong()))
        .thenReturn(capture);
    when(captureRepository.lockExactCurrent(any(), any(), any(), anyString(), any(), anyLong()))
        .thenAnswer(
            invocation -> {
              Current current = invocation.getArgument(1);
              if (current.source() != originalSource) {
                throw new IllegalArgumentException("Current Account source changed");
              }
              return capture;
            });
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
    doAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(true);
              try {
                @SuppressWarnings("unchecked")
                Function<Current, Object> action = invocation.getArgument(4);
                return action.apply(changed);
              } finally {
                TransactionSynchronizationManager.clear();
              }
            })
        .when(actors)
        .withCurrentCommitted(any(), any(), any(), any(), any());

    var service =
        new AccountStartSessionOperatorAuthorizationService(
            actors,
            issuanceOperations,
            captureRepository,
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
  void ownerIssuesRecoversAndReplaysSameAttemptAcrossReferenceExpiry() throws Exception {
    String requestId = "é".repeat(64);
    byte[] requestIdBytes = requestId.getBytes(StandardCharsets.UTF_8);
    assertThat(requestIdBytes).hasSize(128);

    UUID actorId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID requestOwnerId = UUID.randomUUID();
    UUID issuerOperationId = UUID.randomUUID();
    UUID tokenJti = UUID.randomUUID();
    StartSessionPreAuthorizationReservationTuple tuple = tuple(requestId, actorId, tenantId);
    byte[] exactTuple = tuple.canonicalJson().getBytes(StandardCharsets.UTF_8);
    AccountControlUiAuthority.Snapshot source = source(actorId, tenantId, 7L, new byte[] {1});
    AccountControlUiIssuanceRepository.Stored stored =
        stored(actorId, tenantId, tokenJti, issuerOperationId, source);
    Current current = new Current(stored, source);

    var actors = org.mockito.Mockito.mock(AccountControlUiActorService.class);
    var issuanceOperations = org.mockito.Mockito.mock(AccountControlUiIssuanceRepository.class);
    var claims = org.mockito.Mockito.mock(StartSessionReservationEvidenceClient.class);
    var repository =
        org.mockito.Mockito.mock(AccountStartSessionOperatorAuthorizationRepository.class);
    MutableClock operationClock = new MutableClock(NOW);
    byte[] fingerprintKey = new byte[32];
    java.util.Arrays.fill(fingerprintKey, (byte) 11);
    var fingerprintKeys =
        new AccountOperatorAuthorizationFingerprintKeyring(
            () ->
                new AccountOperatorAuthorizationFingerprintKeyring.Snapshot(
                    new AccountOperatorAuthorizationFingerprintKeyring.KeyMaterial(
                        "test-key", new SecretKeySpec(fingerprintKey, "HmacSHA256")),
                    List.of()),
            operationClock,
            Duration.ofSeconds(20));
    var hostedTerms = org.mockito.Mockito.mock(AccountHostedTermsService.class);
    var transactions = org.mockito.Mockito.mock(PlatformTransactionManager.class);
    var environment =
        org.mockito.Mockito.mock(AccountHostedTermsService.CapturedEnvironmentBoundary.class);
    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(ignored -> new SimpleTransactionStatus());
    when(hostedTerms.captureCurrentEnvironmentBoundary()).thenReturn(environment);
    when(claims.readCurrentClaimEvidence(any(), any(), anyLong(), any(), anyLong(), any()))
        .thenAnswer(
            invocation ->
                claimEvidence(
                    invocation.<StartSessionPreAuthorizationReservationTuple>getArgument(0),
                    invocation.<UUID>getArgument(1)));

    Path responseKeyringDirectory = temporaryDirectory.resolve("operator-response");
    Files.createDirectories(responseKeyringDirectory);
    byte[] responseKey = new byte[AccountResponseEnvelopeKeyring.KEY_BYTES];
    java.util.Arrays.fill(responseKey, (byte) 7);
    Files.writeString(
        responseKeyringDirectory.resolve("keyring"),
        RESPONSE_KEYRING_HEADER
            + "\nactive test-key "
            + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(responseKey)
            + "\n",
        StandardCharsets.US_ASCII);
    var responseCrypto =
        new AccountResponseEnvelopeCryptography(
            new AccountResponseEnvelopeKeyring(responseKeyringDirectory.toString()),
            operationClock,
            new java.security.SecureRandom());

    Map<String, IssuanceRecord> durableRecords = new ConcurrentHashMap<>();
    Map<String, RedemptionRequest> originalRedemptions = new ConcurrentHashMap<>();
    when(repository.findByControlPlaneRequestId(anyString()))
        .thenAnswer(
            invocation ->
                Optional.ofNullable(durableRecords.get(invocation.<String>getArgument(0))));
    when(repository.createOrReadExact(any()))
        .thenAnswer(
            invocation -> {
              var candidate =
                  invocation
                      .<AccountStartSessionOperatorAuthorizationRepository.IssuanceCandidate>
                          getArgument(0);
              IssuanceRecord issuance = issuedRecord(candidate);
              IssuanceRecord existing =
                  durableRecords.putIfAbsent(candidate.controlPlaneRequestId(), issuance);
              return new AccountStartSessionOperatorAuthorizationRepository.CreateOrReadResult(
                  existing == null, existing == null ? issuance : existing);
            });
    when(repository.redeemExact(any()))
        .thenAnswer(
            invocation -> {
              RedemptionRequest redemption = invocation.getArgument(0);
              IssuanceRecord issuance = durableRecords.get(redemption.controlPlaneRequestId());
              assertThat(issuance).isNotNull();
              assertThat(redemption.controlPlaneRequestId())
                  .isEqualTo(issuance.controlPlaneRequestId());
              assertThat(redemption.preAuthorizationTuple())
                  .containsExactly(issuance.preAuthorizationTuple());
              assertThat(redemption.authorizationReferenceFingerprint())
                  .isEqualTo(issuance.authorizationReferenceFingerprint());
              assertThat(redemption.reservationOwnerId()).isEqualTo(issuance.reservationOwnerId());
              assertThat(redemption.reservationClaimFence())
                  .isEqualTo(issuance.reservationClaimFence());
              assertThat(redemption.redeemerWorkloadUri()).isEqualTo(GAME_SESSION_PEER);
              assertThat(redemption.authorityEvidenceBundle())
                  .containsExactly(issuance.authorityEvidenceBundle());
              assertThat(redemption.now()).isEqualTo(operationClock.instant());

              boolean replay = issuance.status() == Status.REDEEMED;
              if (replay) {
                RedemptionRequest original =
                    originalRedemptions.get(redemption.controlPlaneRequestId());
                assertThat(original).isNotNull();
                assertThat(redemption.ownerAttemptId()).isEqualTo(original.ownerAttemptId());
                assertThat(redemption.ownerFence()).isEqualTo(original.ownerFence());
              } else {
                assertThat(issuance.status()).isEqualTo(Status.ISSUED);
                assertThat(redemption.now()).isBefore(issuance.referenceExpiresAt());
                originalRedemptions.put(redemption.controlPlaneRequestId(), redemption);
                durableRecords.put(
                    redemption.controlPlaneRequestId(), withRedemption(issuance, redemption));
              }
              return new RedemptionResult(
                  issuance.authorizationReferenceFingerprint(),
                  issuance.authorityEvidenceBundle(),
                  issuance.issuanceOperationId(),
                  issuance.issuanceFence(),
                  replay);
            });
    when(issuanceOperations.captureOwnerLinearization())
        .thenReturn(new OwnerLinearization("123456", 99L));
    AtomicReference<Current> currentForCalls = new AtomicReference<>(current);
    doAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(true);
              try {
                @SuppressWarnings("unchecked")
                Function<Current, Object> action = invocation.getArgument(3);
                return action.apply(currentForCalls.get());
              } finally {
                TransactionSynchronizationManager.clear();
              }
            })
        .when(actors)
        .withCurrent(anyString(), any(), any(), any());
    doAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(true);
              try {
                @SuppressWarnings("unchecked")
                Function<Current, Object> action = invocation.getArgument(4);
                return action.apply(currentForCalls.get());
              } finally {
                TransactionSynchronizationManager.clear();
              }
            })
        .when(actors)
        .withCurrentCommitted(any(), any(), any(), any(), any());

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
            operationClock,
            new java.security.SecureRandom(),
            LOGGING_PEER,
            GAME_SESSION_PEER,
            java.time.Duration.ofSeconds(60),
            java.time.Duration.ofSeconds(20));
    var issueRequest =
        new AccountStartSessionOperatorAuthorizationService.IssueRequest(
            "test-only-forwarded-control-ui-credential",
            exactTuple,
            requestOwnerId,
            10L,
            requestOwnerId,
            10L);
    AtomicReference<AccountStartSessionOperatorAuthorizationService.AuthorizationResponse> issued =
        new AtomicReference<>();
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(LOGGING_PEER).orElseThrow())
        .run(() -> issued.set(service.issue(issueRequest)));

    IssuanceRecord record = durableRecords.get(tuple.controlPlaneRequestId());
    assertThat(record).isNotNull();
    assertThat(record.status()).isEqualTo(Status.ISSUED);
    assertThat(record.encryptedResponseEnvelope()).isNotEmpty();
    assertThat(record.encryptedResponseEnvelope().length).isLessThanOrEqualTo(160 * 1024);
    AccountStartSessionOperatorAuthorityBundle bundle =
        AccountStartSessionOperatorAuthorityBundle.decode(record.authorityEvidenceBundle());
    assertThat(AccountStartSessionOperatorAuthorizationService.envelopeBindingRequestId(requestId))
        .isEqualTo(
            java.util.HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(requestIdBytes)));
    assertThat(
            AccountStartSessionOperatorAuthorizationService.envelopeBindingRequestId(
                "retained-ascii-request-01"))
        .isEqualTo("retained-ascii-request-01");

    var recoverRequest =
        new AccountStartSessionOperatorAuthorizationService.RecoverRequest(
            exactTuple, requestOwnerId, 10L, requestOwnerId, 10L);
    AtomicReference<AccountStartSessionOperatorAuthorizationService.AuthorizationResponse>
        recovered = new AtomicReference<>();
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(LOGGING_PEER).orElseThrow())
        .run(() -> recovered.set(service.recover(recoverRequest)));
    assertThat(recovered.get().operatorAuthorizationReference())
        .isEqualTo(issued.get().operatorAuthorizationReference());
    assertThat(recovered.get().authorityEvidenceBundle())
        .containsExactly(issued.get().authorityEvidenceBundle());

    UUID ownerAttemptId = UUID.randomUUID();
    long ownerFence = 18L;
    var redeemRequest =
        new AccountStartSessionOperatorAuthorizationService.RedeemRequest(
            exactTuple,
            recovered.get().operatorAuthorizationReference(),
            recovered.get().authorizationReferenceFingerprint(),
            requestOwnerId,
            10L,
            ownerAttemptId,
            ownerFence);
    AtomicReference<RedemptionResult> firstRedemption = new AtomicReference<>();
    operationClock.setInstant(record.referenceExpiresAt().minusMillis(1));
    gameSessionContext().run(() -> firstRedemption.set(service.redeem(redeemRequest)));
    assertThat(firstRedemption.get().replay()).isFalse();

    AtomicReference<RedemptionResult> recoveredRetry = new AtomicReference<>();
    operationClock.setInstant(record.referenceExpiresAt().plusSeconds(1));
    assertThat(operationClock.instant()).isBefore(record.responseEnvelopeExpiresAt());
    gameSessionContext().run(() -> recoveredRetry.set(service.redeem(redeemRequest)));
    assertThat(recoveredRetry.get().replay()).isTrue();
    assertThat(recoveredRetry.get().authorizationReferenceFingerprint())
        .isEqualTo(firstRedemption.get().authorizationReferenceFingerprint());
    assertThat(recoveredRetry.get().authorityEvidenceBundle())
        .containsExactly(firstRedemption.get().authorityEvidenceBundle());
    assertThat(recoveredRetry.get().issuanceOperationId())
        .isEqualTo(firstRedemption.get().issuanceOperationId());
    assertThat(recoveredRetry.get().issuanceFence())
        .isEqualTo(firstRedemption.get().issuanceFence());

    AtomicReference<RedemptionResult> postEnvelopeRetry = new AtomicReference<>();
    operationClock.setInstant(record.responseEnvelopeExpiresAt());
    gameSessionContext().run(() -> postEnvelopeRetry.set(service.redeem(redeemRequest)));
    assertThat(postEnvelopeRetry.get().replay()).isTrue();
    assertThat(postEnvelopeRetry.get().authorizationReferenceFingerprint())
        .isEqualTo(firstRedemption.get().authorizationReferenceFingerprint());
    assertThat(postEnvelopeRetry.get().authorityEvidenceBundle())
        .containsExactly(firstRedemption.get().authorityEvidenceBundle());
    assertThat(postEnvelopeRetry.get().issuanceOperationId())
        .isEqualTo(firstRedemption.get().issuanceOperationId());
    assertThat(postEnvelopeRetry.get().issuanceFence())
        .isEqualTo(firstRedemption.get().issuanceFence());
    verify(repository, times(3)).redeemExact(any());

    var changedAttempt =
        new AccountStartSessionOperatorAuthorizationService.RedeemRequest(
            exactTuple,
            recovered.get().operatorAuthorizationReference(),
            recovered.get().authorizationReferenceFingerprint(),
            requestOwnerId,
            10L,
            UUID.randomUUID(),
            ownerFence);
    var changedFence =
        new AccountStartSessionOperatorAuthorizationService.RedeemRequest(
            exactTuple,
            recovered.get().operatorAuthorizationReference(),
            recovered.get().authorizationReferenceFingerprint(),
            requestOwnerId,
            10L,
            ownerAttemptId,
            ownerFence + 1);
    for (var changedRequest : List.of(changedAttempt, changedFence)) {
      gameSessionContext()
          .run(
              () ->
                  assertThatThrownBy(() -> service.redeem(changedRequest))
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessageContaining("cannot authorize another owner attempt"));
    }

    AccountControlUiAuthority.Snapshot changedSource =
        source(actorId, tenantId, 8L, new byte[] {2});
    currentForCalls.set(new Current(stored, changedSource));
    gameSessionContext()
        .run(
            () ->
                assertThatThrownBy(() -> service.redeem(redeemRequest))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Current Account source differs"));
    verify(repository, times(3)).redeemExact(any());

    currentForCalls.set(current);
    StartSessionPreAuthorizationReservationTuple expiredTuple = tuple(actorId, tenantId);
    byte[] expiredTupleBytes = expiredTuple.canonicalJson().getBytes(StandardCharsets.UTF_8);
    var expiredIssueRequest =
        new AccountStartSessionOperatorAuthorizationService.IssueRequest(
            "test-only-forwarded-control-ui-credential",
            expiredTupleBytes,
            requestOwnerId,
            10L,
            requestOwnerId,
            10L);
    AtomicReference<AccountStartSessionOperatorAuthorizationService.AuthorizationResponse>
        expiredIssued = new AtomicReference<>();
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(LOGGING_PEER).orElseThrow())
        .run(() -> expiredIssued.set(service.issue(expiredIssueRequest)));
    IssuanceRecord expiredRecord = durableRecords.get(expiredTuple.controlPlaneRequestId());
    var firstRedemptionAfterExpiry =
        new AccountStartSessionOperatorAuthorizationService.RedeemRequest(
            expiredTupleBytes,
            expiredIssued.get().operatorAuthorizationReference(),
            expiredIssued.get().authorizationReferenceFingerprint(),
            requestOwnerId,
            10L,
            UUID.randomUUID(),
            19L);
    operationClock.setInstant(expiredRecord.referenceExpiresAt());
    gameSessionContext()
        .run(
            () ->
                assertThatThrownBy(() -> service.redeem(firstRedemptionAfterExpiry))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Original Account authority evidence is expired"));
    verify(repository, times(3)).redeemExact(any());

    operationClock.setInstant(record.responseEnvelopeExpiresAt().minusMillis(1));
    String distinctRequestId = "é".repeat(63) + "è";
    assertThat(distinctRequestId.getBytes(StandardCharsets.UTF_8)).hasSize(128);
    assertThat(
            StartSessionPreAuthorizationReservationTuple.requireCanonicalControlPlaneRequestId(
                distinctRequestId))
        .isEqualTo(distinctRequestId);
    var distinctBinding =
        new AccountResponseEnvelopeCryptography.Binding(
            "human-start-session-authorization/v1",
            AccountStartSessionOperatorAuthorizationService.envelopeBindingRequestId(
                distinctRequestId),
            MessageDigest.getInstance("SHA-256").digest(exactTuple),
            LOGGING_PEER,
            record.issuanceOperationId().toString(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            record.issuanceFence(),
            AccountControlUiAuthority.canonical(bundle.authorityTuple()),
            AccountControlUiAuthority.canonical(bundle.membershipVersion()),
            bundle.canonicalBytes());
    assertThatThrownBy(
            () ->
                responseCrypto.decrypt(
                    new AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope(
                        record.encryptedResponseEnvelope()),
                    distinctBinding,
                    record.responseEnvelopeExpiresAt()))
        .isInstanceOf(AccountResponseEnvelopeCryptography.IntegrityException.class);
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
  void exactOriginalGameSessionRedeemerCanReadItsImmutableProjection() {
    ReadHarness harness = readHarness(NOW.plusSeconds(30));
    allowCurrent(harness, harness.source());
    AtomicReference<RedeemedOperationProjection> result = new AtomicReference<>();

    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri(GAME_SESSION_PEER).orElseThrow())
        .run(
            () -> result.set(harness.service().readRedeemedOperationProjection(harness.request())));

    assertThat(result.get().redeemerWorkloadUri()).isEqualTo(GAME_SESSION_PEER);
    assertThat(result.get().canonicalPreAuthorizationTuple())
        .isEqualTo(harness.record().preAuthorizationTuple());
    verify(harness.repository(), times(2))
        .findByControlPlaneRequestId(harness.tuple().controlPlaneRequestId());
    verifyNoInteractions(harness.claims(), harness.fingerprintKeys(), harness.responseCrypto());
  }

  @Test
  void
      projectionRequiresExactSameNamespaceGameDesignOrOriginalGameSessionPeerBeforeTupleParsingOrLookup() {
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
            "spiffe://firemud/ns/other/sa/game-design-service",
            "spiffe://firemud/ns/other/sa/game-session-service",
            LOGGING_PEER)) {
      Context.current()
          .withValue(
              GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(wrongPeerUri).orElseThrow())
          .run(
              () ->
                  assertThatThrownBy(
                          () -> harness.service().readRedeemedOperationProjection(malformed))
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessageContaining(
                          "Exact authenticated Game Design or original Game Session identity"));
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
                    .isInstanceOf(IllegalStateException.class)
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

  @Test
  void recoveryDoesNotDecryptOrRecreateAnErasedOriginalResponse() {
    ReadHarness harness = readHarness(NOW.plusSeconds(30));
    IssuanceRecord erased = asIssued(withErasedEnvelope(harness.record()));
    when(harness.repository().findByControlPlaneRequestId(harness.tuple().controlPlaneRequestId()))
        .thenReturn(Optional.of(erased));
    when(harness
            .claims()
            .readCurrentClaimEvidence(any(), any(), anyLong(), any(), anyLong(), any()))
        .thenReturn(claimEvidence(harness.tuple(), erased.reservationOwnerId()));
    allowCurrent(harness, harness.source());

    var request =
        new AccountStartSessionOperatorAuthorizationService.RecoverRequest(
            harness.tuple().canonicalJson().getBytes(StandardCharsets.UTF_8),
            erased.reservationOwnerId(),
            erased.reservationClaimFence(),
            erased.reservationOwnerId(),
            erased.reservationClaimFence());

    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(LOGGING_PEER).orElseThrow())
        .run(
            () ->
                assertThatThrownBy(() -> harness.service().recover(request))
                    .isInstanceOf(
                        AccountStartSessionOperatorAuthorizationService
                            .ResponseEnvelopeErasedException.class));

    verify(harness.responseCrypto(), never()).decrypt(any(), any(), any());
    verify(harness.repository(), never()).createOrReadExact(any());
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
    Instant capturedAt =
        referenceExpiresAt.isAfter(NOW) ? NOW : referenceExpiresAt.minusSeconds(120);
    AccountStartSessionAuthorityCapture capture =
        capture(tuple, source, stored, reservationOwnerId, 10L, capturedAt);
    Instant issuedAt = referenceExpiresAt.minusSeconds(50);
    Instant redeemedAt =
        referenceExpiresAt.isAfter(NOW) ? NOW.minusSeconds(1) : referenceExpiresAt.minusSeconds(1);
    var bundle =
        AccountStartSessionOperatorAuthorityBundle.create(
            tuple, capture, stored, issuanceOperationId, issuedAt, referenceExpiresAt);
    var bundleReference = bundle.referenceForCapture(capture);
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
            bundle.issuanceFence(),
            AccountStartSessionOperatorAuthorityBundle.toSharedReference(bundleReference),
            bundle.canonicalBytes(),
            fingerprint,
            new byte[] {9, 8, 7},
            issuedAt,
            referenceExpiresAt,
            referenceExpiresAt.plusSeconds(20),
            null,
            Status.REDEEMED,
            GAME_SESSION_PEER,
            ownerAttemptId,
            18L,
            redeemedAt,
            fingerprint,
            bundle.canonicalBytes());

    var actors = org.mockito.Mockito.mock(AccountControlUiActorService.class);
    var issuanceOperations = org.mockito.Mockito.mock(AccountControlUiIssuanceRepository.class);
    var captureRepository =
        org.mockito.Mockito.mock(AccountStartSessionAuthorityCaptureRepository.class);
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
    when(captureRepository.lockReadExactCurrent(any(), any(), anyString(), any(), anyLong()))
        .thenReturn(capture);
    var service =
        new AccountStartSessionOperatorAuthorizationService(
            actors,
            issuanceOperations,
            captureRepository,
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
        captureRepository,
        fingerprintKeys,
        responseCrypto,
        hostedTerms,
        environment,
        tuple,
        source,
        stored,
        record,
        capture,
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

  private static AccountStartSessionAuthorityCapture capture(
      StartSessionPreAuthorizationReservationTuple tuple,
      AccountControlUiAuthority.Snapshot source,
      AccountControlUiIssuanceRepository.Stored stored,
      UUID reservationOwnerId,
      long reservationClaimFence) {
    return capture(tuple, source, stored, reservationOwnerId, reservationClaimFence, NOW);
  }

  private static AccountStartSessionAuthorityCapture capture(
      StartSessionPreAuthorizationReservationTuple tuple,
      AccountControlUiAuthority.Snapshot source,
      AccountControlUiIssuanceRepository.Stored stored,
      UUID reservationOwnerId,
      long reservationClaimFence,
      Instant capturedAt) {
    byte[] signerReceipt = stored.signerReceipt.clone();
    Map<String, Object> snapshot =
        Map.ofEntries(
            Map.entry("schema", "account-start-session-authority-snapshot/v1"),
            Map.entry("controlPlaneRequestId", tuple.controlPlaneRequestId()),
            Map.entry(
                "preAuthorizationTuple",
                Base64.getEncoder()
                    .encodeToString(tuple.canonicalJson().getBytes(StandardCharsets.UTF_8))),
            Map.entry("mutationDigest", tuple.mutationDigest()),
            Map.entry("accountId", tuple.actor().accountId().toString()),
            Map.entry("tenantId", tuple.action().scope().tenantId().toString()),
            Map.entry("targetOwner", tuple.targetOwner()),
            Map.entry("loggingWorkloadUri", LOGGING_PEER),
            Map.entry("reservationOwnerId", reservationOwnerId.toString()),
            Map.entry("reservationClaimFence", Long.toString(reservationClaimFence)),
            Map.entry("controlUiOperationId", stored.operationId.toString()),
            Map.entry("controlUiTokenJti", stored.jti.toString()),
            Map.entry("controlUiTokenHash", stored.tokenHash),
            Map.entry("controlUiSignerReceipt", Base64.getEncoder().encodeToString(signerReceipt)),
            Map.entry("controlUiSignerReceiptSha256", sha256(signerReceipt)),
            Map.entry(
                "sourceVectorEvidence", Base64.getEncoder().encodeToString(source.evidence())),
            Map.entry(
                "sourceVector",
                source.sources().stream()
                    .map(evidence -> Base64.getEncoder().encodeToString(evidence.canonicalBytes()))
                    .toList()),
            Map.entry("outboxCheckpoints", source.outboxCheckpoints()),
            Map.entry("authorityTuple", source.authorityTuple()),
            Map.entry("membershipVersion", source.membershipVersion()),
            Map.entry("accountIdentitySource", source.accountIdentitySource()),
            Map.entry("issuanceFence", Long.toString(source.issuanceFence())),
            Map.entry(
                "issuanceFenceSourceVersion", Long.toString(source.issuanceFenceSourceVersion())));
    return AccountStartSessionAuthorityCapture.create(
        tuple.controlPlaneRequestId(),
        17L,
        31L,
        "123456",
        CAPTURED_AT_FORMAT.format(capturedAt),
        AccountControlUiAuthority.canonical(snapshot));
  }

  private static String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
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
        record.responseEnvelopeErasedAt(),
        Status.ISSUED,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static IssuanceRecord withErasedEnvelope(IssuanceRecord record) {
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
        null,
        record.issuedAt(),
        record.referenceExpiresAt(),
        record.responseEnvelopeExpiresAt(),
        record.responseEnvelopeExpiresAt(),
        record.status(),
        record.redemptionRedeemerWorkloadUri(),
        record.redemptionOwnerAttemptId(),
        record.redemptionOwnerFence(),
        record.redeemedAt(),
        record.redemptionReferenceFingerprint(),
        record.redemptionAuthorityEvidenceBundle());
  }

  private static IssuanceRecord withRedemption(
      IssuanceRecord record, RedemptionRequest redemption) {
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
        record.responseEnvelopeErasedAt(),
        Status.REDEEMED,
        redemption.redeemerWorkloadUri(),
        redemption.ownerAttemptId(),
        redemption.ownerFence(),
        redemption.now(),
        redemption.authorizationReferenceFingerprint(),
        redemption.authorityEvidenceBundle());
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
        record.responseEnvelopeErasedAt(),
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

  private static Context gameSessionContext() {
    return Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri(GAME_SESSION_PEER).orElseThrow());
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> currentInstant;
    private final ZoneId zone;

    MutableClock(Instant initialInstant) {
      this(new AtomicReference<>(initialInstant), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> currentInstant, ZoneId zone) {
      this.currentInstant = currentInstant;
      this.zone = zone;
    }

    void setInstant(Instant instant) {
      currentInstant.set(instant);
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId requestedZone) {
      return new MutableClock(currentInstant, requestedZone);
    }

    @Override
    public Instant instant() {
      return currentInstant.get();
    }
  }

  private record ReadHarness(
      AccountStartSessionOperatorAuthorizationService service,
      AccountControlUiActorService actors,
      StartSessionReservationEvidenceClient claims,
      AccountStartSessionOperatorAuthorizationRepository repository,
      AccountStartSessionAuthorityCaptureRepository captureRepository,
      AccountOperatorAuthorizationFingerprintKeyring fingerprintKeys,
      AccountResponseEnvelopeCryptography responseCrypto,
      AccountHostedTermsService hostedTerms,
      AccountHostedTermsService.CapturedEnvironmentBoundary environment,
      StartSessionPreAuthorizationReservationTuple tuple,
      AccountControlUiAuthority.Snapshot source,
      AccountControlUiIssuanceRepository.Stored stored,
      IssuanceRecord record,
      AccountStartSessionAuthorityCapture capture,
      ReadRedeemedOperationProjectionRequest request,
      UUID actorId,
      UUID tenantId,
      UUID tokenJti) {}

  private static StartSessionPreAuthorizationReservationTuple tuple(UUID actorId, UUID tenantId) {
    return tuple("request-" + UUID.randomUUID(), actorId, tenantId);
  }

  private static StartSessionPreAuthorizationReservationTuple tuple(
      String requestId, UUID actorId, UUID tenantId) {
    var action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(tenantId, "test"),
            new StartSessionOperatorAction.Target(1L, actorId),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "authorization test");
    return StartSessionPreAuthorizationReservationTuple.createHuman(requestId, actorId, action);
  }

  private static IssuanceRecord issuedRecord(
      AccountStartSessionOperatorAuthorizationRepository.IssuanceCandidate candidate) {
    return new IssuanceRecord(
        candidate.controlPlaneRequestId(),
        candidate.preAuthorizationTuple(),
        candidate.mutationDigest(),
        candidate.issuanceWorkloadUri(),
        candidate.reservationOwnerId(),
        candidate.reservationClaimFence(),
        candidate.issuanceOperationId(),
        candidate.issuanceFence(),
        candidate.bundleReference(),
        candidate.authorityEvidenceBundle(),
        candidate.authorizationReferenceFingerprint(),
        candidate.encryptedResponseEnvelope(),
        candidate.issuedAt(),
        candidate.referenceExpiresAt(),
        candidate.responseEnvelopeExpiresAt(),
        null,
        Status.ISSUED,
        null,
        null,
        null,
        null,
        null,
        null);
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
    when(source.issuanceFence()).thenReturn(43L);
    when(source.issuanceFenceSourceVersion()).thenReturn(44L);
    when(source.outboxCheckpoints()).thenReturn(List.of());
    when(source.accountIdentitySource()).thenReturn(Map.of("provenance", "registered"));
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
        .setObservedAtEpochMillis(NOW.minusSeconds(1).toEpochMilli())
        .setClaimExpiresAtEpochMillis(NOW.plusSeconds(30).toEpochMilli())
        .build();
  }
}
