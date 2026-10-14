package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Status;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.ClaimOriginalDraftRequest;
import net.firedevops.firemud.account.v1.ClaimOriginalDraftResponse;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderCredentials;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderGrpcCodec;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Context-level receiver units, not mounted-certificate or real Account currentness proof. */
class AccountOriginalDraftOrderGrpcServiceTest {
  private static final String CREDENTIAL = "original.secret.credential";
  private final AccountOriginalDraftOrderService owner =
      mock(AccountOriginalDraftOrderService.class);
  private final AccountHostedTermsService terms = mock(AccountHostedTermsService.class);
  private final AccountOriginalDraftOrderGrpcService receiver =
      new AccountOriginalDraftOrderGrpcService(owner, terms, "test");

  @AfterEach
  void clear() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesBeforeMalformedDecodeAndCaptureAndDeniesWorld() {
    assertThat(invoke(null).code()).isEqualTo(Status.Code.UNAUTHENTICATED);
    for (String workload :
        List.of("world-management-service", "account-service", "game-session-service")) {
      assertThat(peer("test", workload, () -> invoke(null)).code())
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    }
    assertThat(peer("other", "game-design-service", () -> invoke(null)).code())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(authorized(() -> invoke(ClaimOriginalDraftRequest.getDefaultInstance())).code())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(owner, terms);
  }

  @Test
  void boundInterceptorAuthenticatesBeforeTouchingMalformedCredentialMetadata() {
    var headers = mock(io.grpc.Metadata.class);
    assertThat(boundCode(headers)).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(peer("test", "world-management-service", () -> boundCode(headers)))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(peer("other", "game-design-service", () -> boundCode(headers)))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(headers, owner, terms);
    var malformed = new io.grpc.Metadata();
    malformed.put(
        io.grpc.Metadata.Key.of(
            AccountOriginalDraftOrderCredentials.HEADER.name(),
            io.grpc.Metadata.BINARY_BYTE_MARSHALLER),
        new byte[] {(byte) 128});
    assertThat(peer("test", "game-design-service", () -> boundCode(malformed)))
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(peer("test", "game-design-service", () -> invoke(null)).code())
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    verifyNoInteractions(owner, terms);
  }

  @SuppressWarnings("unchecked")
  private Status.Code boundCode(io.grpc.Metadata headers) {
    var method =
        (io.grpc.ServerMethodDefinition<ClaimOriginalDraftRequest, ClaimOriginalDraftResponse>)
            receiver.bindService().getMethods().iterator().next();
    io.grpc.ServerCall<ClaimOriginalDraftRequest, ClaimOriginalDraftResponse> call =
        mock(io.grpc.ServerCall.class);
    method.getServerCallHandler().startCall(call, headers);
    var status = org.mockito.ArgumentCaptor.forClass(Status.class);
    org.mockito.Mockito.verify(call).close(status.capture(), org.mockito.ArgumentMatchers.any());
    return status.getValue().getCode();
  }

