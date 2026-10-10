package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.firedevops.firemud.account.v1.AccountSelectedOwnerIntakeAuthorizationProducerServiceGrpc;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeAuthorizationProducerRequest;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeAuthorizationProducerResponse;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationProducerCredentials;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationProducerEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationProducerProtoCodec;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Receiver boundary tests; the mocked owner does not establish durable retry or retention proof.
 */
class AccountSelectedOwnerIntakeAuthorizationProducerGrpcServiceTest {
  private static final String NAMESPACE = "test";
  private static final String CREDENTIAL = "original.creator.credential";

  private final AccountSelectedOwnerIntakeAuthorizationService owner =
      mock(AccountSelectedOwnerIntakeAuthorizationService.class);
  private final AccountHostedTermsService terms = mock(AccountHostedTermsService.class);
  private final AccountSelectedOwnerIntakeAuthorizationProducerGrpcService receiver =
      new AccountSelectedOwnerIntakeAuthorizationProducerGrpcService(owner, terms, NAMESPACE);

  @AfterEach
  void clearContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void rejectsWrongPeerNamespaceAndEndUserBeforeCredentialOrRequestDecoding() {
    assertThat(invokeAuthorize(null).code()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(peer(NAMESPACE, "world-management-service", () -> invokeAuthorize(null)).code())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(peer("other", "game-design-service", () -> invokeAuthorize(null)).code())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    SessionContext.setContext("42", List.of(), java.util.Map.of());
    assertThat(peer(NAMESPACE, "game-design-service", () -> invokeAuthorize(null)).code())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(owner, terms);
  }

  @Test
  void boundInterceptorChecksPeerAndMethodBeforeProtectedMetadata() {
    var headers = mock(io.grpc.Metadata.class);
    assertThat(boundCode(headers, null)).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(peer(NAMESPACE, "world-management-service", () -> boundCode(headers, null)))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(peer("other", "game-design-service", () -> boundCode(headers, null)))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    SessionContext.setContext("42", List.of(), java.util.Map.of());
    assertThat(peer(NAMESPACE, "game-design-service", () -> boundCode(headers, null)))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(headers, owner, terms);
    SessionContext.clear();

    var empty = new io.grpc.Metadata();
    assertThat(peer(NAMESPACE, "game-design-service", () -> boundCode(empty, null)))
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    var duplicate = new io.grpc.Metadata();
    duplicate.put(
        SelectedOwnerIntakeAuthorizationProducerCredentials.HEADER,
        SelectedOwnerIntakeAuthorizationProducerCredentials.Credential.of(CREDENTIAL));
    duplicate.put(
        SelectedOwnerIntakeAuthorizationProducerCredentials.HEADER,
        SelectedOwnerIntakeAuthorizationProducerCredentials.Credential.of(CREDENTIAL));
    assertThat(peer(NAMESPACE, "game-design-service", () -> boundCode(duplicate, null)))
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(peer(NAMESPACE, "game-design-service", () -> boundCode(empty, "OtherMethod")))
        .isEqualTo(Status.Code.UNIMPLEMENTED);
    verifyNoInteractions(owner, terms);
  }

  @Test
  void rejectsMalformedRequestAndRequestNamespaceBeforeOwnerAccess() {
    assertThat(authorized(() -> invokeAuthorize(null)).code())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    var valid = request(Owner.ENTITY_MANAGEMENT);
    assertThat(
            authorized(() -> invokeAuthorize(valid.toBuilder().setTargetNamespace("other").build()))
                .code())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(owner, terms);
  }

  @Test
  void delegatesExactRequestAndEnvironmentCaptureAndPassesThroughOwnerBindingOnRetry() {
    var wire = request(Owner.AUTOMATION_SCRIPTING);
    var decoded = SelectedOwnerIntakeAuthorizationProducerProtoCodec.fromRequest(wire);
    var captured = mock(AccountHostedTermsService.CapturedEnvironmentBoundary.class);
    when(terms.captureCurrentEnvironmentBoundary()).thenReturn(captured);
    byte[] authorizationBytes = {1, 2, 3, 4};
    String digest = "a".repeat(64);
    var binding = mock(SelectedOwnerIntakeAuthorizationBinding.class);
    when(binding.owner()).thenReturn(decoded.owner());
    when(binding.targetNamespace()).thenReturn(NAMESPACE);
    when(binding.intakeRequestId()).thenReturn(decoded.intakeRequestId());
    when(binding.selected()).thenReturn(decoded.selected());
    when(binding.intendedReader())
        .thenReturn("spiffe://firemud/ns/" + NAMESPACE + "/sa/automation-scripting-service");
    when(binding.purpose()).thenReturn("AUTOMATION_INTAKE_RETENTION");
    when(binding.digest()).thenReturn(digest);
    when(binding.canonicalBytes()).thenReturn(authorizationBytes);
    when(owner.authorizeWithEnvironmentCapture(
            eq(CREDENTIAL),
            eq(decoded.intakeRequestId()),
            eq(decoded.owner()),
            eq(decoded.selected()),
            any()))
        .thenAnswer(
            call -> {
              Supplier<?> capture = call.getArgument(4);
              assertThat(capture.get()).isSameAs(captured);
              return binding;
            });

    var first = authorized(() -> invokeAuthorize(wire));
    var retry = authorized(() -> invokeAuthorize(wire));
    assertThat(first.error()).isNull();
    assertThat(retry.error()).isNull();
    for (var result : List.of(first, retry)) {
      assertThat(result.response().getRequest()).isEqualTo(wire);
      assertThat(result.response().getIntakeAuthorizationBinding().toByteArray())
          .containsExactly(authorizationBytes);
      assertThat(result.response().getIntakeAuthorizationDigest()).isEqualTo(digest);
      assertThat(result.response().toString()).doesNotContain(CREDENTIAL);
    }
    verify(owner, times(2))
        .authorizeWithEnvironmentCapture(
            eq(CREDENTIAL),
            eq(decoded.intakeRequestId()),
            eq(decoded.owner()),
            eq(decoded.selected()),
            any());
    assertThat(wire.toString()).doesNotContain(CREDENTIAL);
  }

  @Test
  void rejectsAmbientTransactionAndSynchronizationBeforeCallingOwner() {
    var wire = request(Owner.ENTITY_MANAGEMENT);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThat(authorized(() -> invokeAuthorize(wire)).code())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThat(authorized(() -> invokeAuthorize(wire)).code())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    verifyNoInteractions(owner, terms);
  }

  @Test
  void sanitizesOwnerFailures() {
    when(owner.authorizeWithEnvironmentCapture(any(), any(), any(), any(), any()))
        .thenThrow(Status.PERMISSION_DENIED.withDescription(CREDENTIAL).asRuntimeException());
    var denied = authorized(() -> invokeAuthorize(request(Owner.ENTITY_MANAGEMENT)));
    assertThat(denied.code()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(denied.error().toString()).doesNotContain(CREDENTIAL);
    assertThat(denied.error().getCause()).isNull();
  }

  private record Result(
      SelectedOwnerIntakeAuthorizationProducerResponse response, Throwable error) {
    Status.Code code() {
      return Status.fromThrowable(error).getCode();
    }
  }

  private Result invokeAuthorize(SelectedOwnerIntakeAuthorizationProducerRequest request) {
    var response = new AtomicReference<SelectedOwnerIntakeAuthorizationProducerResponse>();
    var error = new AtomicReference<Throwable>();
    var observer =
        new io.grpc.stub.StreamObserver<SelectedOwnerIntakeAuthorizationProducerResponse>() {
          @Override
          public void onNext(SelectedOwnerIntakeAuthorizationProducerResponse value) {
            response.set(value);
          }

          @Override
          public void onError(Throwable failure) {
            error.set(failure);
          }

          @Override
          public void onCompleted() {}
        };
    receiver.authorizeSelectedOwnerIntake(request, observer);
    return new Result(response.get(), error.get());
  }

  @SuppressWarnings("unchecked")
  private Status.Code boundCode(io.grpc.Metadata headers, String replacementMethodName) {
    var method =
        (io.grpc.ServerMethodDefinition<
                SelectedOwnerIntakeAuthorizationProducerRequest,
                SelectedOwnerIntakeAuthorizationProducerResponse>)
            receiver.bindService().getMethods().stream().findFirst().orElseThrow();
    io.grpc.ServerCall<
            SelectedOwnerIntakeAuthorizationProducerRequest,
            SelectedOwnerIntakeAuthorizationProducerResponse>
        call = mock(io.grpc.ServerCall.class);
    var descriptor = method.getMethodDescriptor();
    if (replacementMethodName != null) {
      descriptor =
          MethodDescriptor
              .<SelectedOwnerIntakeAuthorizationProducerRequest,
                  SelectedOwnerIntakeAuthorizationProducerResponse>
                  newBuilder()
              .setType(descriptor.getType())
              .setFullMethodName(
                  AccountSelectedOwnerIntakeAuthorizationProducerServiceGrpc.SERVICE_NAME
                      + "/"
                      + replacementMethodName)
              .setRequestMarshaller(descriptor.getRequestMarshaller())
              .setResponseMarshaller(descriptor.getResponseMarshaller())
              .build();
    }
    when(call.getMethodDescriptor()).thenReturn(descriptor);
    method.getServerCallHandler().startCall(call, headers);
    var status = org.mockito.ArgumentCaptor.forClass(Status.class);
    org.mockito.Mockito.verify(call).close(status.capture(), org.mockito.ArgumentMatchers.any());
    return status.getValue().getCode();
  }

  private static <T> T authorized(Supplier<T> action) {
    var context =
        io.grpc.Context.current()
            .withValue(
                SelectedOwnerIntakeAuthorizationProducerCredentials.CONTEXT_KEY,
                SelectedOwnerIntakeAuthorizationProducerCredentials.Credential.of(CREDENTIAL));
    var previous = context.attach();
    try {
      return peer(NAMESPACE, "game-design-service", action);
    } finally {
      context.detach(previous);
    }
  }

  private static <T> T peer(String namespace, String workload, Supplier<T> action) {
    var context =
        io.grpc.Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
                    .orElseThrow());
    var previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  private static SelectedOwnerIntakeAuthorizationProducerRequest request(Owner owner) {
    var safe =
        SelectedOwnerIntakeAuthorizationProducerEvidence.Request.create(
            NAMESPACE, UUID.randomUUID(), owner, selected());
    return SelectedOwnerIntakeAuthorizationProducerProtoCodec.toRequest(safe);
  }

  private static DraftCommitBinding selected() {
    UUID tenant = UUID.randomUUID(), version = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, version, 1, "private", 2, "private", "NEW_GAME_ROW");
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "base-1",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0", UUID.randomUUID(), Owner.GAME_DESIGN_CONTROL_PLANE, "{}")),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                "GAMEPLAY_RULE_SET",
                version.toString(),
                "GAMEPLAY_RULE_SET",
                "effective",
                "0")));
  }
}
