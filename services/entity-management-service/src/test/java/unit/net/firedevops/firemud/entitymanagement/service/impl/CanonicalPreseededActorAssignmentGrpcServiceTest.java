package net.firedevops.firemud.entitymanagement.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.config.PreseededActorAssignmentConfiguration;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.security.CanonicalPreseededActorAssignmentPeerInterceptor;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentExpectedTarget;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidencePort;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceipt;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceiptRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentReceiptService;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentResult;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentService;
import net.firedevops.firemud.entitymanagement.service.PreseededActorCorePayload;
import net.firedevops.firemud.entitymanagement.service.RunOwnedPreseededAssignmentAuthority;
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorRequest;
import net.firedevops.firemud.entitymanagement.v1.AssignPreseededActorResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentOutcome;
import net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentReceiptStatus;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorAssignmentReceiptRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorAssignmentReceiptResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CanonicalPreseededActorAssignmentGrpcServiceTest {
  private static final String NAMESPACE = "gameplay";
  private static final String PEER_URI = "spiffe://firemud/ns/gameplay/sa/game-session-service";
  private static final String WORLD_PEER_URI =
      "spiffe://firemud/ns/gameplay/sa/world-management-service";
  private static final UUID ASSIGNMENT_UUID = uuid("10000000-0000-4000-8000-000000000001");
  private static final UUID ACCOUNT_UUID = uuid("20000000-0000-4000-8000-000000000002");
  private static final UUID CHARACTER_UUID = uuid("30000000-0000-4000-8000-000000000003");
  private static final UUID TENANT_UUID = uuid("40000000-0000-4000-8000-000000000004");
  private static final UUID REALM_UUID = uuid("50000000-0000-4000-8000-000000000005");
  private static final UUID VERSION_UUID = uuid("60000000-0000-4000-8000-000000000006");
  private static final UUID NAMESPACE_UUID = uuid("70000000-0000-4000-8000-000000000007");

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void delegatesExactAuthenticatedRequestAndReturnsOnlyPersistedAssignedIdentity() {
    var wireRequest = request();
    PreseededActorAssignmentRequest domainRequest =
        PreseededActorAssignmentGrpcCodec.fromRequest(wireRequest);
    PreseededActorAssignmentService assignmentService = mock(PreseededActorAssignmentService.class);
    when(assignmentService.assign(domainRequest))
        .thenReturn(
            new PreseededActorAssignmentResult(
                ASSIGNMENT_UUID,
                PreseededActorAssignmentResult.Outcome.ASSIGNED,
                CHARACTER_UUID,
                domainRequest.mutationIntentDigest()));
    var handler = assignmentHandler(assignmentService);

    CapturingObserver response = invoke(handler, wireRequest);

    assertThat(response.completed).isTrue();
    assertThat(response.errorCode).isNull();
    assertThat(response.value.getAssignmentUuid()).isEqualTo(ASSIGNMENT_UUID.toString());
    assertThat(response.value.getOutcome())
        .isEqualTo(PreseededActorAssignmentOutcome.PRESEEDED_ACTOR_ASSIGNMENT_OUTCOME_ASSIGNED);
    assertThat(response.value.getCharacterUuid()).isEqualTo(CHARACTER_UUID.toString());
    assertThat(response.value.getIntentDigest()).isEqualTo(domainRequest.mutationIntentDigest());
    verify(assignmentService).assign(domainRequest);
  }

  @Test
  void idempotencyConflictReturnsNoCharacterIdentity() {
    var wireRequest = request();
    PreseededActorAssignmentRequest domainRequest =
        PreseededActorAssignmentGrpcCodec.fromRequest(wireRequest);
    PreseededActorAssignmentService assignmentService = mock(PreseededActorAssignmentService.class);
    when(assignmentService.assign(domainRequest))
        .thenReturn(
            new PreseededActorAssignmentResult(
                ASSIGNMENT_UUID,
                PreseededActorAssignmentResult.Outcome.IDEMPOTENCY_CONFLICT,
                null,
                domainRequest.mutationIntentDigest()));

    CapturingObserver response = invoke(assignmentHandler(assignmentService), wireRequest);

    assertThat(response.completed).isTrue();
    assertThat(response.value.getOutcome())
        .isEqualTo(
            PreseededActorAssignmentOutcome
                .PRESEEDED_ACTOR_ASSIGNMENT_OUTCOME_IDEMPOTENCY_CONFLICT);
    assertThat(response.value.hasCharacterUuid()).isFalse();
  }

  @Test
  void malformedRequestIsRejectedBeforeTheAssignmentService() {
    PreseededActorAssignmentService assignmentService = mock(PreseededActorAssignmentService.class);
    var malformed = request().toBuilder().setMutationIntentDigest("f".repeat(64)).build();

    CapturingObserver response = invoke(assignmentHandler(assignmentService), malformed);

    assertNoPartialResponse(response, Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(assignmentService);
  }

  @Test
  void missingInterceptorPeerFailsBeforeAssignmentService() {
    PreseededActorAssignmentService assignmentService = mock(PreseededActorAssignmentService.class);
    var response = new CapturingObserver();
    var handler = assignmentHandler(assignmentService);

    Context.ROOT.run(() -> handler.assignPreseededActor(request(), response));

    assertNoPartialResponse(response, Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(assignmentService);
  }

  @Test
  void capabilityAndOwnerFailuresReturnClosedStatusesWithoutPartialResponse() {
    var wireRequest = request();
    PreseededActorAssignmentRequest domainRequest =
        PreseededActorAssignmentGrpcCodec.fromRequest(wireRequest);
    PreseededActorAssignmentService deniedService = mock(PreseededActorAssignmentService.class);
    when(deniedService.assign(domainRequest))
        .thenThrow(new AdminAuthorizationException("fixture denial"));
    var denied = invoke(assignmentHandler(deniedService), wireRequest);
    assertNoPartialResponse(denied, Status.Code.PERMISSION_DENIED);

    PreseededActorAssignmentService unavailableService =
        mock(PreseededActorAssignmentService.class);
    when(unavailableService.assign(domainRequest))
        .thenThrow(
            new PreseededActorAssignmentOwnerEvidencePort.OwnerEvidenceUnavailableException());
    var unavailable = invoke(assignmentHandler(unavailableService), wireRequest);
    assertNoPartialResponse(unavailable, Status.Code.UNAVAILABLE);
    assertThat(unavailable.errorDescription)
        .isEqualTo("Preseeded actor assignment owner is unavailable");

    PreseededActorAssignmentService upstreamFailureService =
        mock(PreseededActorAssignmentService.class);
    when(upstreamFailureService.assign(domainRequest))
        .thenThrow(
            Status.FAILED_PRECONDITION
                .withDescription("PRIVATE OWNER DETAIL")
                .asRuntimeException());
    var upstreamFailure =
        invoke(
            assignmentHandler(upstreamFailureService),
            wireRequest);
    assertNoPartialResponse(upstreamFailure, Status.Code.FAILED_PRECONDITION);
    assertThat(upstreamFailure.errorDescription)
        .isEqualTo("Preseeded actor assignment owner evidence is not current");
  }

  @Test
  void disabledAccountEvidencePortsReturnUnavailable() {
    var configuration = new PreseededActorAssignmentConfiguration();
    var wireRequest = request();
    var accountIdentityUnavailableService =
        configuration.preseededActorAssignmentService(
            mock(RunOwnedPreseededAssignmentAuthority.class),
            configuration.disabledPreseededActorAccountIdentityPort(),
            configuration.disabledPreseededActorStagingEligibilityPort(),
            configuration.unavailablePreseededActorAssignmentOwnerEvidencePort(),
            mock(CharacterRepository.class));
    var accountIdentityUnavailable =
        invoke(
            new CanonicalPreseededActorAssignmentGrpcService(
                NAMESPACE, accountIdentityUnavailableService),
            wireRequest);
    assertNoPartialResponse(accountIdentityUnavailable, Status.Code.UNAVAILABLE);

    var stagingUnavailableService =
        configuration.preseededActorAssignmentService(
            mock(RunOwnedPreseededAssignmentAuthority.class),
            (accountUuid, assignmentUuid) ->
                new RuntimeAccountIdentityEvidence(
                    1,
                    "dev",
                    UUID.fromString(assignmentUuid),
                    UUID.fromString(accountUuid),
                    23L,
                    "ACCOUNT_DATABASE_INSERT"),
            configuration.disabledPreseededActorStagingEligibilityPort(),
            configuration.unavailablePreseededActorAssignmentOwnerEvidencePort(),
            mock(CharacterRepository.class));
    var stagingUnavailable =
        invoke(
            assignmentHandler(stagingUnavailableService),
            wireRequest);
    assertNoPartialResponse(stagingUnavailable, Status.Code.UNAVAILABLE);
  }

  @Test
  void knownPreconditionIsFailedPreconditionAndUnexpectedIllegalStateIsInternal() {
    var wireRequest = request();
    PreseededActorAssignmentRequest domainRequest =
        PreseededActorAssignmentGrpcCodec.fromRequest(wireRequest);

    PreseededActorAssignmentService preconditionService =
        mock(PreseededActorAssignmentService.class);
    when(preconditionService.assign(domainRequest))
        .thenThrow(
            new PreseededActorAssignmentService.PreconditionException(
                "PRIVATE PRECONDITION DETAIL"));
    var precondition =
        invoke(
            assignmentHandler(preconditionService),
            wireRequest);
    assertNoPartialResponse(precondition, Status.Code.FAILED_PRECONDITION);
    assertThat(precondition.errorDescription)
        .isEqualTo("Preseeded actor assignment preconditions are not satisfied");

    PreseededActorAssignmentService unexpectedFailureService =
        mock(PreseededActorAssignmentService.class);
    when(unexpectedFailureService.assign(domainRequest))
        .thenThrow(new IllegalStateException("PRIVATE INTERNAL DETAIL"));
    var unexpectedFailure =
        invoke(
            assignmentHandler(unexpectedFailureService),
            wireRequest);
    assertNoPartialResponse(unexpectedFailure, Status.Code.INTERNAL);
    assertThat(unexpectedFailure.errorDescription).isEqualTo("Preseeded actor assignment failed");
  }

  @Test
  void worldReceiptReadReturnsOnlyTheExactPersistedAssignedReceipt() {
    var wireRequest = receiptReadRequest();
    var readRequest = PreseededActorAssignmentGrpcCodec.fromReceiptReadRequest(wireRequest);
    var receipt = receipt();
    var receiptService = mock(PreseededActorAssignmentReceiptService.class);
    when(receiptService.read(readRequest.receiptRequest())).thenReturn(Optional.of(receipt));
    var assignmentService = mock(PreseededActorAssignmentService.class);
    var handler =
        new CanonicalPreseededActorAssignmentGrpcService(
            NAMESPACE, assignmentService, receiptService);

    CapturingReadObserver response = invokeRead(handler, wireRequest);

    assertThat(response.completed).isTrue();
    assertThat(response.errorCode).isNull();
    assertThat(response.value.getSchemaVersion()).isEqualTo(1);
    assertThat(response.value.getPlacementOperationUuid())
        .isEqualTo(wireRequest.getPlacementOperationUuid());
    assertThat(response.value.getAssignmentUuid()).isEqualTo(receipt.assignmentUuid().toString());
    assertThat(response.value.getCanonicalAccountUuid())
        .isEqualTo(receipt.canonicalAccountUuid().toString());
    assertThat(response.value.getCharacterUuid()).isEqualTo(receipt.characterUuid().toString());
    assertThat(response.value.getIntentDigest()).isEqualTo(receipt.intentDigest());
    assertThat(response.value.getExpectedTarget()).isEqualTo(wireRequest.getExpectedTarget());
    assertThat(response.value.getCorePayload().getDisplayName()).isEqualTo("Sora");
    assertThat(response.value.getStatus())
        .isEqualTo(
            PreseededActorAssignmentReceiptStatus
                .PRESEEDED_ACTOR_ASSIGNMENT_RECEIPT_STATUS_ASSIGNED);
    verify(receiptService).read(readRequest.receiptRequest());
    verifyNoInteractions(assignmentService);
  }

  @Test
  void missingOrMismatchedPersistedReceiptFailsWithoutPartialResponse() {
    var wireRequest = receiptReadRequest();
    var readRequest = PreseededActorAssignmentGrpcCodec.fromReceiptReadRequest(wireRequest);
    var receiptService = mock(PreseededActorAssignmentReceiptService.class);
    when(receiptService.read(readRequest.receiptRequest())).thenReturn(Optional.empty());
    var handler =
        new CanonicalPreseededActorAssignmentGrpcService(
            NAMESPACE, mock(PreseededActorAssignmentService.class), receiptService);

    assertNoPartialReadResponse(invokeRead(handler, wireRequest), Status.Code.NOT_FOUND);

    when(receiptService.read(readRequest.receiptRequest()))
        .thenReturn(
            Optional.of(receipt(uuid("90000000-0000-4000-8000-000000000009"), receiptRequest())));
    assertNoPartialReadResponse(invokeRead(handler, wireRequest), Status.Code.FAILED_PRECONDITION);
  }

  @Test
  void receiptReadRequiresTheWorldPeerBeforeReadingEntityStorage() {
    var receiptService = mock(PreseededActorAssignmentReceiptService.class);
    var handler =
        new CanonicalPreseededActorAssignmentGrpcService(
            NAMESPACE, mock(PreseededActorAssignmentService.class), receiptService);
    var response = new CapturingReadObserver();

    Context.ROOT.run(
        () -> handler.readPreseededActorAssignmentReceipt(receiptReadRequest(), response));

    assertNoPartialReadResponse(response, Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(receiptService);
  }

  @Test
  void receiptReadOnNextFailureIsReportedAsTheSameExceptionAndDoesNotComplete() {
    var wireRequest = receiptReadRequest();
    var readRequest = PreseededActorAssignmentGrpcCodec.fromReceiptReadRequest(wireRequest);
    var receiptService = mock(PreseededActorAssignmentReceiptService.class);
    when(receiptService.read(readRequest.receiptRequest())).thenReturn(Optional.of(receipt()));
    var handler =
        new CanonicalPreseededActorAssignmentGrpcService(
            NAMESPACE, mock(PreseededActorAssignmentService.class), receiptService);
    var observerFailure = new IllegalStateException("observer failure");
    var calls = new ObserverCalls();
    StreamObserver<ReadPreseededActorAssignmentReceiptResponse> observer =
        new StreamObserver<>() {
          @Override
          public void onNext(ReadPreseededActorAssignmentReceiptResponse value) {
            calls.next = true;
            throw observerFailure;
          }

          @Override
          public void onError(Throwable failure) {
            calls.error = failure;
          }

          @Override
          public void onCompleted() {
            calls.completed = true;
          }
        };

    invokeReadWithObserver(handler, wireRequest, observer);

    assertThat(calls.next).isTrue();
    assertThat(calls.error).isSameAs(observerFailure);
    assertThat(calls.completed).isFalse();
  }

  @Test
  void receiptReadOnCompletedFailurePropagatesWithoutASecondTerminalCallback() {
    var wireRequest = receiptReadRequest();
    var readRequest = PreseededActorAssignmentGrpcCodec.fromReceiptReadRequest(wireRequest);
    var receiptService = mock(PreseededActorAssignmentReceiptService.class);
    when(receiptService.read(readRequest.receiptRequest())).thenReturn(Optional.of(receipt()));
    var handler =
        new CanonicalPreseededActorAssignmentGrpcService(
            NAMESPACE, mock(PreseededActorAssignmentService.class), receiptService);
    var observerFailure = new IllegalStateException("completion failure");
    var calls = new ObserverCalls();
    StreamObserver<ReadPreseededActorAssignmentReceiptResponse> observer =
        new StreamObserver<>() {
          @Override
          public void onNext(ReadPreseededActorAssignmentReceiptResponse value) {
            calls.next = true;
          }

          @Override
          public void onError(Throwable failure) {
            calls.error = failure;
          }

          @Override
          public void onCompleted() {
            calls.completed = true;
            throw observerFailure;
          }
        };

    assertThatThrownBy(() -> invokeReadWithObserver(handler, wireRequest, observer))
        .isSameAs(observerFailure);
    assertThat(calls.next).isTrue();
    assertThat(calls.completed).isTrue();
    assertThat(calls.error).isNull();
  }

  @Test
  void dependencyStatusesMapToBoundedTransportFailuresWithoutPartialResponse() {
    var wireRequest = request();
    PreseededActorAssignmentRequest domainRequest =
        PreseededActorAssignmentGrpcCodec.fromRequest(wireRequest);
    for (Status.Code upstreamCode :
        List.of(
            Status.Code.UNAVAILABLE,
            Status.Code.DEADLINE_EXCEEDED,
            Status.Code.RESOURCE_EXHAUSTED,
            Status.Code.ABORTED,
            Status.Code.CANCELLED,
            Status.Code.UNAUTHENTICATED,
            Status.Code.UNKNOWN)) {
      PreseededActorAssignmentService assignmentService =
          mock(PreseededActorAssignmentService.class);
      when(assignmentService.assign(domainRequest))
          .thenThrow(
              Status.fromCode(upstreamCode)
                  .withDescription("PRIVATE DEPENDENCY DETAIL")
                  .asRuntimeException());

      CapturingObserver response =
          invoke(
              assignmentHandler(assignmentService),
              wireRequest);

      Status.Code expectedCode =
          upstreamCode == Status.Code.UNKNOWN ? Status.Code.INTERNAL : Status.Code.UNAVAILABLE;
      assertNoPartialResponse(response, expectedCode);
      assertThat(response.errorDescription)
          .isEqualTo(
              expectedCode == Status.Code.UNAVAILABLE
                  ? "Preseeded actor assignment owner is unavailable"
                  : "Preseeded actor assignment failed");
    }
  }

  @Test
  void onNextFailureIsReportedAsTheSameExceptionAndDoesNotComplete() {
    var wireRequest = request();
    PreseededActorAssignmentRequest domainRequest =
        PreseededActorAssignmentGrpcCodec.fromRequest(wireRequest);
    var assignmentService = mock(PreseededActorAssignmentService.class);
    when(assignmentService.assign(domainRequest))
        .thenReturn(
            new PreseededActorAssignmentResult(
                ASSIGNMENT_UUID,
                PreseededActorAssignmentResult.Outcome.ASSIGNED,
                CHARACTER_UUID,
                domainRequest.mutationIntentDigest()));
    var observerFailure = new IllegalStateException("observer failure");
    var calls = new ObserverCalls();
    StreamObserver<AssignPreseededActorResponse> observer =
        new StreamObserver<>() {
          @Override
          public void onNext(AssignPreseededActorResponse value) {
            calls.next = true;
            throw observerFailure;
          }

          @Override
          public void onError(Throwable failure) {
            calls.error = failure;
          }

          @Override
          public void onCompleted() {
            calls.completed = true;
          }
        };

    invokeWithObserver(
        assignmentHandler(assignmentService),
        wireRequest,
        observer);

    assertThat(calls.next).isTrue();
    assertThat(calls.error).isSameAs(observerFailure);
    assertThat(calls.completed).isFalse();
  }

  @Test
  void onCompletedFailurePropagatesWithoutASecondTerminalCallback() {
    var wireRequest = request();
    PreseededActorAssignmentRequest domainRequest =
        PreseededActorAssignmentGrpcCodec.fromRequest(wireRequest);
    var assignmentService = mock(PreseededActorAssignmentService.class);
    when(assignmentService.assign(domainRequest))
        .thenReturn(
            new PreseededActorAssignmentResult(
                ASSIGNMENT_UUID,
                PreseededActorAssignmentResult.Outcome.ASSIGNED,
                CHARACTER_UUID,
                domainRequest.mutationIntentDigest()));
    var observerFailure = new IllegalStateException("completion failure");
    var calls = new ObserverCalls();
    StreamObserver<AssignPreseededActorResponse> observer =
        new StreamObserver<>() {
          @Override
          public void onNext(AssignPreseededActorResponse value) {
            calls.next = true;
          }

          @Override
          public void onError(Throwable failure) {
            calls.error = failure;
          }

          @Override
          public void onCompleted() {
            calls.completed = true;
            throw observerFailure;
          }
        };

    assertThatThrownBy(
            () ->
                invokeWithObserver(
                    assignmentHandler(assignmentService),
                    wireRequest,
                    observer))
        .isSameAs(observerFailure);
    assertThat(calls.next).isTrue();
    assertThat(calls.completed).isTrue();
    assertThat(calls.error).isNull();
  }

  @Test
  void invalidPersistedResultStillReturnsBoundedInternalWithoutPartialResponse() {
    var wireRequest = request();
    PreseededActorAssignmentRequest domainRequest =
        PreseededActorAssignmentGrpcCodec.fromRequest(wireRequest);
    var assignmentService = mock(PreseededActorAssignmentService.class);
    when(assignmentService.assign(domainRequest))
        .thenReturn(
            new PreseededActorAssignmentResult(
                uuid("80000000-0000-4000-8000-000000000008"),
                PreseededActorAssignmentResult.Outcome.ASSIGNED,
                CHARACTER_UUID,
                domainRequest.mutationIntentDigest()));

    CapturingObserver response =
        invoke(
            assignmentHandler(assignmentService),
            wireRequest);

    assertNoPartialResponse(response, Status.Code.INTERNAL);
    assertThat(response.errorDescription).isEqualTo("Preseeded actor assignment failed");
  }

  private static CapturingObserver invoke(
      CanonicalPreseededActorAssignmentGrpcService handler, AssignPreseededActorRequest request) {
    var response = new CapturingObserver();
    invokeWithObserver(handler, request, response);
    return response;
  }

  private static void invokeWithObserver(
      CanonicalPreseededActorAssignmentGrpcService handler,
      AssignPreseededActorRequest request,
      StreamObserver<AssignPreseededActorResponse> response) {
    ServerCall<AssignPreseededActorRequest, AssignPreseededActorResponse> call =
        mock(ServerCall.class);
    MethodDescriptor<AssignPreseededActorRequest, AssignPreseededActorResponse> descriptor =
        mock(MethodDescriptor.class);
    when(descriptor.getFullMethodName())
        .thenReturn(CanonicalPreseededActorAssignmentPeerInterceptor.METHOD_NAME);
    when(call.getMethodDescriptor()).thenReturn(descriptor);
    Attributes attributes =
        Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, peerSession()).build();
    when(call.getAttributes()).thenReturn(attributes);
    ServerCallHandler<AssignPreseededActorRequest, AssignPreseededActorResponse> next =
        (ignoredCall, ignoredHeaders) -> {
          handler.assignPreseededActor(request, response);
          return new ServerCall.Listener<>() {};
        };

    new CanonicalPreseededActorAssignmentPeerInterceptor(NAMESPACE)
        .interceptCall(call, new Metadata(), next);
  }

  private static final class ObserverCalls {
    private boolean next;
    private Throwable error;
    private boolean completed;
  }

  private static CapturingReadObserver invokeRead(
      CanonicalPreseededActorAssignmentGrpcService handler,
      ReadPreseededActorAssignmentReceiptRequest request) {
    var response = new CapturingReadObserver();
    invokeReadWithObserver(handler, request, response);
    return response;
  }

  private static void invokeReadWithObserver(
      CanonicalPreseededActorAssignmentGrpcService handler,
      ReadPreseededActorAssignmentReceiptRequest request,
      StreamObserver<ReadPreseededActorAssignmentReceiptResponse> response) {
    ServerCall<
            ReadPreseededActorAssignmentReceiptRequest, ReadPreseededActorAssignmentReceiptResponse>
        call = mock(ServerCall.class);
    MethodDescriptor<
            ReadPreseededActorAssignmentReceiptRequest, ReadPreseededActorAssignmentReceiptResponse>
        descriptor = mock(MethodDescriptor.class);
    when(descriptor.getFullMethodName())
        .thenReturn(CanonicalPreseededActorAssignmentPeerInterceptor.RECEIPT_READ_METHOD_NAME);
    when(call.getMethodDescriptor()).thenReturn(descriptor);
    Attributes attributes =
        Attributes.newBuilder()
            .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, peerSession(WORLD_PEER_URI))
            .build();
    when(call.getAttributes()).thenReturn(attributes);
    ServerCallHandler<
            ReadPreseededActorAssignmentReceiptRequest, ReadPreseededActorAssignmentReceiptResponse>
        next =
            (ignoredCall, ignoredHeaders) -> {
              handler.readPreseededActorAssignmentReceipt(request, response);
              return new ServerCall.Listener<>() {};
            };

    new CanonicalPreseededActorAssignmentPeerInterceptor(NAMESPACE)
        .interceptCall(call, new Metadata(), next);
  }

  private static CanonicalPreseededActorAssignmentGrpcService assignmentHandler(
      PreseededActorAssignmentService assignmentService) {
    return new CanonicalPreseededActorAssignmentGrpcService(
        NAMESPACE, assignmentService, mock(PreseededActorAssignmentReceiptService.class));
  }

  private static void assertNoPartialResponse(CapturingObserver response, Status.Code code) {
    assertThat(response.value).isNull();
    assertThat(response.completed).isFalse();
    assertThat(response.errorCode).isEqualTo(code);
  }

  private static void assertNoPartialReadResponse(
      CapturingReadObserver response, Status.Code code) {
    assertThat(response.value).isNull();
    assertThat(response.completed).isFalse();
    assertThat(response.errorCode).isEqualTo(code);
  }

  private static ReadPreseededActorAssignmentReceiptRequest receiptReadRequest() {
    var assignmentRequest = request();
    var assignment = PreseededActorAssignmentGrpcCodec.fromRequest(assignmentRequest);
    return ReadPreseededActorAssignmentReceiptRequest.newBuilder()
        .setSchemaVersion(1)
        .setPlacementOperationUuid("80000000-0000-4000-8000-000000000008")
        .setAssignmentUuid(assignment.assignmentUuid().toString())
        .setCanonicalAccountUuid(assignment.canonicalAccountUuid().toString())
        .setCharacterUuid(CHARACTER_UUID.toString())
        .setIntentDigest(assignment.mutationIntentDigest())
        .setExpectedTarget(assignmentRequest.getExpectedTarget())
        .build();
  }

  private static PreseededActorAssignmentReceiptRequest receiptRequest() {
    var assignment = PreseededActorAssignmentGrpcCodec.fromRequest(request());
    return new PreseededActorAssignmentReceiptRequest(
        assignment.assignmentUuid(),
        assignment.canonicalAccountUuid(),
        CHARACTER_UUID,
        assignment.mutationIntentDigest(),
        assignment.expectedTarget());
  }

  private static PreseededActorAssignmentReceipt receipt() {
    return receipt(CHARACTER_UUID, receiptRequest());
  }

  private static PreseededActorAssignmentReceipt receipt(
      UUID characterUuid, PreseededActorAssignmentReceiptRequest selector) {
    var assignment = PreseededActorAssignmentGrpcCodec.fromRequest(request());
    return new PreseededActorAssignmentReceipt(
        selector.assignmentUuid(),
        selector.canonicalAccountUuid(),
        characterUuid,
        selector.intentDigest(),
        selector.expectedTarget(),
        assignment.corePayload(),
        1,
        "gameplay",
        23L,
        "ACCOUNT_REPOSITORY_INSERT",
        PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE,
        Instant.parse("2026-10-25T00:00:00Z"),
        9L,
        "b".repeat(64),
        "c".repeat(64),
        PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING,
        PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION,
        "d".repeat(64),
        selector.expectedTarget().publishedReleaseBundleRef(),
        "PRESEEDED_ONLY",
        "ASSIGNED");
  }

  private static AssignPreseededActorRequest request() {
    var target =
        new PreseededActorAssignmentExpectedTarget(
            TENANT_UUID,
            REALM_UUID,
            "earth",
            "main",
            "instance:opaque",
            17L,
            VERSION_UUID,
            "a".repeat(64),
            NAMESPACE_UUID,
            "release:test",
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
    var assignment =
        new PreseededActorAssignmentRequest(
            ASSIGNMENT_UUID,
            ACCOUNT_UUID,
            new PreseededActorCorePayload(PreseededActorCorePayload.ActorKind.PLAYER, "Sora"),
            target);
    return AssignPreseededActorRequest.newBuilder()
        .setSchemaVersion(1)
        .setAssignmentUuid(ASSIGNMENT_UUID.toString())
        .setCanonicalAccountUuid(ACCOUNT_UUID.toString())
        .setExpectedTarget(
            net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget
                .newBuilder()
                .setCanonicalTenantUuid(TENANT_UUID.toString())
                .setRealmUuid(REALM_UUID.toString())
                .setWorldSlug("earth")
                .setRealmSlug("main")
                .setGameInstanceId("instance:opaque")
                .setCatalogRevision(17L)
                .setCanonicalVersionUuid(VERSION_UUID.toString())
                .setFrozenPolicyDigest("a".repeat(64))
                .setPlayableStateNamespaceUuid(NAMESPACE_UUID.toString())
                .setPublishedReleaseBundleRef("release:test")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .build())
        .setCorePayload(
            net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentCorePayload
                .newBuilder()
                .setActorKind(CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                .setDisplayName("Sora")
                .build())
        .setMutationIntentDigest(assignment.mutationIntentDigest())
        .build();
  }

  private static SSLSession peerSession() {
    return peerSession(PEER_URI);
  }

  private static SSLSession peerSession(String uri) {
    X509Certificate leaf = mock(X509Certificate.class);
    try {
      when(leaf.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, uri)));
      SSLSession session = mock(SSLSession.class);
      when(session.getPeerCertificates()).thenReturn(new Certificate[] {leaf});
      return session;
    } catch (java.security.cert.CertificateParsingException
        | javax.net.ssl.SSLPeerUnverifiedException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class CapturingObserver
      implements StreamObserver<AssignPreseededActorResponse> {
    private AssignPreseededActorResponse value;
    private Status.Code errorCode;
    private String errorDescription;
    private boolean completed;

    @Override
    public void onNext(AssignPreseededActorResponse next) {
      value = next;
    }

    @Override
    public void onError(Throwable failure) {
      Status status = Status.fromThrowable(failure);
      errorCode = status.getCode();
      errorDescription = status.getDescription();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

  private static final class CapturingReadObserver
      implements StreamObserver<ReadPreseededActorAssignmentReceiptResponse> {
    private ReadPreseededActorAssignmentReceiptResponse value;
    private Status.Code errorCode;
    private boolean completed;

    @Override
    public void onNext(ReadPreseededActorAssignmentReceiptResponse next) {
      value = next;
    }

    @Override
    public void onError(Throwable failure) {
      errorCode = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
