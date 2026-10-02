package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.CaptureIssuerProjectionForRuntimeRequest;
import net.firedevops.firemud.account.v1.CaptureIssuerProjectionForRuntimeResponse;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeRequest;
import net.firedevops.firemud.account.v1.ReadIssuerAuthorityForRuntimeResponse;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository.Receipt;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthorityEventReadback;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityGrpcService;
import net.firedevops.firemud.accountservice.service.AccountIssuerProjectionReconciliationService;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

class AccountIssuerAuthorityGrpcServiceTest {
  private static final String ISSUER_ID = "https://account.example.test/issuer";
  private static final String SOURCE_SCOPE = "issuer/" + ISSUER_ID;
  private static final String STREAM_KEY = "account:auth-authority:v1:" + SOURCE_SCOPE;
  private static final String PROJECTION_KEY =
      "session:game:auth:issuer-generation:v1:" + ISSUER_ID;
  private static final String NAMESPACE = "firemud-test";
  private static final String GAME_SESSION_URI =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service";
  private static final String REQUEST_ID = "f50e8400-e29b-41d4-a716-446655440000";
  private static final UUID OPERATION_ID = UUID.fromString("91b0eb2d-f7b1-4d88-8db4-2b81cacfc96a");
  private static final GrpcPeerIdentity GAME_SESSION_PEER =
      new GrpcPeerIdentity(GAME_SESSION_URI, NAMESPACE, "game-session-service");

  @Test
  void constructorRequiresProducerAndValidWorkloadNamespace() {
    assertThatThrownBy(
            () -> new AccountIssuerAuthorityGrpcService(null, mockCaptureService(), NAMESPACE))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("producer");
    assertThatThrownBy(() -> new AccountIssuerAuthorityGrpcService(mockProducer(), null, NAMESPACE))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("reconciliation service");
    assertThatThrownBy(
            () ->
                new AccountIssuerAuthorityGrpcService(
                    mockProducer(), mockCaptureService(), "Invalid_Namespace"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("namespace");
  }

  @Test
  void exactCertificateDerivedCallerIsCheckedBeforeAnySourceAccess() {
    AccountIssuerAuthorityEventProducer producer = mockProducer();
    AccountIssuerAuthorityGrpcService service = newService(producer);
    ReadIssuerAuthorityForRuntimeRequest request = validRequest();

    Outcome<ReadIssuerAuthorityForRuntimeResponse> wrongService =
        invoke(
            service,
            request,
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service",
                NAMESPACE,
                "account-service"));
    assertFailure(wrongService, Status.Code.PERMISSION_DENIED);

    Outcome<ReadIssuerAuthorityForRuntimeResponse> wrongNamespace =
        invoke(
            service,
            request,
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/other-test/sa/game-session-service",
                "other-test",
                "game-session-service"));
    assertFailure(wrongNamespace, Status.Code.PERMISSION_DENIED);

    verifyNoInteractions(producer);
  }

