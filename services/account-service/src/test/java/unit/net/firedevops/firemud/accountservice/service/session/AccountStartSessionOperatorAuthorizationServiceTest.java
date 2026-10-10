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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.client.StartSessionReservationEvidenceClient;
import net.firedevops.firemud.accountservice.client.StartSessionReservationEvidenceClient.Purpose;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.IssuanceRecord;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.Status;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService.Current;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationReferenceFingerprint.ReferenceKind;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService.ReadRedeemedOperationProjectionRequest;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService.RedeemedOperationProjection;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceResponse;
import net.firedevops.firemud.loggingadmin.v1.StartSessionReservationEvidencePurpose;
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
  private static final DateTimeFormatter CAPTURED_AT_FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  private static final String LOGGING_PEER = "spiffe://firemud/ns/test/sa/logging-admin-service";
  private static final String GAME_SESSION_PEER =
      "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String GAME_DESIGN_PEER = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String WORLD_MANAGEMENT_PEER =
      "spiffe://firemud/ns/test/sa/world-management-service";

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
    List<String> events = new java.util.ArrayList<>();

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
    List<AccountHostedTermsService.CapturedEnvironmentBoundary> environmentCaptures =
        new java.util.ArrayList<>();
    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(new SimpleTransactionStatus());
    when(hostedTerms.captureCurrentEnvironmentBoundary())
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              events.add("environment-capture");
              var environment =
                  org.mockito.Mockito.mock(
                      AccountHostedTermsService.CapturedEnvironmentBoundary.class);
              environmentCaptures.add(environment);
              return environment;
            });
    AccountStartSessionAuthorityCapture capture =
        capture(tuple, originalSource, stored, requestOwnerId, 10L);
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(invocation.<Current>getArgument(0)).isSameAs(original);
              assertThat(invocation.<String>getArgument(2)).isEqualTo(LOGGING_PEER);
              events.add("capture-persisted");
              return capture;
            })
        .when(captureRepository)
        .prepareOrReadExact(any(), eq(tuple), eq(LOGGING_PEER), eq(requestOwnerId), eq(10L));
    when(repository.findByControlPlaneRequestId(tuple.controlPlaneRequestId()))
        .thenReturn(Optional.empty());
    when(claims.readCurrentClaimEvidence(any(), any(), anyLong(), any(), anyLong(), any()))
        .thenAnswer(
            ignored -> {
              org.assertj.core.api.Assertions.assertThat(
                      TransactionSynchronizationManager.isActualTransactionActive())
                  .isFalse();
              events.add("claim-read");
              return claimEvidence(tuple, requestOwnerId);
            });
    doAnswer(
            invocation -> {
              org.assertj.core.api.Assertions.assertThat(
                      TransactionSynchronizationManager.isActualTransactionActive())
                  .isFalse();
              events.add("fingerprint");
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
              events.add("encrypt");
              return new AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope(
                  new byte[] {3, 4, 5});
            });

    AtomicInteger ownerPass = new AtomicInteger();
    var secureRandom =
        new java.security.SecureRandom() {
          @Override
          public void nextBytes(byte[] bytes) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            events.add("random");
            java.util.Arrays.fill(bytes, (byte) 0x5a);
          }
        };
    doAnswer(
            invocation -> {
              int pass = ownerPass.getAndIncrement();
              Current current = pass == 0 ? original : changed;
              assertThat(
                      invocation.<AccountHostedTermsService.CapturedEnvironmentBoundary>getArgument(
                          2))
                  .isSameAs(environmentCaptures.get(environmentCaptures.size() - 1));
              TransactionSynchronizationManager.setActualTransactionActive(true);
              Object result;
              try {
                @SuppressWarnings("unchecked")
                Function<Current, Object> action = invocation.getArgument(3);
                result = action.apply(current);
              } finally {
                TransactionSynchronizationManager.clear();
              }
              events.add("current-preparation-committed");
              return result;
            })
        .when(actors)
        .withCurrent(anyString(), any(), any(), any());
    doAnswer(
            invocation -> {
              events.add("final-current");
              assertThat(
                      invocation.<AccountHostedTermsService.CapturedEnvironmentBoundary>getArgument(
                          3))
                  .isSameAs(environmentCaptures.get(environmentCaptures.size() - 1));
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
            secureRandom,
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
    verify(captureRepository)
        .prepareOrReadExact(any(), eq(tuple), eq(LOGGING_PEER), eq(requestOwnerId), eq(10L));
    verify(captureRepository, never())
        .lockExactCurrent(any(), any(), any(), anyString(), any(), anyLong());
    assertThat(events.indexOf("claim-read")).isLessThan(events.indexOf("capture-persisted"));
    assertThat(events.indexOf("capture-persisted"))
        .isLessThan(events.indexOf("current-preparation-committed"));
    assertThat(events.indexOf("current-preparation-committed"))
        .isLessThan(events.indexOf("random"));
    assertThat(events.indexOf("random")).isLessThan(events.indexOf("fingerprint"));
    assertThat(events.indexOf("fingerprint")).isLessThan(events.indexOf("encrypt"));
    assertThat(environmentCaptures).hasSize(2);
    assertThat(events.indexOf("encrypt")).isLessThan(events.lastIndexOf("environment-capture"));
    assertThat(events.lastIndexOf("environment-capture"))
        .isLessThan(events.indexOf("final-current"));
  }

  @Test
  void successfulIssueLocksTheSameCommittedCaptureBeforeWritingIssuedRecord() {
    IssueHarness harness = issueHarness(false, false);
    AtomicReference<AccountStartSessionOperatorAuthorizationService.AuthorizationResponse>
        response = new AtomicReference<>();

    loggingContext().run(() -> response.set(harness.service().issue(harness.issueRequest())));

    assertThat(harness.events())
        .containsSubsequence(
            "claim-read",
            "capture-persisted",
            "initial-current-returned",
            "random",
            "fingerprint",
            "encrypt",
            "final-current",
            "capture-lock",
            "issuance-row-insert");
    assertThat(harness.environmentCaptures()).hasSize(2);
    assertThat(harness.events().indexOf("encrypt"))
        .isLessThan(harness.events().indexOf("environment-capture-2"));
    assertThat(harness.events().indexOf("environment-capture-2"))
        .isLessThan(harness.events().indexOf("final-current"));
    assertThat(harness.randomCalls()).hasValue(1);
    assertThat(response.get().operatorAuthorizationReference())
        .isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(fill(32, (byte) 0x5a)));
    assertThat(response.get().expiresAt()).isEqualTo(NOW.plusSeconds(60));
    assertThat(response.get().bundleReference())
        .isEqualTo(
            AccountStartSessionOperatorAuthorityBundle.fromSharedReference(
                harness.capture().bundleReference()));

    var candidateCaptor =
        org.mockito.ArgumentCaptor.forClass(
            AccountStartSessionOperatorAuthorizationRepository.IssuanceCandidate.class);
    verify(harness.repository()).createOrReadExact(candidateCaptor.capture());
    var candidate = candidateCaptor.getValue();
    assertThat(candidate.issuanceFence()).isEqualTo(70L);
    assertThat(candidate.bundleReference().sourceVersion()).isEqualTo("17");
    assertThat(candidate.bundleReference().sourceFence()).isEqualTo("31");
    assertThat(candidate.issuanceFence())
        .isNotEqualTo(Long.parseLong(candidate.bundleReference().sourceFence()));
    verify(harness.actors())
        .withCurrentCommitted(
            eq(harness.tuple().actor().accountId()),
            eq(harness.tuple().action().scope().tenantId()),
            eq(harness.current().stored().jti),
            any(),
            any());
    verify(harness.captureRepository())
        .prepareOrReadExact(
            eq(harness.current()),
            eq(harness.tuple()),
            eq(LOGGING_PEER),
            eq(harness.reservationOwnerId()),
            eq(10L));
    verify(harness.captureRepository())
        .lockExactCurrent(
            eq(harness.capture()),
            eq(harness.current()),
            eq(harness.tuple()),
            eq(LOGGING_PEER),
            eq(harness.reservationOwnerId()),
            eq(10L));
  }

  @Test
  void failedCaptureStopsBeforeRandomFingerprintEncryptionOrIssuanceInsert() {
    IssueHarness harness = issueHarness(true, false);

    loggingContext()
        .run(
            () ->
                assertThatThrownBy(() -> harness.service().issue(harness.issueRequest()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("capture unavailable"));

    assertThat(harness.randomCalls()).hasValue(0);
    verifyNoInteractions(harness.fingerprintKeys(), harness.responseCrypto());
    verify(harness.repository(), never()).createOrReadExact(any());
    verify(harness.captureRepository())
        .prepareOrReadExact(
            any(),
            eq(harness.tuple()),
            eq(LOGGING_PEER),
            eq(harness.reservationOwnerId()),
            eq(10L));
    verify(harness.captureRepository(), never())
        .lockExactCurrent(any(), any(), any(), anyString(), any(), anyLong());
  }

  @Test
  void exactIssueRetryAndRecoveryReturnStoredResponseWithoutReminting() {
    IssueHarness harness = issueHarness(false, true);
    AtomicReference<AccountStartSessionOperatorAuthorizationService.AuthorizationResponse>
        issueRetry = new AtomicReference<>();
    AtomicReference<AccountStartSessionOperatorAuthorizationService.AuthorizationResponse>
        recovery = new AtomicReference<>();

    loggingContext().run(() -> issueRetry.set(harness.service().issue(harness.issueRequest())));
    loggingContext().run(() -> recovery.set(harness.service().recover(harness.recoverRequest())));

    assertThat(harness.environmentCaptures()).hasSize(4);
    assertThat(harness.events().indexOf("decrypt"))
        .isLessThan(harness.events().indexOf("environment-capture-2"));
    assertThat(harness.events().indexOf("environment-capture-2"))
        .isLessThan(harness.events().indexOf("final-current"));
    assertThat(harness.events().lastIndexOf("decrypt"))
        .isLessThan(harness.events().indexOf("environment-capture-4"));
    assertThat(harness.events().indexOf("environment-capture-4"))
        .isLessThan(harness.events().lastIndexOf("final-current"));

    for (var response : List.of(issueRetry.get(), recovery.get())) {
      assertThat(response.operatorAuthorizationReference()).isEqualTo("A".repeat(43));
      assertThat(response.authorizationReferenceFingerprint())
          .isEqualTo(harness.originalResponse().authorizationReferenceFingerprint());
      assertThat(response.expiresAt()).isEqualTo(harness.originalResponse().expiresAt());
      assertThat(response.authorityEvidenceBundle())
          .containsExactly(harness.originalResponse().authorityEvidenceBundle());
      assertThat(response.bundleReference())
          .isEqualTo(harness.originalResponse().bundleReference());
    }
    assertThat(harness.randomCalls()).hasValue(0);
    verify(harness.repository(), never()).createOrReadExact(any());
    verify(harness.fingerprintKeys(), never()).fingerprintForIssue(any(), any());
    verify(harness.fingerprintKeys(), times(2))
        .matchesOriginal(
            eq(ReferenceKind.HUMAN_OPERATOR),
            any(byte[].class),
            eq(harness.originalResponse().authorizationReferenceFingerprint()),
            eq(NOW.plusSeconds(60)),
            eq(NOW.plusSeconds(80)));
    verify(harness.responseCrypto(), never()).encrypt(any(), any(), any());
    verify(harness.responseCrypto(), times(2)).decrypt(any(), any(), any());
    verify(harness.captureRepository(), never())
        .prepareOrReadExact(any(), any(), anyString(), any(), anyLong());
    verify(harness.claims(), times(1))
        .readCurrentClaimEvidence(any(), any(), anyLong(), any(), anyLong(), eq(Purpose.ISSUE));
    verify(harness.claims(), times(1))
        .readCurrentClaimEvidence(any(), any(), anyLong(), any(), anyLong(), eq(Purpose.RECOVER));
    verify(harness.captureRepository(), times(2))
        .lockReadExactCurrent(
            any(),
            eq(harness.tuple()),
            eq(LOGGING_PEER),
            eq(harness.reservationOwnerId()),
            eq(10L));
    verify(harness.captureRepository(), times(2))
        .lockExactCurrent(
            eq(harness.capture()),
            eq(harness.current()),
            eq(harness.tuple()),
            eq(LOGGING_PEER),
            eq(harness.reservationOwnerId()),
            eq(10L));
  }

  @Test
  void storedRetryAndRecoveryRejectCurrentSourceChangeDuringResponseDecryption() {
    for (boolean recovery : List.of(false, true)) {
      IssueHarness harness = issueHarness(false, true);
      AccountControlUiAuthority.Snapshot changedSource =
          source(
              harness.tuple().actor().accountId(),
              harness.tuple().action().scope().tenantId(),
              8L,
              new byte[] {2});
      doAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                harness.events().add("decrypt");
                harness.finalCurrent().set(new Current(harness.current().stored(), changedSource));
                return harness.originalResponseBytes();
              })
          .when(harness.responseCrypto())
          .decrypt(any(), any(), any());

      loggingContext()
          .run(
              () ->
                  assertThatThrownBy(
                          () -> {
                            if (recovery) {
                              harness.service().recover(harness.recoverRequest());
                            } else {
                              harness.service().issue(harness.issueRequest());
                            }
                          })
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessageContaining("source changed"));

      assertThat(harness.environmentCaptures()).hasSize(2);
      assertThat(harness.events().indexOf("decrypt"))
          .isLessThan(harness.events().indexOf("environment-capture-2"));
      assertThat(harness.events().indexOf("environment-capture-2"))
          .isLessThan(harness.events().lastIndexOf("final-current"));
      assertThat(harness.randomCalls()).hasValue(0);
      verify(harness.repository(), never()).createOrReadExact(any());
      verify(harness.fingerprintKeys(), never()).fingerprintForIssue(any(), any());
      verify(harness.responseCrypto(), never()).encrypt(any(), any(), any());
      verify(harness.captureRepository(), never())
          .lockExactCurrent(any(), any(), any(), anyString(), any(), anyLong());
      verify(harness.responseCrypto()).decrypt(any(), any(), any());
    }
  }

  @Test
  void createOrReadRaceLoserRevalidatesOriginalAfterDecryptBeforeReturningIt() {
    for (boolean sourceChanges : List.of(false, true)) {
      IssueHarness harness = issueHarness(false, false);
      when(harness
              .repository()
              .findByControlPlaneRequestId(harness.tuple().controlPlaneRequestId()))
          .thenReturn(Optional.empty(), Optional.of(harness.originalRecord()));
      doAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                harness.events().add("issuance-race-lost");
                return new AccountStartSessionOperatorAuthorizationRepository.CreateOrReadResult(
                    false, harness.originalRecord());
              })
          .when(harness.repository())
          .createOrReadExact(any());
      AccountControlUiAuthority.Snapshot changedSource =
          source(
              harness.tuple().actor().accountId(),
              harness.tuple().action().scope().tenantId(),
              8L,
              new byte[] {2});
      doAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                harness.events().add("decrypt");
                if (sourceChanges) {
                  harness
                      .finalCurrent()
                      .set(new Current(harness.current().stored(), changedSource));
                }
                return harness.originalResponseBytes();
              })
          .when(harness.responseCrypto())
          .decrypt(any(), any(), any());

      if (sourceChanges) {
        loggingContext()
            .run(
                () ->
                    assertThatThrownBy(() -> harness.service().issue(harness.issueRequest()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("source changed"));
      } else {
        AtomicReference<AccountStartSessionOperatorAuthorizationService.AuthorizationResponse>
            response = new AtomicReference<>();
        loggingContext().run(() -> response.set(harness.service().issue(harness.issueRequest())));
        assertThat(response.get().operatorAuthorizationReference()).isEqualTo("A".repeat(43));
        assertThat(response.get().authorizationReferenceFingerprint())
            .isEqualTo(harness.originalResponse().authorizationReferenceFingerprint());
        assertThat(response.get().expiresAt()).isEqualTo(harness.originalResponse().expiresAt());
        assertThat(response.get().authorityEvidenceBundle())
            .containsExactly(harness.originalResponse().authorityEvidenceBundle());
        assertThat(response.get().bundleReference())
            .isEqualTo(harness.originalResponse().bundleReference());
      }

      assertThat(harness.environmentCaptures()).hasSize(3);
      assertThat(harness.events().indexOf("encrypt"))
          .isLessThan(harness.events().indexOf("environment-capture-2"));
      assertThat(harness.events().indexOf("environment-capture-2"))
          .isLessThan(harness.events().indexOf("issuance-race-lost"));
      assertThat(harness.events().indexOf("issuance-race-lost"))
          .isLessThan(harness.events().indexOf("decrypt"));
      assertThat(harness.events().indexOf("decrypt"))
          .isLessThan(harness.events().indexOf("environment-capture-3"));
      assertThat(harness.events().indexOf("environment-capture-3"))
          .isLessThan(harness.events().lastIndexOf("final-current"));
      assertThat(harness.randomCalls()).hasValue(1);
      verify(harness.repository()).createOrReadExact(any());
      verify(harness.responseCrypto()).encrypt(any(), any(), any());
      verify(harness.responseCrypto()).decrypt(any(), any(), any());
      verify(harness.captureRepository(), times(sourceChanges ? 1 : 2))
          .lockExactCurrent(
              eq(harness.capture()),
              any(),
              eq(harness.tuple()),
              eq(LOGGING_PEER),
              eq(harness.reservationOwnerId()),
              eq(10L));
    }
  }

  @Test
  void exactRepeatedReadReturnsOriginalRedeemedProjectionAfterCurrentnessRevalidation() {
    ReadHarness harness = readHarness(NOW.plusSeconds(30));
    allowCurrent(harness, harness.source());
    AtomicReference<RedeemedOperationProjection> first = new AtomicReference<>();
    AtomicReference<RedeemedOperationProjection> retry = new AtomicReference<>();
    AtomicReference<RedeemedOperationProjection> gameSessionRead = new AtomicReference<>();

    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(GAME_DESIGN_PEER).orElseThrow())
        .run(
            () -> {
              first.set(harness.service().readRedeemedOperationProjection(harness.request()));
              retry.set(harness.service().readRedeemedOperationProjection(harness.request()));
            });
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri(GAME_SESSION_PEER).orElseThrow())
        .run(
            () ->
                gameSessionRead.set(
                    harness.service().readRedeemedOperationProjection(harness.request())));

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
    assertThat(gameSessionRead.get().controlPlaneRequestId())
        .isEqualTo(original.controlPlaneRequestId());
    assertThat(gameSessionRead.get().canonicalPreAuthorizationTuple())
        .isEqualTo(original.canonicalPreAuthorizationTuple());
    assertThat(gameSessionRead.get().ownerAttemptId()).isEqualTo(original.ownerAttemptId());
    assertThat(gameSessionRead.get().ownerFence()).isEqualTo(original.ownerFence());
    assertThat(gameSessionRead.get().authorityEvidenceBundle())
        .isEqualTo(original.authorityEvidenceBundle());

    byte[] mutatedCopy = original.authorityEvidenceBundle();
    mutatedCopy[0] ^= 0x01;
    assertThat(original.authorityEvidenceBundle())
        .isEqualTo(harness.record().authorityEvidenceBundle());
    assertThat(original.toString()).doesNotContain("operatorAuthorizationReference");
    assertThat(original.toString()).doesNotContain("controlUiJwt");
    verify(harness.actors(), times(3))
        .withCurrentCommitted(
            eq(harness.actorId()),
            eq(harness.tenantId()),
            eq(harness.tokenJti()),
            eq(harness.environment()),
            any());
    verify(harness.repository(), times(6))
        .findByControlPlaneRequestId(harness.tuple().controlPlaneRequestId());
    verify(harness.repository(), never()).createOrReadExact(any());
    verify(harness.repository(), never()).redeemExact(any());
    verifyNoInteractions(
        harness.claims(),
        harness.fingerprintKeys(),
        harness.responseCrypto(),
        harness.captureRepository());
  }

  @Test
  void projectionRequiresExactSameNamespaceGameDesignOrOriginalGameSessionPeerBeforeLookup() {
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
            "spiffe://firemud/ns/test/sa/account-service",
            LOGGING_PEER,
            WORLD_MANAGEMENT_PEER)) {
      Context.current()
          .withValue(
              GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(wrongPeerUri).orElseThrow())
          .run(
              () ->
                  assertThatThrownBy(
                          () -> harness.service().readRedeemedOperationProjection(malformed))
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessageContaining(
                          "Exact authenticated Game Design or Game Session workload identity"));
    }
    Context.ROOT.run(
        () ->
            assertThatThrownBy(() -> harness.service().readRedeemedOperationProjection(malformed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                    "Exact authenticated Game Design or Game Session workload identity"));

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
    gameSessionContext()
        .run(
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
    for (Context peerContext : List.of(gameDesignContext(), gameSessionContext())) {
      for (ReadRedeemedOperationProjectionRequest request : requests) {
        peerContext.run(
            () ->
                assertThatThrownBy(() -> harness.service().readRedeemedOperationProjection(request))
                    .isInstanceOf(IllegalStateException.class));
      }
    }

    verify(harness.actors(), never()).withCurrentCommitted(any(), any(), any(), any(), any());

    ReadHarness expiredHarness = readHarness(NOW.minusSeconds(10));
    for (Context peerContext : List.of(gameDesignContext(), gameSessionContext())) {
      peerContext.run(
          () ->
              assertThatThrownBy(
                      () ->
                          expiredHarness
                              .service()
                              .readRedeemedOperationProjection(expiredHarness.request()))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("Stored redeemed operation"));
    }
    verify(expiredHarness.actors(), never())
        .withCurrentCommitted(any(), any(), any(), any(), any());
  }

  @Test
  void projectionRejectsRevokedOrChangedCurrentAccountSource() {
    ReadHarness harness = readHarness(NOW.plusSeconds(30));
    AccountControlUiAuthority.Snapshot changedSource =
        source(harness.actorId(), harness.tenantId(), 8L, new byte[] {2});
    allowCurrent(harness, changedSource);

    for (Context peerContext : List.of(gameDesignContext(), gameSessionContext())) {
      peerContext.run(
          () ->
              assertThatThrownBy(
                      () -> harness.service().readRedeemedOperationProjection(harness.request()))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("Current Account source"));
    }

    verify(harness.actors(), times(2))
        .withCurrentCommitted(
            eq(harness.actorId()),
            eq(harness.tenantId()),
            eq(harness.tokenJti()),
            eq(harness.environment()),
            any());
    verifyNoInteractions(
        harness.claims(),
        harness.fingerprintKeys(),
        harness.responseCrypto(),
        harness.captureRepository());
  }

  @Test
  void worldCurrentnessAuthenticatesExactPeerBeforeParsingAndDoesNotWidenProjectionRead() {
    ReadHarness harness = readHarness(NOW.plusSeconds(30));
    byte[] malformed = "not a canonical tuple".getBytes(StandardCharsets.UTF_8);

    for (String wrongPeerUri :
        List.of(
            "spiffe://firemud/ns/other/sa/world-management-service",
            GAME_DESIGN_PEER,
            GAME_SESSION_PEER,
            LOGGING_PEER,
            "spiffe://firemud/ns/test/sa/account-service")) {
      Context.current()
          .withValue(
              GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(wrongPeerUri).orElseThrow())
          .run(
              () ->
                  assertThatThrownBy(
                          () ->
                              harness
                                  .service()
                                  .withCurrentWorldReceivingParticipationCurrentness(
                                      malformed,
                                      harness.request().ownerAttemptId(),
                                      harness.request().ownerFence(),
                                      ignored -> "unexpected"))
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessageContaining("Exact authenticated internal workload identity"));
    }
    Context.ROOT.run(
        () ->
            assertThatThrownBy(
                    () ->
                        harness
                            .service()
                            .withCurrentWorldReceivingParticipationCurrentness(
                                malformed,
                                harness.request().ownerAttemptId(),
                                harness.request().ownerFence(),
                                ignored -> "unexpected"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Exact authenticated internal workload identity"));

    worldManagementContext()
        .run(
            () ->
                assertThatThrownBy(
                        () ->
                            harness
                                .service()
                                .withCurrentWorldReceivingParticipationCurrentness(
                                    malformed,
                                    harness.request().ownerAttemptId(),
                                    harness.request().ownerFence(),
                                    ignored -> "unexpected"))
                    .isInstanceOf(IllegalArgumentException.class));

    verify(harness.repository(), never()).findByControlPlaneRequestId(anyString());
    verify(harness.actors(), never()).withCurrentCommitted(any(), any(), any(), any(), any());
    verify(harness.hostedTerms(), never()).captureCurrentEnvironmentBoundary();
    verifyNoInteractions(harness.captureRepository());
  }

  @Test
  void worldCurrentnessRejectsTupleNamespaceDifferentFromAuthenticatedPeerBeforeLookup() {
    String loggingPeer = "spiffe://firemud/ns/logging-ns/sa/logging-admin-service";
    String gameSessionPeer = "spiffe://firemud/ns/world-ns/sa/game-session-service";
    ReadHarness harness = readHarness(NOW.plusSeconds(30), loggingPeer, gameSessionPeer);
    StartSessionPostAuthorizationExecutionTuple canonicalPostTuple = postTuple(harness);

    assertThat(canonicalPostTuple.preAuthorizationTuple().action().scope().targetNamespace())
        .isEqualTo("logging-ns");
    worldManagementContext("spiffe://firemud/ns/world-ns/sa/world-management-service")
        .run(
            () ->
                assertThatThrownBy(
                        () ->
                            harness
                                .service()
                                .withCurrentWorldReceivingParticipationCurrentness(
                                    canonicalPostTuple.canonicalBytes(),
                                    harness.request().ownerAttemptId(),
                                    harness.request().ownerFence(),
                                    ignored -> "unexpected"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                        "StartSession target namespace does not match the authenticated World peer"));

    verify(harness.repository(), never()).findByControlPlaneRequestId(anyString());
    verify(harness.actors(), never()).withCurrentCommitted(any(), any(), any(), any(), any());
    verify(harness.hostedTerms(), never()).captureCurrentEnvironmentBoundary();
    verifyNoInteractions(harness.captureRepository());
  }

  @Test
  void worldCurrentnessRejectsAmbientTransactionOrSynchronizationBeforeCurrentness() {
    ReadHarness transactionHarness = readHarness(NOW.plusSeconds(30));
    byte[] postTuple = postTuple(transactionHarness).canonicalBytes();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    worldManagementContext()
        .run(
            () ->
                assertThatThrownBy(
                        () ->
                            transactionHarness
                                .service()
                                .withCurrentWorldReceivingParticipationCurrentness(
                                    postTuple,
                                    transactionHarness.request().ownerAttemptId(),
                                    transactionHarness.request().ownerFence(),
                                    ignored -> "unexpected"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("without an ambient Account transaction"));
    TransactionSynchronizationManager.clear();

    ReadHarness synchronizationHarness = readHarness(NOW.plusSeconds(30));
    byte[] synchronizedPostTuple = postTuple(synchronizationHarness).canonicalBytes();
    TransactionSynchronizationManager.initSynchronization();
    worldManagementContext()
        .run(
            () ->
                assertThatThrownBy(
                        () ->
                            synchronizationHarness
                                .service()
                                .withCurrentWorldReceivingParticipationCurrentness(
                                    synchronizedPostTuple,
                                    synchronizationHarness.request().ownerAttemptId(),
                                    synchronizationHarness.request().ownerFence(),
                                    ignored -> "unexpected"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("without an ambient Account transaction"));

    verify(transactionHarness.repository(), never()).findByControlPlaneRequestId(anyString());
    verify(synchronizationHarness.repository(), never()).findByControlPlaneRequestId(anyString());
    verify(transactionHarness.hostedTerms(), never()).captureCurrentEnvironmentBoundary();
    verify(synchronizationHarness.hostedTerms(), never()).captureCurrentEnvironmentBoundary();
    verify(transactionHarness.actors(), never())
        .withCurrentCommitted(any(), any(), any(), any(), any());
    verify(synchronizationHarness.actors(), never())
        .withCurrentCommitted(any(), any(), any(), any(), any());
  }

  @Test
  void worldCurrentnessLocksExactCaptureAndRunsOnlyAccountCallbackInSameTransaction()
      throws Exception {
    ReadHarness harness = readHarness(NOW.plusSeconds(30));
    allowCurrent(harness, harness.source());
    AtomicReference<AccountStartSessionAuthorityCapture> callbackCapture = new AtomicReference<>();

    AccountStartSessionAuthorityCapture result =
        worldManagementContext()
            .call(
                () ->
                    harness
                        .service()
                        .withCurrentWorldReceivingParticipationCurrentness(
                            postTuple(harness).canonicalBytes(),
                            harness.request().ownerAttemptId(),
                            harness.request().ownerFence(),
                            lockedCapture -> {
                              assertThat(
                                      TransactionSynchronizationManager.isActualTransactionActive())
                                  .isTrue();
                              assertThat(lockedCapture).isEqualTo(harness.capture());
                              callbackCapture.set(lockedCapture);
                              return lockedCapture;
                            }));

    assertThat(result).isEqualTo(harness.capture());
    assertThat(callbackCapture.get()).isEqualTo(harness.capture());
    verify(harness.captureRepository())
        .lockReadExactCurrentFromWorldReceiving(
            any(),
            eq(harness.tuple()),
            eq(LOGGING_PEER),
            eq(harness.record().reservationOwnerId()),
            eq(harness.record().reservationClaimFence()),
            eq(WORLD_MANAGEMENT_PEER));
    verify(harness.captureRepository(), never())
        .prepareOrReadExact(any(), any(), anyString(), any(), anyLong());
    verify(harness.captureRepository(), never())
        .lockExactCurrent(any(), any(), any(), anyString(), any(), anyLong());
    verify(harness.repository(), never()).createOrReadExact(any());
    verify(harness.repository(), never()).redeemExact(any());
    verifyNoInteractions(harness.claims(), harness.fingerprintKeys(), harness.responseCrypto());
  }

  @Test
  void worldCurrentnessRejectsExpiredReferenceAndChangedCurrentSourceBeforeCallback() {
    ReadHarness expired = readHarness(NOW.minusSeconds(10));
    AtomicReference<Boolean> expiredCallback = new AtomicReference<>(false);
    worldManagementContext()
        .run(
            () ->
                assertThatThrownBy(
                        () ->
                            expired
                                .service()
                                .withCurrentWorldReceivingParticipationCurrentness(
                                    postTuple(expired).canonicalBytes(),
                                    expired.request().ownerAttemptId(),
                                    expired.request().ownerFence(),
                                    ignored -> {
                                      expiredCallback.set(true);
                                      return null;
                                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Stored redeemed operation"));
    assertThat(expiredCallback).hasValue(false);
    verify(expired.actors(), never()).withCurrentCommitted(any(), any(), any(), any(), any());
    verify(expired.captureRepository(), never())
        .lockReadExactCurrentFromWorldReceiving(
            any(), any(), anyString(), any(), anyLong(), anyString());

    ReadHarness changed = readHarness(NOW.plusSeconds(30));
    AccountControlUiAuthority.Snapshot changedSource =
        source(changed.actorId(), changed.tenantId(), 8L, new byte[] {2});
    allowCurrent(changed, changedSource);
    AtomicReference<Boolean> changedCallback = new AtomicReference<>(false);
    worldManagementContext()
        .run(
            () ->
                assertThatThrownBy(
                        () ->
                            changed
                                .service()
                                .withCurrentWorldReceivingParticipationCurrentness(
                                    postTuple(changed).canonicalBytes(),
                                    changed.request().ownerAttemptId(),
                                    changed.request().ownerFence(),
                                    ignored -> {
                                      changedCallback.set(true);
                                      return null;
                                    }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Current Account source"));
    assertThat(changedCallback).hasValue(false);
    verify(changed.captureRepository())
        .lockReadExactCurrentFromWorldReceiving(
            any(),
            eq(changed.tuple()),
            eq(LOGGING_PEER),
            any(),
            anyLong(),
            eq(WORLD_MANAGEMENT_PEER));
  }

  @Test
  void worldCurrentnessRejectsPostTupleFingerprintAndOwnerAttemptSubstitution() {
    ReadHarness harness = readHarness(NOW.plusSeconds(30));
    StartSessionPostAuthorizationExecutionTuple original = postTuple(harness);
    StartSessionPostAuthorizationExecutionTuple substitutedFingerprint =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            harness.tuple(),
            LOGGING_PEER,
            "arfp/v1/test-key/" + "b".repeat(64),
            harness.record().reservationOwnerId(),
            harness.record().reservationClaimFence(),
            harness.record().authorityEvidenceBundle(),
            harness.record().bundleReference());
    AtomicReference<Boolean> callback = new AtomicReference<>(false);

    worldManagementContext()
        .run(
            () ->
                assertThatThrownBy(
                        () ->
                            harness
                                .service()
                                .withCurrentWorldReceivingParticipationCurrentness(
                                    substitutedFingerprint.canonicalBytes(),
                                    harness.request().ownerAttemptId(),
                                    harness.request().ownerFence(),
                                    ignored -> {
                                      callback.set(true);
                                      return null;
                                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tuple differs from the original Account issuance"));

    worldManagementContext()
        .run(
            () ->
                assertThatThrownBy(
                        () ->
                            harness
                                .service()
                                .withCurrentWorldReceivingParticipationCurrentness(
                                    original.canonicalBytes(),
                                    UUID.randomUUID(),
                                    harness.request().ownerFence(),
                                    ignored -> {
                                      callback.set(true);
                                      return null;
                                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Stored redeemed operation"));

    assertThat(callback).hasValue(false);
    verify(harness.actors(), never()).withCurrentCommitted(any(), any(), any(), any(), any());
    verify(harness.captureRepository(), never())
        .lockReadExactCurrentFromWorldReceiving(
            any(), any(), anyString(), any(), anyLong(), anyString());
  }

  @Test
  void expiredOrFutureOriginalClaimEvidenceStopsBeforeCaptureAndPrivateCrypto() {
    MutableClock expiredClock = new MutableClock(NOW);
    IssueHarness expired = issueHarness(false, false, expiredClock, NOW.minusSeconds(1), NOW);
    loggingContext()
        .run(
            () ->
                assertThatThrownBy(() -> expired.service().issue(expired.issueRequest()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("claim evidence is stale or contradictory"));

    assertThat(expired.randomCalls()).hasValue(0);
    verify(expired.captureRepository(), never())
        .prepareOrReadExact(any(), any(), anyString(), any(), anyLong());
    verifyNoInteractions(expired.fingerprintKeys(), expired.responseCrypto());
    verify(expired.claims(), times(1))
        .readCurrentClaimEvidence(any(), any(), anyLong(), any(), anyLong(), eq(Purpose.ISSUE));

    MutableClock futureClock = new MutableClock(NOW);
    IssueHarness future =
        issueHarness(false, false, futureClock, NOW.plusSeconds(1), NOW.plusSeconds(30));
    loggingContext()
        .run(
            () ->
                assertThatThrownBy(() -> future.service().issue(future.issueRequest()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("claim evidence is stale or contradictory"));

    assertThat(future.randomCalls()).hasValue(0);
    verify(future.captureRepository(), never())
        .prepareOrReadExact(any(), any(), anyString(), any(), anyLong());
    verifyNoInteractions(future.fingerprintKeys(), future.responseCrypto());
    verify(future.claims(), times(1))
        .readCurrentClaimEvidence(any(), any(), anyLong(), any(), anyLong(), eq(Purpose.ISSUE));

    IssueHarness contradictory =
        issueHarness(false, false, new MutableClock(NOW), NOW.minusSeconds(1), NOW.minusSeconds(1));
    loggingContext()
        .run(
            () ->
                assertThatThrownBy(
                        () -> contradictory.service().issue(contradictory.issueRequest()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("claim evidence is stale or contradictory"));

    assertThat(contradictory.randomCalls()).hasValue(0);
    verify(contradictory.captureRepository(), never())
        .prepareOrReadExact(any(), any(), anyString(), any(), anyLong());
    verifyNoInteractions(contradictory.fingerprintKeys(), contradictory.responseCrypto());
    verify(contradictory.claims(), times(1))
        .readCurrentClaimEvidence(any(), any(), anyLong(), any(), anyLong(), eq(Purpose.ISSUE));
  }

  @Test
  void originalClaimExpiryDuringEncryptionDeniesAtFinalCaptureLockBeforeInsert() {
    MutableClock clock = new MutableClock(NOW);
    IssueHarness harness =
        issueHarness(false, false, clock, NOW.minusSeconds(1), NOW.plusSeconds(30));
    doAnswer(
            invocation -> {
              harness.events().add("encrypt");
              clock.set(NOW.plusSeconds(30));
              return new AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope(
                  new byte[] {3, 4, 5});
            })
        .when(harness.responseCrypto())
        .encrypt(any(), any(), any());

    loggingContext()
        .run(
            () ->
                assertThatThrownBy(() -> harness.service().issue(harness.issueRequest()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("claim evidence is stale or contradictory"));

    assertThat(harness.randomCalls()).hasValue(1);
    verify(harness.captureRepository())
        .prepareOrReadExact(
            any(),
            eq(harness.tuple()),
            eq(LOGGING_PEER),
            eq(harness.reservationOwnerId()),
            eq(10L));
    verify(harness.captureRepository())
        .lockExactCurrent(
            eq(harness.capture()),
            eq(harness.current()),
            eq(harness.tuple()),
            eq(LOGGING_PEER),
            eq(harness.reservationOwnerId()),
            eq(10L));
    verify(harness.repository(), never()).createOrReadExact(any());
    assertThat(harness.events()).contains("capture-lock").doesNotContain("issuance-row-insert");
    verify(harness.claims(), times(1))
        .readCurrentClaimEvidence(any(), any(), anyLong(), any(), anyLong(), eq(Purpose.ISSUE));
  }

  @Test
  void claimExpiryDuringCapturePreparationStopsBeforeRandomFingerprintOrEncryption() {
    MutableClock clock = new MutableClock(NOW);
    IssueHarness harness =
        issueHarness(false, false, clock, NOW.minusSeconds(1), NOW.plusSeconds(30));
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              harness.events().add("capture-persisted");
              clock.set(NOW.plusSeconds(30));
              return harness.capture();
            })
        .when(harness.captureRepository())
        .prepareOrReadExact(
            any(),
            eq(harness.tuple()),
            eq(LOGGING_PEER),
            eq(harness.reservationOwnerId()),
            eq(10L));

    loggingContext()
        .run(
            () ->
                assertThatThrownBy(() -> harness.service().issue(harness.issueRequest()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("claim evidence is stale or contradictory"));

    assertThat(harness.randomCalls()).hasValue(0);
    verify(harness.captureRepository())
        .prepareOrReadExact(
            any(),
            eq(harness.tuple()),
            eq(LOGGING_PEER),
            eq(harness.reservationOwnerId()),
            eq(10L));
    verify(harness.captureRepository(), never())
        .lockExactCurrent(any(), any(), any(), anyString(), any(), anyLong());
    verify(harness.repository(), never()).createOrReadExact(any());
    verifyNoInteractions(harness.fingerprintKeys(), harness.responseCrypto());
    verify(harness.claims(), times(1))
        .readCurrentClaimEvidence(any(), any(), anyLong(), any(), anyLong(), eq(Purpose.ISSUE));
  }

  private static IssueHarness issueHarness(boolean captureFails, boolean existing) {
    return issueHarness(
        captureFails, existing, new MutableClock(NOW), NOW.minusSeconds(1), NOW.plusSeconds(30));
  }

  private static IssueHarness issueHarness(
      boolean captureFails,
      boolean existing,
      MutableClock clock,
      Instant observedAt,
      Instant claimExpiresAt) {
    UUID actorId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID reservationOwnerId = UUID.randomUUID();
    UUID claimOwnerId = reservationOwnerId;
    UUID tokenJti = UUID.randomUUID();
    UUID originalOperationId = UUID.randomUUID();
    StartSessionPreAuthorizationReservationTuple tuple = tuple(actorId, tenantId);
    AccountControlUiAuthority.Snapshot source = source(actorId, tenantId, 7L, new byte[] {1});
    AccountControlUiIssuanceRepository.Stored stored =
        stored(actorId, tenantId, tokenJti, originalOperationId, source);
    Current current = new Current(stored, source);
    AccountStartSessionAuthorityCapture capture =
        capture(tuple, source, stored, reservationOwnerId, 10L);
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
    List<AccountHostedTermsService.CapturedEnvironmentBoundary> environmentCaptures =
        new java.util.ArrayList<>();
    AtomicReference<Current> finalCurrent = new AtomicReference<>(current);
    List<String> events = new java.util.ArrayList<>();
    AtomicInteger randomCalls = new AtomicInteger();
    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(ignored -> new SimpleTransactionStatus());
    when(hostedTerms.captureCurrentEnvironmentBoundary())
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              var environment =
                  org.mockito.Mockito.mock(
                      AccountHostedTermsService.CapturedEnvironmentBoundary.class);
              environmentCaptures.add(environment);
              events.add("environment-capture-" + environmentCaptures.size());
              return environment;
            });

    AccountStartSessionOperatorAuthorityBundle originalBundle =
        AccountStartSessionOperatorAuthorityBundle.create(
            tuple, capture, stored, originalOperationId, NOW.minusSeconds(1), NOW.plusSeconds(60));
    String originalFingerprint = "arfp/v1/test-key/" + "a".repeat(64);
    when(fingerprintKeys.matchesOriginal(
            any(ReferenceKind.class),
            any(byte[].class),
            anyString(),
            any(Instant.class),
            any(Instant.class)))
        .thenAnswer(
            invocation -> {
              assertThat(invocation.<ReferenceKind>getArgument(0))
                  .isEqualTo(ReferenceKind.HUMAN_OPERATOR);
              assertThat(invocation.<byte[]>getArgument(1))
                  .containsExactly("A".repeat(43).getBytes(StandardCharsets.US_ASCII));
              assertThat(invocation.<String>getArgument(2)).isEqualTo(originalFingerprint);
              assertThat(invocation.<Instant>getArgument(3)).isEqualTo(NOW.plusSeconds(60));
              assertThat(invocation.<Instant>getArgument(4)).isEqualTo(NOW.plusSeconds(80));
              return true;
            });
    AuthorizationFixture original =
        originalAuthorizationFixture(
            tuple,
            reservationOwnerId,
            originalOperationId,
            capture,
            originalBundle,
            originalFingerprint);
    when(repository.findByControlPlaneRequestId(tuple.controlPlaneRequestId()))
        .thenReturn(existing ? Optional.of(original.record()) : Optional.empty());
    when(claims.readCurrentClaimEvidence(any(), any(), anyLong(), any(), anyLong(), any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              events.add("claim-read");
              return claimEvidence(
                  tuple,
                  reservationOwnerId,
                  claimOwnerId,
                  11L,
                  (Purpose) invocation.getArgument(5),
                  observedAt,
                  claimExpiresAt);
            });
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(invocation.<Current>getArgument(0)).isSameAs(current);
              events.add("capture-persisted");
              if (captureFails) throw new IllegalStateException("capture unavailable");
              return capture;
            })
        .when(captureRepository)
        .prepareOrReadExact(any(), eq(tuple), eq(LOGGING_PEER), eq(reservationOwnerId), eq(10L));
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              events.add("capture-read-lock");
              return capture;
            })
        .when(captureRepository)
        .lockReadExactCurrent(any(), eq(tuple), eq(LOGGING_PEER), eq(reservationOwnerId), eq(10L));
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(invocation.<AccountStartSessionAuthorityCapture>getArgument(0))
                  .isEqualTo(capture);
              events.add("capture-lock");
              return capture;
            })
        .when(captureRepository)
        .lockExactCurrent(
            any(), any(), eq(tuple), eq(LOGGING_PEER), eq(reservationOwnerId), eq(10L));
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              events.add("fingerprint");
              return "arfp/v1/test-key/" + "b".repeat(64);
            })
        .when(fingerprintKeys)
        .fingerprintForIssue(any(), any());
    when(responseCrypto.encrypt(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              events.add("encrypt");
              return new AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope(
                  new byte[] {3, 4, 5});
            });
    when(responseCrypto.decrypt(any(), any(), any()))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              events.add("decrypt");
              return original.responseBytes();
            });
    // The supplied RNG is kept concrete so the test observes the private random call boundary.
    var secureRandom =
        new java.security.SecureRandom() {
          @Override
          public void nextBytes(byte[] bytes) {
            randomCalls.incrementAndGet();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            events.add("random");
            java.util.Arrays.fill(bytes, (byte) 0x5a);
          }
        };
    doAnswer(
            invocation -> {
              assertThat(
                      invocation.<AccountHostedTermsService.CapturedEnvironmentBoundary>getArgument(
                          2))
                  .isSameAs(environmentCaptures.get(environmentCaptures.size() - 1));
              TransactionSynchronizationManager.setActualTransactionActive(true);
              Object result;
              try {
                @SuppressWarnings("unchecked")
                Function<Current, Object> action = invocation.getArgument(3);
                result = action.apply(current);
              } finally {
                TransactionSynchronizationManager.clear();
              }
              events.add("initial-current-returned");
              return result;
            })
        .when(actors)
        .withCurrent(anyString(), any(), any(), any());
    doAnswer(
            invocation -> {
              assertThat(
                      invocation.<AccountHostedTermsService.CapturedEnvironmentBoundary>getArgument(
                          3))
                  .isSameAs(environmentCaptures.get(environmentCaptures.size() - 1));
              events.add("final-current");
              TransactionSynchronizationManager.setActualTransactionActive(true);
              try {
                @SuppressWarnings("unchecked")
                Function<Current, Object> action = invocation.getArgument(4);
                return action.apply(finalCurrent.get());
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
            clock,
            secureRandom,
            LOGGING_PEER,
            GAME_SESSION_PEER,
            java.time.Duration.ofSeconds(60),
            java.time.Duration.ofSeconds(20));
    var issueRequest =
        new AccountStartSessionOperatorAuthorizationService.IssueRequest(
            "test-only-control-ui-credential",
            tuple.canonicalJson().getBytes(StandardCharsets.UTF_8),
            reservationOwnerId,
            10L,
            claimOwnerId,
            11L);
    var recoverRequest =
        new AccountStartSessionOperatorAuthorizationService.RecoverRequest(
            tuple.canonicalJson().getBytes(StandardCharsets.UTF_8),
            reservationOwnerId,
            10L,
            claimOwnerId,
            11L);

    if (!existing) {
      when(repository.createOrReadExact(any()))
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                events.add("issuance-row-insert");
                var candidate =
                    (AccountStartSessionOperatorAuthorizationRepository.IssuanceCandidate)
                        invocation.getArgument(0);
                return new AccountStartSessionOperatorAuthorizationRepository.CreateOrReadResult(
                    true, issuedRecord(candidate));
              });
    }
    return new IssueHarness(
        service,
        actors,
        issueRequest,
        recoverRequest,
        events,
        randomCalls,
        claims,
        repository,
        captureRepository,
        fingerprintKeys,
        responseCrypto,
        capture,
        current,
        finalCurrent,
        environmentCaptures,
        tuple,
        reservationOwnerId,
        original.response(),
        original.responseBytes(),
        original.record());
  }

  private static AuthorizationFixture originalAuthorizationFixture(
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID reservationOwnerId,
      UUID operationId,
      AccountStartSessionAuthorityCapture capture,
      AccountStartSessionOperatorAuthorityBundle bundle,
      String fingerprint) {
    Instant issuedAt = NOW.minusSeconds(1);
    Instant referenceExpiresAt = NOW.plusSeconds(60);
    Instant recoveryExpiresAt = NOW.plusSeconds(80);
    String reference = "A".repeat(43);
    Map<String, Object> responseValue =
        Map.of(
            "operatorAuthorizationReference", reference,
            "authorizationReferenceFingerprint", fingerprint,
            "expiresAt", referenceExpiresAt.toString(),
            "authorityEvidenceBundle", bundle.jsonValue(),
            "bundleReference", bundle.referenceForCapture(capture).asJsonValue());
    byte[] responseBytes = AccountControlUiAuthority.canonical(responseValue);
    var response =
        new AccountStartSessionOperatorAuthorizationService.AuthorizationResponse(
            reference,
            fingerprint,
            referenceExpiresAt,
            bundle.canonicalBytes(),
            bundle.referenceForCapture(capture));
    var record =
        new IssuanceRecord(
            tuple.controlPlaneRequestId(),
            tuple.canonicalJson().getBytes(StandardCharsets.UTF_8),
            tuple.mutationDigest(),
            LOGGING_PEER,
            reservationOwnerId,
            10L,
            operationId,
            bundle.issuanceFence(),
            AccountStartSessionOperatorAuthorityBundle.toSharedReference(
                bundle.referenceForCapture(capture)),
            bundle.canonicalBytes(),
            fingerprint,
            new byte[] {1, 2, 3},
            issuedAt,
            referenceExpiresAt,
            recoveryExpiresAt,
            Status.ISSUED,
            null,
            null,
            null,
            null,
            null,
            null);
    return new AuthorizationFixture(response, responseBytes, record);
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
        Status.ISSUED,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private record AuthorizationFixture(
      AccountStartSessionOperatorAuthorizationService.AuthorizationResponse response,
      byte[] responseBytes,
      IssuanceRecord record) {
    private AuthorizationFixture {
      responseBytes = responseBytes.clone();
    }

    @Override
    public byte[] responseBytes() {
      return responseBytes.clone();
    }
  }

  private record IssueHarness(
      AccountStartSessionOperatorAuthorizationService service,
      AccountControlUiActorService actors,
      AccountStartSessionOperatorAuthorizationService.IssueRequest issueRequest,
      AccountStartSessionOperatorAuthorizationService.RecoverRequest recoverRequest,
      List<String> events,
      AtomicInteger randomCalls,
      StartSessionReservationEvidenceClient claims,
      AccountStartSessionOperatorAuthorizationRepository repository,
      AccountStartSessionAuthorityCaptureRepository captureRepository,
      AccountOperatorAuthorizationFingerprintKeyring fingerprintKeys,
      AccountResponseEnvelopeCryptography responseCrypto,
      AccountStartSessionAuthorityCapture capture,
      Current current,
      AtomicReference<Current> finalCurrent,
      List<AccountHostedTermsService.CapturedEnvironmentBoundary> environmentCaptures,
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID reservationOwnerId,
      AccountStartSessionOperatorAuthorizationService.AuthorizationResponse originalResponse,
      byte[] originalResponseBytes,
      IssuanceRecord originalRecord) {
    private IssueHarness {
      originalResponseBytes = originalResponseBytes.clone();
    }

    @Override
    public byte[] originalResponseBytes() {
      return originalResponseBytes.clone();
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> current;

    private MutableClock(Instant initial) {
      current = new AtomicReference<>(initial);
    }

    void set(Instant next) {
      current.set(next);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current.get();
    }

    @Override
    public long millis() {
      return current.get().toEpochMilli();
    }
  }

  private static byte[] fill(int length, byte value) {
    byte[] bytes = new byte[length];
    java.util.Arrays.fill(bytes, value);
    return bytes;
  }

  private static ReadHarness readHarness(Instant referenceExpiresAt) {
    return readHarness(referenceExpiresAt, LOGGING_PEER, GAME_SESSION_PEER);
  }

  private static ReadHarness readHarness(
      Instant referenceExpiresAt, String exactLoggingPeerUri, String exactGameSessionPeerUri) {
    UUID actorId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID reservationOwnerId = UUID.randomUUID();
    UUID ownerAttemptId = UUID.randomUUID();
    UUID issuanceOperationId = UUID.randomUUID();
    UUID tokenJti = UUID.randomUUID();
    String targetNamespace =
        GrpcPeerIdentity.parseUri(exactLoggingPeerUri).orElseThrow().namespace();
    StartSessionPreAuthorizationReservationTuple tuple = tuple(actorId, tenantId, targetNamespace);
    AccountControlUiAuthority.Snapshot source = source(actorId, tenantId, 7L, new byte[] {1});
    AccountControlUiIssuanceRepository.Stored stored =
        stored(actorId, tenantId, tokenJti, issuanceOperationId, source);
    Instant issuedAt = referenceExpiresAt.minusSeconds(50);
    Instant redeemedAt =
        referenceExpiresAt.isAfter(NOW) ? NOW.minusSeconds(1) : referenceExpiresAt.minusSeconds(1);
    var capture =
        capture(
            tuple,
            source,
            stored,
            reservationOwnerId,
            10L,
            issuedAt.minusSeconds(1),
            exactLoggingPeerUri);
    assertThat(Instant.parse(capture.capturedAt())).isBefore(issuedAt).isBefore(referenceExpiresAt);
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
            exactLoggingPeerUri,
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
            Status.REDEEMED,
            exactGameSessionPeerUri,
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
    when(hostedTerms.captureCurrentEnvironmentBoundary())
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              return environment;
            });
    when(repository.findByControlPlaneRequestId(tuple.controlPlaneRequestId()))
        .thenReturn(Optional.of(record));
    String exactWorldManagementPeerUri =
        "spiffe://firemud/ns/"
            + GrpcPeerIdentity.parseUri(exactGameSessionPeerUri).orElseThrow().namespace()
            + "/sa/world-management-service";
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(invocation.<Current>getArgument(0).stored()).isSameAs(stored);
              return capture;
            })
        .when(captureRepository)
        .lockReadExactCurrent(
            any(), eq(tuple), eq(exactLoggingPeerUri), eq(reservationOwnerId), eq(10L));
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(invocation.<Current>getArgument(0).stored()).isSameAs(stored);
              assertThat(invocation.<String>getArgument(2)).isEqualTo(exactLoggingPeerUri);
              assertThat(invocation.<String>getArgument(5)).isEqualTo(exactWorldManagementPeerUri);
              return capture;
            })
        .when(captureRepository)
        .lockReadExactCurrentFromWorldReceiving(
            any(),
            eq(tuple),
            eq(exactLoggingPeerUri),
            eq(reservationOwnerId),
            eq(10L),
            eq(exactWorldManagementPeerUri));
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
            exactLoggingPeerUri,
            exactGameSessionPeerUri,
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
        capture,
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

  private static Context loggingContext() {
    return Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(LOGGING_PEER).orElseThrow());
  }

  private static Context gameSessionContext() {
    return Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri(GAME_SESSION_PEER).orElseThrow());
  }

  private static Context worldManagementContext() {
    return worldManagementContext(WORLD_MANAGEMENT_PEER);
  }

  private static Context worldManagementContext(String worldPeerUri) {
    return Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(worldPeerUri).orElseThrow());
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
      AccountStartSessionAuthorityCapture capture,
      AccountControlUiIssuanceRepository.Stored stored,
      IssuanceRecord record,
      ReadRedeemedOperationProjectionRequest request,
      UUID actorId,
      UUID tenantId,
      UUID tokenJti) {}

  private static StartSessionPostAuthorizationExecutionTuple postTuple(ReadHarness harness) {
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        harness.tuple(),
        harness.record().issuanceWorkloadUri(),
        harness.request().authorizationReferenceFingerprint(),
        harness.request().reservationOwnerId(),
        harness.request().reservationClaimFence(),
        harness.record().authorityEvidenceBundle(),
        harness.record().bundleReference());
  }

  private static StartSessionPreAuthorizationReservationTuple tuple(UUID actorId, UUID tenantId) {
    return tuple(actorId, tenantId, "test");
  }

  private static StartSessionPreAuthorizationReservationTuple tuple(
      UUID actorId, UUID tenantId, String targetNamespace) {
    var action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(tenantId, targetNamespace),
            new StartSessionOperatorAction.Target(1L, actorId),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "authorization test");
    return StartSessionPreAuthorizationReservationTuple.createHuman(
        "request-" + UUID.randomUUID(), actorId, action);
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
    return capture(
        tuple, source, stored, reservationOwnerId, reservationClaimFence, capturedAt, LOGGING_PEER);
  }

  private static AccountStartSessionAuthorityCapture capture(
      StartSessionPreAuthorizationReservationTuple tuple,
      AccountControlUiAuthority.Snapshot source,
      AccountControlUiIssuanceRepository.Stored stored,
      UUID reservationOwnerId,
      long reservationClaimFence,
      Instant capturedAt,
      String loggingWorkloadUri) {
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
            Map.entry("loggingWorkloadUri", loggingWorkloadUri),
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
    when(source.issuanceFence()).thenReturn(sourceVersion * 10L);
    when(source.issuanceFenceSourceVersion()).thenReturn(sourceVersion + 100L);
    when(source.evidence()).thenReturn(evidence.clone());
    when(source.outboxCheckpoints())
        .thenReturn(List.of(Map.of("stream", "account", "sequence", 1L)));
    when(source.accountIdentitySource())
        .thenReturn(Map.of("accountId", actorId.toString(), "sourceVersion", sourceVersion));
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
    return claimEvidence(
        tuple, ownerId, ownerId, 10L, Purpose.ISSUE, NOW.minusSeconds(1), NOW.plusSeconds(30));
  }

  private static ReadCurrentClaimEvidenceResponse claimEvidence(
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID reservationOwnerId,
      UUID claimOwnerId,
      long claimFence,
      Purpose purpose,
      Instant observedAt,
      Instant claimExpiresAt) {
    return ReadCurrentClaimEvidenceResponse.newBuilder()
        .setControlPlaneRequestId(tuple.controlPlaneRequestId())
        .setPreAuthorizationTupleJson(
            ByteString.copyFrom(tuple.canonicalJson(), StandardCharsets.UTF_8))
        .setMutationDigest(tuple.mutationDigest())
        .setReservationOwnerId(reservationOwnerId.toString())
        .setReservationClaimFence(10L)
        .setClaimOwnerId(claimOwnerId.toString())
        .setClaimFence(claimFence)
        .setObservedAtEpochMillis(observedAt.toEpochMilli())
        .setClaimExpiresAtEpochMillis(claimExpiresAt.toEpochMilli())
        .setPurpose(
            purpose == Purpose.ISSUE
                ? StartSessionReservationEvidencePurpose
                    .START_SESSION_RESERVATION_EVIDENCE_PURPOSE_ISSUE
                : StartSessionReservationEvidencePurpose
                    .START_SESSION_RESERVATION_EVIDENCE_PURPOSE_RECOVER)
        .build();
  }
}
