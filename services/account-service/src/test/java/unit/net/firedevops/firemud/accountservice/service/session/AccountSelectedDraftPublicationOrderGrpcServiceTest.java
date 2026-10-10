package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Status;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AuthorizeSelectedPublicationRequest;
import net.firedevops.firemud.account.v1.AuthorizeSelectedPublicationResponse;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AccountSelectedPublicationOrderCredentials;
import net.firedevops.firemud.common.publication.AccountSelectedPublicationOrderGrpcCodec;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Context-level receiver units, not mounted-certificate or real Account currentness proof. */
class AccountSelectedDraftPublicationOrderGrpcServiceTest {
  private static final String CREDENTIAL = "original.secret.credential";
  private final AccountSelectedDraftPublicationOrderService owner =
      mock(AccountSelectedDraftPublicationOrderService.class);
  private final AccountHostedTermsService terms = mock(AccountHostedTermsService.class);
  private final AccountSelectedDraftPublicationOrderGrpcService receiver =
      new AccountSelectedDraftPublicationOrderGrpcService(owner, terms, "test");

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
    assertThat(
            authorized(() -> invoke(AuthorizeSelectedPublicationRequest.getDefaultInstance()))
                .code())
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
            AccountSelectedPublicationOrderCredentials.HEADER.name(),
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
        (io.grpc.ServerMethodDefinition<
                AuthorizeSelectedPublicationRequest, AuthorizeSelectedPublicationResponse>)
            receiver.bindService().getMethods().iterator().next();
    io.grpc.ServerCall<AuthorizeSelectedPublicationRequest, AuthorizeSelectedPublicationResponse>
        call = mock(io.grpc.ServerCall.class);
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
  void
      automaticallyCapturesAccountEnvironmentAndHandsOffOriginalCredentialAndSelectionOutsideSql() {
    var request = request();
    var decoded = AccountSelectedPublicationOrderGrpcCodec.fromRequest(request, CREDENTIAL);
    var captured = mock(AccountHostedTermsService.CapturedEnvironmentBoundary.class);
    UUID actor = UUID.randomUUID();
    var binding =
        new AccountPublicationAuthorizationBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new AccountPublicationAuthorizationBinding.PreallocationInput(
                actor, decoded.selection()),
            List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                    actor.toString(),
                    "1",
                    "1",
                    null,
                    null,
                    new byte[] {1})));
    when(terms.captureCurrentEnvironmentBoundary())
        .thenAnswer(
            call -> {
              outsideSql();
              return captured;
            });
    when(owner.authorize(eq(CREDENTIAL), any(), same(captured)))
        .thenAnswer(
            call -> {
              outsideSql();
              assertThat(
                      call.getArgument(1, AuthoredDraftPublishSelectionBinding.class)
                          .canonicalBytes())
                  .isEqualTo(decoded.originalSelection());
              return binding;
            });
    var result = authorized(() -> invoke(request));
    assertThat(result.error()).isNull();
    assertThat(
            AccountSelectedPublicationOrderGrpcCodec.fromResponse(decoded, result.response())
                .canonicalBytes())
        .isEqualTo(binding.canonicalBytes());
    assertThat(result.response().toString()).doesNotContain(CREDENTIAL);
  }

  @Test
  void unavailableCaptureReturnsSanitizedFailure() {
    when(terms.captureCurrentEnvironmentBoundary())
        .thenThrow(new IllegalStateException(CREDENTIAL));
    var denied = authorized(() -> invoke(request()));
    assertThat(denied.code()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(denied.error().toString()).doesNotContain(CREDENTIAL);
    verifyNoInteractions(owner);
  }

  @Test
  void secretBearingOwnerStatusReturnsOnlyItsCodeAndSanitizedDescription() {
    var captured = mock(AccountHostedTermsService.CapturedEnvironmentBoundary.class);
    when(terms.captureCurrentEnvironmentBoundary()).thenReturn(captured);
    when(owner.authorize(eq(CREDENTIAL), any(), same(captured)))
        .thenThrow(Status.PERMISSION_DENIED.withDescription(CREDENTIAL).asRuntimeException());
    var denied = authorized(() -> invoke(request()));
    assertThat(denied.code()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(denied.error().toString()).doesNotContain(CREDENTIAL);
    assertThat(denied.error().getCause()).isNull();
  }

  private record Result(AuthorizeSelectedPublicationResponse response, Throwable error) {
    Status.Code code() {
      return Status.fromThrowable(error).getCode();
    }
  }

  private Result invoke(AuthorizeSelectedPublicationRequest request) {
    var response = new AtomicReference<AuthorizeSelectedPublicationResponse>();
    var error = new AtomicReference<Throwable>();
    receiver.authorizeSelectedPublication(
        request,
        new io.grpc.stub.StreamObserver<>() {
          @Override
          public void onNext(AuthorizeSelectedPublicationResponse value) {
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
                AccountSelectedPublicationOrderCredentials.CONTEXT_KEY,
                AccountSelectedPublicationOrderCredentials.Credential.of(CREDENTIAL));
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

  private static AuthorizeSelectedPublicationRequest request() {
    UUID tenant = UUID.randomUUID(), version = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, version, 19, "tenant-key", 42, "tenant-key", "NEW_GAME_ROW");
    var commit =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "base-1",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0", UUID.randomUUID(), DraftCommitBinding.Owner.WORLD_MANAGEMENT, "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "WORLD_TEMPLATE",
                    "world-1",
                    "ROOM_SCOPE",
                    "room-1",
                    "0")));
    var selected =
        AuthoredDraftPublishSelectionBinding.capture(
            new AuthoredDraftPublishSelectionBinding.PublishIntent(
                tenant,
                version,
                "publication-request",
                "5",
                "fixture",
                commit.requestId(),
                commit.commitId(),
                commit.digest()),
            target,
            commit,
            new AuthoredDraftPublishSelectionBinding.VisibilityFence(
                target,
                commit.requestId(),
                commit.commitId(),
                commit.digest(),
                "[]",
                OffsetDateTime.parse("2026-10-01T00:00:00Z")));
    return AccountSelectedPublicationOrderGrpcCodec.toRequest(
        AccountSelectedPublicationOrderGrpcCodec.Request.create("test", selected, CREDENTIAL));
  }
}