  @Test
  void missingCallerIsDeniedBeforeRequestParsingOrSourceAccess() {
    AccountIssuerAuthorityEventProducer producer = mockProducer();
    AccountIssuerAuthorityGrpcService service = newService(producer);
    ReadIssuerAuthorityForRuntimeRequest malformed =
        ReadIssuerAuthorityForRuntimeRequest.newBuilder().setIssuerId(" ").build();

    Outcome<ReadIssuerAuthorityForRuntimeResponse> outcome = invokeWithoutPeer(service, malformed);

    assertFailure(outcome, Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(producer);
  }

  @Test
  void unknownFieldsMalformedRequestIdentityAndInvalidSelectorsFailBeforeSourceAccess() {
    AccountIssuerAuthorityEventProducer producer = mockProducer();
    AccountIssuerAuthorityGrpcService service = newService(producer);
    ReadIssuerAuthorityForRuntimeRequest valid = validRequest();
    List<ReadIssuerAuthorityForRuntimeRequest> invalidRequests =
        List.of(
            valid.toBuilder()
                .setUnknownFields(
                    com.google.protobuf.UnknownFieldSet.newBuilder()
                        .addField(
                            99,
                            com.google.protobuf.UnknownFieldSet.Field.newBuilder()
                                .addVarint(1L)
                                .build())
                        .build())
                .build(),
            valid.toBuilder().setRequestId("F50E8400-E29B-41D4-A716-446655440000").build(),
            valid.toBuilder().setRequestId("00000000-0000-0000-0000-000000000000").build(),
            valid.toBuilder().setRequestId("f50e8400-e29b-41d4-a716-446655440000 ").build(),
            valid.toBuilder().setIssuerId("  ").build(),
            valid.toBuilder().setRequestedOutboxSequence("0").build(),
            valid.toBuilder().setRequestedOutboxSequence("01").build(),
            valid.toBuilder().setRequestedOutboxSequence("+1").build(),
            valid.toBuilder().setRequestedOutboxSequence("-1").build(),
            valid.toBuilder().setRequestedOutboxSequence("9223372036854775808").build());

    for (ReadIssuerAuthorityForRuntimeRequest invalidRequest : invalidRequests) {
      Outcome<ReadIssuerAuthorityForRuntimeResponse> outcome =
          invoke(service, invalidRequest, GAME_SESSION_PEER);
      assertFailure(outcome, Status.Code.INVALID_ARGUMENT);
    }

    verifyNoInteractions(producer);
  }

  @Test
  void canonicalBaselineCurrentReadOmitsAllEventFields() {
    AccountIssuerAuthorityEventProducer producer = mockProducer();
    IssuerAuthoritySnapshot baseline = newSnapshot(1L, 1L, 0L, Optional.empty());
    when(producer.readCurrent(ISSUER_ID)).thenReturn(baseline);
    AccountIssuerAuthorityGrpcService service = newService(producer);

    Outcome<ReadIssuerAuthorityForRuntimeResponse> outcome =
        invoke(service, validRequest(), GAME_SESSION_PEER);

    ReadIssuerAuthorityForRuntimeResponse response = assertSuccess(outcome);
    assertThat(response.getSchemaVersion()).isEqualTo("account-auth-issuer-source-readback/v1");
    assertThat(response.getTargetNamespace()).isEqualTo(NAMESPACE);
    assertThat(response.getRequestId()).isEqualTo(REQUEST_ID);
    assertThat(response.hasRequestedEventCanonicalJson()).isFalse();
    assertThat(response.getSourceSnapshot().getIssuerId()).isEqualTo(ISSUER_ID);
    assertThat(response.getSourceSnapshot().getSourceScope()).isEqualTo(SOURCE_SCOPE);
    assertThat(response.getSourceSnapshot().getOutboxStreamKey()).isEqualTo(STREAM_KEY);
    assertThat(response.getSourceSnapshot().getIssuerAuthGeneration()).isEqualTo("1");
    assertThat(response.getSourceSnapshot().getSourceVersion()).isEqualTo("1");
    assertThat(response.getSourceSnapshot().getOutboxSequence()).isEqualTo("0");
    assertThat(response.getSourceSnapshot().hasLatestEventCanonicalJson()).isFalse();
    verify(producer).readCurrent(ISSUER_ID);
  }

  @Test
  void positiveCurrentReadReturnsExactLatestCanonicalEvent() {
    AccountIssuerAuthorityEventProducer producer = mockProducer();
    IssuerGenerationAuthorityEvent latest = event(REQUEST_ID, 1L, 2L, 2L);
    IssuerAuthoritySnapshot current = newSnapshot(2L, 2L, 1L, Optional.of(latest));
    when(producer.readCurrent(ISSUER_ID)).thenReturn(current);
    AccountIssuerAuthorityGrpcService service = newService(producer);

    Outcome<ReadIssuerAuthorityForRuntimeResponse> outcome =
        invoke(service, validRequest(), GAME_SESSION_PEER);

    ReadIssuerAuthorityForRuntimeResponse response = assertSuccess(outcome);
    assertThat(response.getSourceSnapshot().hasLatestEventCanonicalJson()).isTrue();
    assertThat(response.getSourceSnapshot().getLatestEventCanonicalJson())
        .isEqualTo(latest.canonicalJson());
    assertThat(response.hasRequestedEventCanonicalJson()).isFalse();
    verify(producer).readCurrent(ISSUER_ID);
  }

  @Test
  void historicalReadPreservesCompleteSelectedEventAndCurrentCheckpointSeparately() {
    AccountIssuerAuthorityEventProducer producer = mockProducer();
    IssuerGenerationAuthorityEvent requested =
        event("f50e8400-e29b-41d4-a716-446655440001", 1L, 2L, 2L);
    IssuerGenerationAuthorityEvent latest =
        event("f50e8400-e29b-41d4-a716-446655440002", 2L, 3L, 3L);
    IssuerAuthoritySnapshot current = newSnapshot(3L, 3L, 2L, Optional.of(latest));
    when(producer.readCommittedEvent(ISSUER_ID, 1L))
        .thenReturn(new IssuerAuthorityEventReadback(current, requested));
    AccountIssuerAuthorityGrpcService service = newService(producer);
    ReadIssuerAuthorityForRuntimeRequest request =
        validRequest().toBuilder().setRequestedOutboxSequence("1").build();

    Outcome<ReadIssuerAuthorityForRuntimeResponse> outcome =
        invoke(service, request, GAME_SESSION_PEER);

    ReadIssuerAuthorityForRuntimeResponse response = assertSuccess(outcome);
    assertThat(response.getRequestId()).isEqualTo(REQUEST_ID);
    assertThat(response.getSourceSnapshot().getOutboxSequence()).isEqualTo("2");
    assertThat(response.getSourceSnapshot().getLatestEventCanonicalJson())
        .isEqualTo(latest.canonicalJson());
    assertThat(response.hasRequestedEventCanonicalJson()).isTrue();
    assertThat(response.getRequestedEventCanonicalJson()).isEqualTo(requested.canonicalJson());
    assertThat(response.getRequestedEventCanonicalJson())
        .contains("\"eventId\":\"account-issuer-authority-event-v1:")
        .contains("\"eventDigest\":\"sha256:")
        .doesNotContain("authorityTuple", "issuanceFence", "outboxCheckpoints");
    verify(producer).readCommittedEvent(ISSUER_ID, 1L);
  }

  @Test
  void wrongHistoricalEventSelectorFailsWithoutPartialContent() {
    AccountIssuerAuthorityEventProducer producer = mockProducer();
    IssuerGenerationAuthorityEvent selected = event(REQUEST_ID, 2L, 3L, 3L);
    IssuerGenerationAuthorityEvent latest = event(REQUEST_ID, 2L, 3L, 3L);
    IssuerAuthoritySnapshot current = newSnapshot(3L, 3L, 2L, Optional.of(latest));
    when(producer.readCommittedEvent(ISSUER_ID, 1L))
        .thenReturn(new IssuerAuthorityEventReadback(current, selected));
    AccountIssuerAuthorityGrpcService service = newService(producer);

    Outcome<ReadIssuerAuthorityForRuntimeResponse> outcome =
        invoke(
            service,
            validRequest().toBuilder().setRequestedOutboxSequence("1").build(),
            GAME_SESSION_PEER);

    assertFailure(outcome, Status.Code.FAILED_PRECONDITION);
  }

  @Test
  void ownerContradictionAndTransientAccessFailureReturnTerminalSanitizedStatuses() {
    AccountIssuerAuthorityEventProducer contradictoryProducer = mockProducer();
    when(contradictoryProducer.readCurrent(ISSUER_ID))
        .thenThrow(new IllegalStateException("internal source payload details"));
    AccountIssuerAuthorityGrpcService contradictoryService = newService(contradictoryProducer);
    Outcome<ReadIssuerAuthorityForRuntimeResponse> contradiction =
        invoke(contradictoryService, validRequest(), GAME_SESSION_PEER);
    assertFailure(contradiction, Status.Code.FAILED_PRECONDITION);
    assertThat(contradiction.error().getDescription())
        .doesNotContain("internal source payload details");

    AccountIssuerAuthorityEventProducer transientProducer = mockProducer();
    when(transientProducer.readCurrent(ISSUER_ID))
        .thenThrow(new TransientDataAccessResourceException("database access detail"));
    AccountIssuerAuthorityGrpcService transientService = newService(transientProducer);
    Outcome<ReadIssuerAuthorityForRuntimeResponse> unavailable =
        invoke(transientService, validRequest(), GAME_SESSION_PEER);
    assertFailure(unavailable, Status.Code.UNAVAILABLE);
    assertThat(unavailable.error().getDescription()).doesNotContain("database access detail");
  }

  @Test
  void exactConfiguredIssuerRejectionIsInvalidArgument() {
    AccountIssuerAuthorityEventProducer producer = mockProducer();
    when(producer.readCurrent("https://other.example.test/issuer"))
        .thenThrow(new AccountIssuerAuthorityEventProducer.IssuerMismatchException());
    AccountIssuerAuthorityGrpcService service = newService(producer);
    ReadIssuerAuthorityForRuntimeRequest request =
        validRequest().toBuilder().setIssuerId("https://other.example.test/issuer").build();

    Outcome<ReadIssuerAuthorityForRuntimeResponse> outcome =
        invoke(service, request, GAME_SESSION_PEER);

    assertFailure(outcome, Status.Code.INVALID_ARGUMENT);
  }

  @Test
  void captureReturnsCompletePristineReceiptWithoutChangingSourceReadback() {
    AccountIssuerAuthorityEventProducer producer = mockProducer();
    AccountIssuerProjectionReconciliationService captureService = mockCaptureService();
    Receipt receipt = receipt(OPERATION_ID, REQUEST_ID, GAME_SESSION_URI, baseline());
    when(captureService.capture(ISSUER_ID, GAME_SESSION_URI, UUID.fromString(REQUEST_ID)))
        .thenReturn(receipt);
    AccountIssuerAuthorityGrpcService service = newService(producer, captureService);

    Outcome<CaptureIssuerProjectionForRuntimeResponse> outcome =
        invokeCapture(service, validCaptureRequest(), GAME_SESSION_PEER);

    CaptureIssuerProjectionForRuntimeResponse response = assertSuccess(outcome);
    assertThat(response.getSchemaVersion()).isEqualTo("account-auth-issuer-projection-capture/v1");
    assertThat(response.getTargetNamespace()).isEqualTo(NAMESPACE);
    assertThat(response.getOperationId()).isEqualTo(OPERATION_ID.toString());
    assertThat(response.getRequestId()).isEqualTo(REQUEST_ID);
    assertThat(response.getIssuerId()).isEqualTo(ISSUER_ID);
    assertThat(response.getCallerWorkloadIdentity()).isEqualTo(GAME_SESSION_URI);
    assertThat(response.getProjectionKey()).isEqualTo(PROJECTION_KEY);
    assertThat(response.getRequestDigestVersion()).isEqualTo(1);
    assertThat(response.getRequestDigest()).isEqualTo(receipt.requestDigest());
    assertThat(response.hasCapturedSourceSnapshot()).isTrue();
    assertThat(response.getCapturedSourceSnapshot().getIssuerId()).isEqualTo(ISSUER_ID);
    assertThat(response.getCapturedSourceSnapshot().getIssuerAuthGeneration()).isEqualTo("1");
    assertThat(response.getCapturedSourceSnapshot().getSourceVersion()).isEqualTo("1");
    assertThat(response.getCapturedSourceSnapshot().getOutboxSequence()).isEqualTo("0");
    assertThat(response.getCapturedSourceSnapshot().hasLatestEventCanonicalJson()).isFalse();
    verify(captureService, org.mockito.Mockito.times(1))
        .capture(ISSUER_ID, GAME_SESSION_URI, UUID.fromString(REQUEST_ID));
    verifyNoInteractions(producer);
  }

  @Test
  void capturePreservesIndependentPositiveCountersAndCompleteCanonicalEvent() {
    AccountIssuerAuthorityEventProducer producer = mockProducer();
    AccountIssuerProjectionReconciliationService captureService = mockCaptureService();
    IssuerGenerationAuthorityEvent capturedEvent =
        event("f50e8400-e29b-41d4-a716-446655440001", 3L, 8L, 13L);
    IssuerAuthoritySnapshot captured = newSnapshot(8L, 13L, 3L, Optional.of(capturedEvent));
    Receipt receipt = receipt(OPERATION_ID, REQUEST_ID, GAME_SESSION_URI, captured);
    when(captureService.capture(ISSUER_ID, GAME_SESSION_URI, UUID.fromString(REQUEST_ID)))
        .thenReturn(receipt);
    AccountIssuerAuthorityGrpcService service = newService(producer, captureService);

    Outcome<CaptureIssuerProjectionForRuntimeResponse> outcome =
        invokeCapture(service, validCaptureRequest(), GAME_SESSION_PEER);

    CaptureIssuerProjectionForRuntimeResponse response = assertSuccess(outcome);
    assertThat(response.getCapturedSourceSnapshot().getIssuerAuthGeneration()).isEqualTo("8");
    assertThat(response.getCapturedSourceSnapshot().getSourceVersion()).isEqualTo("13");
    assertThat(response.getCapturedSourceSnapshot().getOutboxSequence()).isEqualTo("3");
    assertThat(response.getCapturedSourceSnapshot().getLatestEventCanonicalJson())
        .isEqualTo(capturedEvent.canonicalJson());
    verify(captureService, org.mockito.Mockito.times(1))
        .capture(ISSUER_ID, GAME_SESSION_URI, UUID.fromString(REQUEST_ID));
    verifyNoInteractions(producer);
  }

  @Test
  void exactHistoricalRetryReturnsTheOriginalCaptureReceiptSnapshot() {
    AccountIssuerAuthorityEventProducer producer = mockProducer();
    AccountIssuerProjectionReconciliationService captureService = mockCaptureService();
    IssuerGenerationAuthorityEvent oldEvent =
        event("f50e8400-e29b-41d4-a716-446655440001", 1L, 2L, 5L);
    IssuerAuthoritySnapshot historical = newSnapshot(2L, 5L, 1L, Optional.of(oldEvent));
    UUID originalOperationId = UUID.fromString("21b0eb2d-f7b1-4d88-8db4-2b81cacfc96a");
    Receipt original = receipt(originalOperationId, REQUEST_ID, GAME_SESSION_URI, historical);
    when(captureService.capture(ISSUER_ID, GAME_SESSION_URI, UUID.fromString(REQUEST_ID)))
        .thenReturn(original);
    AccountIssuerAuthorityGrpcService service = newService(producer, captureService);

    Outcome<CaptureIssuerProjectionForRuntimeResponse> outcome =
        invokeCapture(service, validCaptureRequest(), GAME_SESSION_PEER);

    CaptureIssuerProjectionForRuntimeResponse response = assertSuccess(outcome);
    assertThat(response.getOperationId()).isEqualTo(originalOperationId.toString());
    assertThat(response.getCapturedSourceSnapshot().getIssuerAuthGeneration()).isEqualTo("2");
    assertThat(response.getCapturedSourceSnapshot().getSourceVersion()).isEqualTo("5");
    assertThat(response.getCapturedSourceSnapshot().getOutboxSequence()).isEqualTo("1");
    assertThat(response.getCapturedSourceSnapshot().getLatestEventCanonicalJson())
        .isEqualTo(oldEvent.canonicalJson());
    verify(captureService, org.mockito.Mockito.times(1))
        .capture(ISSUER_ID, GAME_SESSION_URI, UUID.fromString(REQUEST_ID));
    verifyNoInteractions(producer);
  }

  @Test
  void captureRejectsWrongOrMissingPeerBeforeParsingOrOwnerInteraction() {
    AccountIssuerAuthorityEventProducer producer = mockProducer();
    AccountIssuerProjectionReconciliationService captureService = mockCaptureService();
    AccountIssuerAuthorityGrpcService service = newService(producer, captureService);
    CaptureIssuerProjectionForRuntimeRequest malformed =
        CaptureIssuerProjectionForRuntimeRequest.newBuilder().setIssuerId(" ").build();

    Outcome<CaptureIssuerProjectionForRuntimeResponse> wrongPeer =
        invokeCapture(
            service,
            malformed,
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service",
                NAMESPACE,
                "account-service"));
    Outcome<CaptureIssuerProjectionForRuntimeResponse> missingPeer =
        invokeWithoutPeerCapture(service, malformed);

    assertFailure(wrongPeer, Status.Code.PERMISSION_DENIED);
    assertFailure(missingPeer, Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(producer, captureService);
  }

  @Test
  void captureRejectsUnknownFieldsAndMalformedRequestIdsBeforeCallingCaptureService() {
    AccountIssuerAuthorityEventProducer producer = mockProducer();
    AccountIssuerProjectionReconciliationService captureService = mockCaptureService();
    AccountIssuerAuthorityGrpcService service = newService(producer, captureService);
    CaptureIssuerProjectionForRuntimeRequest valid = validCaptureRequest();
    List<CaptureIssuerProjectionForRuntimeRequest> invalidRequests =
        List.of(
            valid.toBuilder()
                .setUnknownFields(
                    com.google.protobuf.UnknownFieldSet.newBuilder()
                        .addField(
                            99,
                            com.google.protobuf.UnknownFieldSet.Field.newBuilder()
                                .addVarint(1L)
                                .build())
                        .build())
                .build(),
            valid.toBuilder().setRequestId("F50E8400-E29B-41D4-A716-446655440000").build(),
            valid.toBuilder().setRequestId("00000000-0000-0000-0000-000000000000").build(),
            valid.toBuilder().setRequestId("f50e8400-e29b-41d4-a716-446655440000 ").build());

    for (CaptureIssuerProjectionForRuntimeRequest invalidRequest : invalidRequests) {
      assertFailure(
          invokeCapture(service, invalidRequest, GAME_SESSION_PEER), Status.Code.INVALID_ARGUMENT);
    }

    verifyNoInteractions(producer, captureService);
  }

  @Test
  void captureRejectsCrossBoundAndContradictoryReceiptsWithoutPartialResponse() {
    AccountIssuerAuthorityEventProducer producer = mockProducer();
    AccountIssuerProjectionReconciliationService crossBoundCaptureService = mockCaptureService();
    Receipt crossBound =
        receipt(
            OPERATION_ID,
            REQUEST_ID,
            "spiffe://firemud/ns/other-unit/sa/game-session-service",
            baseline());
    when(crossBoundCaptureService.capture(ISSUER_ID, GAME_SESSION_URI, UUID.fromString(REQUEST_ID)))
        .thenReturn(crossBound);
    AccountIssuerAuthorityGrpcService crossBoundService =
        newService(producer, crossBoundCaptureService);

    Outcome<CaptureIssuerProjectionForRuntimeResponse> crossBoundOutcome =
        invokeCapture(crossBoundService, validCaptureRequest(), GAME_SESSION_PEER);

    assertFailure(crossBoundOutcome, Status.Code.FAILED_PRECONDITION);

    AccountIssuerProjectionReconciliationService contradictoryCaptureService = mockCaptureService();
    Receipt contradictory = spy(receipt(OPERATION_ID, REQUEST_ID, GAME_SESSION_URI, baseline()));
    IssuerAuthoritySnapshot contradictorySnapshot = mock(IssuerAuthoritySnapshot.class);
    when(contradictorySnapshot.issuerId()).thenReturn(ISSUER_ID);
    when(contradictorySnapshot.issuerAuthGeneration()).thenReturn(2L);
    when(contradictorySnapshot.sourceVersion()).thenReturn(2L);
    when(contradictorySnapshot.outboxStreamKey()).thenReturn(STREAM_KEY);
    when(contradictorySnapshot.outboxSequence()).thenReturn(0L);
    when(contradictorySnapshot.latestEvent()).thenReturn(Optional.empty());
    // Both production constructors reject this inconsistent checkpoint. Mock it only to exercise
    // transport defense against untrusted collaborator output, not a migrated SQL row.
    doReturn(contradictorySnapshot).when(contradictory).capturedSource();
    when(contradictoryCaptureService.capture(
            ISSUER_ID, GAME_SESSION_URI, UUID.fromString(REQUEST_ID)))
        .thenReturn(contradictory);
    AccountIssuerAuthorityGrpcService contradictoryService =
        newService(producer, contradictoryCaptureService);

    Outcome<CaptureIssuerProjectionForRuntimeResponse> contradictoryOutcome =
        invokeCapture(contradictoryService, validCaptureRequest(), GAME_SESSION_PEER);

    assertFailure(contradictoryOutcome, Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(producer);
  }

  @Test
  void captureMapsExactIssuerConflictReceiptConflictAndTransientFailureToBoundedStatuses() {
    AccountIssuerAuthorityEventProducer producer = mockProducer();
    AccountIssuerProjectionReconciliationService mismatchService = mockCaptureService();
    String otherIssuer = "https://other.example.test/issuer";
    when(mismatchService.capture(otherIssuer, GAME_SESSION_URI, UUID.fromString(REQUEST_ID)))
        .thenThrow(new AccountIssuerAuthorityEventProducer.IssuerMismatchException());
    AccountIssuerAuthorityGrpcService mismatchGrpcService = newService(producer, mismatchService);
    CaptureIssuerProjectionForRuntimeRequest wrongIssuerRequest =
        validCaptureRequest().toBuilder().setIssuerId(otherIssuer).build();
    assertFailure(
        invokeCapture(mismatchGrpcService, wrongIssuerRequest, GAME_SESSION_PEER),
        Status.Code.INVALID_ARGUMENT);

    AccountIssuerProjectionReconciliationService conflictService = mockCaptureService();
    when(conflictService.capture(ISSUER_ID, GAME_SESSION_URI, UUID.fromString(REQUEST_ID)))
        .thenThrow(
            new AccountIssuerProjectionReconciliationRepository.IdempotencyConflictException(
                "private conflict detail"));
    Outcome<CaptureIssuerProjectionForRuntimeResponse> conflict =
        invokeCapture(
            newService(producer, conflictService), validCaptureRequest(), GAME_SESSION_PEER);
    assertFailure(conflict, Status.Code.ALREADY_EXISTS);
    assertThat(conflict.error().getDescription()).doesNotContain("private conflict detail");

    AccountIssuerProjectionReconciliationService transientService = mockCaptureService();
    when(transientService.capture(ISSUER_ID, GAME_SESSION_URI, UUID.fromString(REQUEST_ID)))
        .thenThrow(new TransientDataAccessResourceException("database detail"));
    Outcome<CaptureIssuerProjectionForRuntimeResponse> unavailable =
        invokeCapture(
            newService(producer, transientService), validCaptureRequest(), GAME_SESSION_PEER);
    assertFailure(unavailable, Status.Code.UNAVAILABLE);
    assertThat(unavailable.error().getDescription()).doesNotContain("database detail");
    verifyNoInteractions(producer);
  }

  private static AccountIssuerAuthorityGrpcService newService(
      AccountIssuerAuthorityEventProducer producer) {
    return newService(producer, mockCaptureService());
  }

  private static AccountIssuerAuthorityGrpcService newService(
      AccountIssuerAuthorityEventProducer producer,
      AccountIssuerProjectionReconciliationService captureService) {
    return new AccountIssuerAuthorityGrpcService(producer, captureService, NAMESPACE);
  }

  private static AccountIssuerAuthorityEventProducer mockProducer() {
    return mock(AccountIssuerAuthorityEventProducer.class);
  }

  private static AccountIssuerProjectionReconciliationService mockCaptureService() {
    return mock(AccountIssuerProjectionReconciliationService.class);
  }

  private static ReadIssuerAuthorityForRuntimeRequest validRequest() {
    return ReadIssuerAuthorityForRuntimeRequest.newBuilder()
        .setIssuerId(ISSUER_ID)
        .setRequestId(REQUEST_ID)
        .build();
  }

  private static CaptureIssuerProjectionForRuntimeRequest validCaptureRequest() {
    return CaptureIssuerProjectionForRuntimeRequest.newBuilder()
        .setIssuerId(ISSUER_ID)
        .setRequestId(REQUEST_ID)
        .build();
  }

  private static IssuerAuthoritySnapshot baseline() {
    return newSnapshot(1L, 1L, 0L, Optional.empty());
  }

  private static Receipt receipt(
      UUID operationId,
      String requestId,
      String callerIdentity,
      IssuerAuthoritySnapshot capturedSource) {
    UUID stableRequestId = UUID.fromString(requestId);
    return new Receipt(
        operationId,
        stableRequestId,
        ISSUER_ID,
        callerIdentity,
        PROJECTION_KEY,
        1,
        Receipt.requestDigestFor(ISSUER_ID, callerIdentity, PROJECTION_KEY, stableRequestId),
        capturedSource);
  }

  private static IssuerAuthoritySnapshot newSnapshot(
      long generation,
      long sourceVersion,
      long sequence,
      Optional<IssuerGenerationAuthorityEvent> latestEvent) {
    return new IssuerAuthoritySnapshot(
        ISSUER_ID, generation, sourceVersion, STREAM_KEY, sequence, latestEvent);
  }

  private static Outcome<CaptureIssuerProjectionForRuntimeResponse> invokeCapture(
      AccountIssuerAuthorityGrpcService service,
      CaptureIssuerProjectionForRuntimeRequest request,
      GrpcPeerIdentity peerIdentity) {
    Context peerContext = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peerIdentity);
    Context previous = peerContext.attach();
    try {
      RecordingObserver<CaptureIssuerProjectionForRuntimeResponse> observer =
          new RecordingObserver<>();
      service.captureIssuerProjectionForRuntime(request, observer);
      return observer.outcome();
    } finally {
      peerContext.detach(previous);
    }
  }

  private static Outcome<CaptureIssuerProjectionForRuntimeResponse> invokeWithoutPeerCapture(
      AccountIssuerAuthorityGrpcService service, CaptureIssuerProjectionForRuntimeRequest request) {
    RecordingObserver<CaptureIssuerProjectionForRuntimeResponse> observer =
        new RecordingObserver<>();
    service.captureIssuerProjectionForRuntime(request, observer);
    return observer.outcome();
  }

  private static IssuerGenerationAuthorityEvent event(
      String eventRequestId, long sequence, long generation, long sourceVersion) {
    return IssuerGenerationAuthorityEventV1Codec.seal(
        Map.of(
            "schemaVersion",
            IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
            "eventType",
            IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE,
            "eventId",
            "account-issuer-authority-event-v1:" + eventRequestId,
            "requestId",
            eventRequestId,
            "issuerId",
            ISSUER_ID,
            "sourceScope",
            SOURCE_SCOPE,
            "outboxStreamKey",
            STREAM_KEY,
            "outboxSequence",
            Long.toString(sequence),
            "issuerAuthGeneration",
            Long.toString(generation),
            "sourceVersion",
            Long.toString(sourceVersion)));
  }

  private static Outcome<ReadIssuerAuthorityForRuntimeResponse> invoke(
      AccountIssuerAuthorityGrpcService service,
      ReadIssuerAuthorityForRuntimeRequest request,
      GrpcPeerIdentity peerIdentity) {
    Context peerContext = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peerIdentity);
    Context previous = peerContext.attach();
    try {
      RecordingObserver<ReadIssuerAuthorityForRuntimeResponse> observer = new RecordingObserver<>();
      service.readIssuerAuthorityForRuntime(request, observer);
      return observer.outcome();
    } finally {
      peerContext.detach(previous);
    }
  }