  @Test
  void deniesAmbientSqlNamespaceAndUnknownFieldsBeforeCapture() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThat(authorized(() -> invoke(null)).code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertThat(authorized(() -> invoke(null)).code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    TransactionSynchronizationManager.clearSynchronization();
    var request = request();
    assertThat(
            authorized(() -> invoke(request.toBuilder().setTargetNamespace("other").build()))
                .code())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            authorized(
                    () ->
                        invoke(
                            request.toBuilder()
                                .setUnknownFields(
                                    UnknownFieldSet.newBuilder()
                                        .addField(
                                            99,
                                            UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                                        .build())
                                .build()))
                .code())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(owner, terms);
  }

  @Test
  void capturesEnvironmentInternallyAndPreservesOriginalCredentialAndCompleteBinding() {
    var wire = request();
    var decoded = AccountOriginalDraftOrderGrpcCodec.fromRequest(wire, CREDENTIAL);
    var captured = mock(AccountHostedTermsService.CapturedEnvironmentBoundary.class);
    when(terms.captureCurrentEnvironmentBoundary())
        .thenAnswer(
            call -> {
              outsideSql();
              return captured;
            });
    org.mockito.Mockito.doAnswer(
            call -> {
              outsideSql();
              assertThat(call.getArgument(0, String.class)).isEqualTo(CREDENTIAL);
              assertThat(call.getArgument(1, DraftAuthorizationFenceBinding.class).canonicalBytes())
                  .isEqualTo(decoded.originalAccountBinding());
              java.util.function.Supplier<AccountHostedTermsService.CapturedEnvironmentBoundary>
                  capture = call.getArgument(2);
              assertThat(capture.get()).isSameAs(captured);
              return null;
            })
        .when(owner)
        .claimWithEnvironmentCapture(eq(CREDENTIAL), any(), any());
    var result = authorized(() -> invoke(wire));
    assertThat(result.error()).isNull();
    assertThat(
            AccountOriginalDraftOrderGrpcCodec.fromResponse(decoded, result.response())
                .original()
                .canonicalBytes())
        .isEqualTo(decoded.originalAccountBinding());
    assertThat(result.response().toString()).doesNotContain(CREDENTIAL);
  }

  @Test
  void ownerFailureNeverEchoesCredentialDiagnostics() {
    var captured = mock(AccountHostedTermsService.CapturedEnvironmentBoundary.class);
    when(terms.captureCurrentEnvironmentBoundary()).thenReturn(captured);
    org.mockito.Mockito.doThrow(
            io.grpc.Status.PERMISSION_DENIED.withDescription(CREDENTIAL).asRuntimeException())
        .when(owner)
        .claimWithEnvironmentCapture(eq(CREDENTIAL), any(), any());
    var denied = authorized(() -> invoke(request()));
    assertThat(denied.code()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(denied.error().toString()).doesNotContain(CREDENTIAL);
    assertThat(denied.error().getCause()).isNull();
  }

  private static ClaimOriginalDraftRequest request() {
    return AccountOriginalDraftOrderGrpcCodec.toRequest(
        AccountOriginalDraftOrderGrpcCodec.Request.create("test", original(), CREDENTIAL));
  }

  private record Result(ClaimOriginalDraftResponse response, Throwable error) {
    Status.Code code() {
      return Status.fromThrowable(error).getCode();
    }
  }

  private Result invoke(ClaimOriginalDraftRequest request) {
    var response = new AtomicReference<ClaimOriginalDraftResponse>();
    var error = new AtomicReference<Throwable>();
    receiver.claimOriginalDraft(
        request,
        new io.grpc.stub.StreamObserver<>() {
          @Override
          public void onNext(ClaimOriginalDraftResponse value) {
            response.set(value);
          }

          @Override
          public void onError(Throwable failure) {
            error.set(failure);
          }

          @Override
          public void onCompleted() {}
        });
    return new Result(response.get(), error.get());
  }

  private static void outsideSql() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }

  private static <T> T authorized(java.util.function.Supplier<T> action) {
    var context =
        io.grpc.Context.current()
            .withValue(
                AccountOriginalDraftOrderCredentials.CONTEXT_KEY,
                AccountOriginalDraftOrderCredentials.Credential.of(CREDENTIAL));
    var prior = context.attach();
    try {
      return peer("test", "game-design-service", action);
    } finally {
      context.detach(prior);
    }
  }

  private static <T> T peer(
      String namespace, String workload, java.util.function.Supplier<T> action) {
    var context =
        io.grpc.Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
                    .orElseThrow());
    var prior = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(prior);
    }
  }

  static DraftAuthorizationFenceBinding original() {
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "tenant-key", 2, "tenant-key", "NEW_GAME_ROW");
    var binding =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULES",
                    target.canonicalVersionId().toString(),
                    "GAMEPLAY_RULES",
                    "all",
                    "0")));
    return new DraftAuthorizationFenceBinding(
            UUID.randomUUID(),
            binding.requestId(),
            binding.commitId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            binding.baseCommitId(),
            "0",
            binding.canonicalBytes(),
            binding.canonicalBytes(),
            binding.digest(),
            List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                    UUID.randomUUID().toString(),
                    "1",
                    "1",
                    "account",
                    "1",
                    new byte[] {1})))
        .withRequiredOwners();
  }
}