  private static Outcome<ReadIssuerAuthorityForRuntimeResponse> invokeWithoutPeer(
      AccountIssuerAuthorityGrpcService service, ReadIssuerAuthorityForRuntimeRequest request) {
    RecordingObserver<ReadIssuerAuthorityForRuntimeResponse> observer = new RecordingObserver<>();
    service.readIssuerAuthorityForRuntime(request, observer);
    return observer.outcome();
  }

  private static <T> T assertSuccess(Outcome<T> outcome) {
    assertThat(outcome.error()).isNull();
    assertThat(outcome.completed()).isTrue();
    assertThat(outcome.responses()).hasSize(1);
    return outcome.responses().get(0);
  }

  private static void assertFailure(Outcome<?> outcome, Status.Code expectedCode) {
    assertThat(outcome.error()).isNotNull();
    assertThat(outcome.error().getCode()).isEqualTo(expectedCode);
    assertThat(outcome.responses()).isEmpty();
    assertThat(outcome.completed()).isFalse();
  }

  private record Outcome<T>(List<T> responses, Status error, boolean completed) {}

  private static final class RecordingObserver<T> implements StreamObserver<T> {
    private final List<T> responses = new ArrayList<>();
    private Status error;
    private boolean completed;

    @Override
    public void onNext(T value) {
      responses.add(value);
    }

    @Override
    public void onError(Throwable throwable) {
      Status received = Status.fromThrowable(throwable);
      error = Status.fromCode(received.getCode()).withDescription(received.getDescription());
    }

    @Override
    public void onCompleted() {
      completed = true;
    }

    private Outcome<T> outcome() {
      return new Outcome<>(List.copyOf(responses), error, completed);
    }
  }
}
