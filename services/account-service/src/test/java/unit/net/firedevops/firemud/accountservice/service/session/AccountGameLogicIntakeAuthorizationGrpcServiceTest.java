package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.firedevops.firemud.account.v1.GameLogicIntakeAuthorizationRequest;
import net.firedevops.firemud.account.v1.GameLogicIntakeAuthorizationResponse;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeAuthorizationCredentials;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationGrpcCodec;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeSourceReadScope;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Receiver boundary tests; no PostgreSQL or genuine authoring provenance is implied. */
class AccountGameLogicIntakeAuthorizationGrpcServiceTest {
  private static final String NAMESPACE = "test";
  private static final String CREDENTIAL = "original.creator.credential";

  private final AccountGameLogicIntakeAuthorizationService owner =
      mock(AccountGameLogicIntakeAuthorizationService.class);
  private final AccountHostedTermsService terms = mock(AccountHostedTermsService.class);
  private final AccountGameLogicIntakeAuthorizationGrpcService receiver =
      new AccountGameLogicIntakeAuthorizationGrpcService(owner, terms, NAMESPACE);

  @AfterEach
  void clearContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesExactPeerAndCredentialBeforeDecodingOrOwnerCapture() {
    assertThat(invokeAuthorize(null).code()).isEqualTo(Status.Code.UNAUTHENTICATED);
    for (String workload :
        List.of("world-management-service", "game-logic-service", "account-service"))
      assertThat(peer(NAMESPACE, workload, () -> invokeAuthorize(null)).code())
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(peer("other", "game-design-service", () -> invokeAuthorize(null)).code())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            peer(
                    NAMESPACE,
                    "game-design-service",
                    () -> invokeAuthorize(GameLogicIntakeAuthorizationRequest.getDefaultInstance()))
                .code())
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(
            authorized(
                    () ->
                        invokeAuthorize(
                            GameLogicIntakeAuthorizationRequest.newBuilder()
                                .setUnknownFields(
                                    com.google.protobuf.UnknownFieldSet.newBuilder()
                                        .addField(
                                            99,
                                            com.google.protobuf.UnknownFieldSet.Field.newBuilder()
                                                .addVarint(1)
                                                .build())
                                        .build())
                                .build()))
                .code())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            authorized(
                    () ->
                        invokeAuthorize(request().toBuilder().setTargetNamespace("other").build()))
                .code())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(owner, terms);
  }

  @Test
  void boundInterceptorAuthenticatesPeerBeforeReadingMethodLocalCredential() {
    var headers = mock(io.grpc.Metadata.class);
    assertThat(boundCode(headers)).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(peer(NAMESPACE, "world-management-service", () -> boundCode(headers)))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(peer("other", "game-design-service", () -> boundCode(headers)))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(headers, owner, terms);

    var malformed = new io.grpc.Metadata();
    malformed.put(
        io.grpc.Metadata.Key.of(
            AccountGameLogicIntakeAuthorizationCredentials.HEADER.name(),
            io.grpc.Metadata.BINARY_BYTE_MARSHALLER),
        new byte[] {(byte) 255});
    assertThat(peer(NAMESPACE, "game-design-service", () -> boundCode(malformed)))
        .isEqualTo(Status.Code.UNAUTHENTICATED);
  }

  @Test
  void capturesEnvironmentOnlyThroughSupplierAndEchoesExactSelectionWithoutCredential() {
    var safeRequest = request();
    var decoded = GameLogicIntakeAuthorizationGrpcCodec.fromRequest(safeRequest);
    var captured = mock(AccountHostedTermsService.CapturedEnvironmentBoundary.class);
    var binding = authorization(decoded);
    var captures = new AtomicInteger();
    when(terms.captureCurrentEnvironmentBoundary())
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              captures.incrementAndGet();
              return captured;
            });
    when(owner.authorizeWithEnvironmentCapture(
            eq(CREDENTIAL), eq(decoded.intakeRequestId()), eq(decoded.selected()), any()))
        .thenAnswer(
            call -> {
              Supplier<?> capture = call.getArgument(3);
              assertThat(capture.get()).isSameAs(captured);
              return binding;
            });

    var result = authorized(() -> invokeAuthorize(safeRequest));
    assertThat(result.error()).isNull();
    assertThat(captures).hasValue(1);
    var response = GameLogicIntakeAuthorizationGrpcCodec.fromResponse(decoded, result.response());
    assertThat(response.authorization().orElseThrow().canonicalBytes())
        .containsExactly(binding.canonicalBytes());
    assertThat(result.response().toString()).doesNotContain(CREDENTIAL);
    assertThat(safeRequest.toString()).doesNotContain(CREDENTIAL);
  }

  @Test
  void explicitRecoverAndAbortUseTheSameProtectedRequestWithoutEnvironmentCapture() {
    var wire = request();
    var decoded = GameLogicIntakeAuthorizationGrpcCodec.fromRequest(wire);
    var selected = decoded.selected();
    var scope =
        new GameLogicIntakeSourceReadScope(
            NAMESPACE,
            UUID.randomUUID(),
            UUID.randomUUID(),
            decoded.intakeRequestId(),
            UUID.randomUUID(),
            selected,
            "spiffe://firemud/ns/test/sa/account-service",
            GameLogicIntakeSourceReadScope.PURPOSE);
    var reserved =
        new AccountGameLogicIntakeSourceReadRecovery(
            scope, AccountGameLogicIntakeSourceReadRecovery.State.RESERVED, Optional.empty());
    var aborted =
        new AccountGameLogicIntakeSourceReadRecovery(
            scope, AccountGameLogicIntakeSourceReadRecovery.State.ABORTED, Optional.empty());
    when(owner.recover(CREDENTIAL, decoded.intakeRequestId(), selected)).thenReturn(reserved);
    when(owner.abortSourceRead(CREDENTIAL, decoded.intakeRequestId(), selected))
        .thenReturn(aborted);

    var recovered = authorized(() -> invoke(wire, Operation.RECOVER));
    var stopped = authorized(() -> invoke(wire, Operation.ABORT));
    assertThat(recovered.error()).isNull();
    assertThat(stopped.error()).isNull();
    assertThat(
            GameLogicIntakeAuthorizationGrpcCodec.fromResponse(decoded, recovered.response())
                .outcome())
        .isEqualTo(GameLogicIntakeAuthorizationEvidence.Outcome.RESERVED);
    assertThat(
            GameLogicIntakeAuthorizationGrpcCodec.fromResponse(decoded, stopped.response())
                .outcome())
        .isEqualTo(GameLogicIntakeAuthorizationEvidence.Outcome.ABORTED);
    org.mockito.Mockito.verify(owner).recover(CREDENTIAL, decoded.intakeRequestId(), selected);
    org.mockito.Mockito.verify(owner)
        .abortSourceRead(CREDENTIAL, decoded.intakeRequestId(), selected);
    verifyNoInteractions(terms);
  }

  @Test
  void rejectsAmbientSqlBeforeAnyAccountOwnerAccess() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThat(authorized(() -> invokeAuthorize(request())).code())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    verifyNoInteractions(owner, terms);
  }

  @Test
  void reportsOwnerFailuresBySanitizedStatusAndNeverEchoesCredential() {
    when(owner.authorizeWithEnvironmentCapture(eq(CREDENTIAL), any(), any(), any()))
        .thenThrow(Status.PERMISSION_DENIED.withDescription(CREDENTIAL).asRuntimeException());
    var denied = authorized(() -> invokeAuthorize(request()));
    assertThat(denied.code()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(denied.error().toString()).doesNotContain(CREDENTIAL);
    assertThat(denied.error().getCause()).isNull();
  }

  private record Result(GameLogicIntakeAuthorizationResponse response, Throwable error) {
    Status.Code code() {
      return Status.fromThrowable(error).getCode();
    }
  }

  private Result invokeAuthorize(GameLogicIntakeAuthorizationRequest request) {
    return invoke(request, Operation.AUTHORIZE);
  }

  private Result invoke(GameLogicIntakeAuthorizationRequest request, Operation operation) {
    var response = new AtomicReference<GameLogicIntakeAuthorizationResponse>();
    var error = new AtomicReference<Throwable>();
    var observer =
        new io.grpc.stub.StreamObserver<GameLogicIntakeAuthorizationResponse>() {
          @Override
          public void onNext(GameLogicIntakeAuthorizationResponse value) {
            response.set(value);
          }

          @Override
          public void onError(Throwable failure) {
            error.set(failure);
          }

          @Override
          public void onCompleted() {}
        };
    switch (operation) {
      case AUTHORIZE -> receiver.authorizeIntake(request, observer);
      case RECOVER -> receiver.recoverIntake(request, observer);
      case ABORT -> receiver.abortIntake(request, observer);
    }
    return new Result(response.get(), error.get());
  }

  private enum Operation {
    AUTHORIZE,
    RECOVER,
    ABORT
  }

  @SuppressWarnings("unchecked")
  private Status.Code boundCode(io.grpc.Metadata headers) {
    var method =
        (io.grpc.ServerMethodDefinition<
                GameLogicIntakeAuthorizationRequest, GameLogicIntakeAuthorizationResponse>)
            receiver.bindService().getMethods().stream()
                .filter(
                    candidate ->
                        candidate
                            .getMethodDescriptor()
                            .getFullMethodName()
                            .equals(
                                net.firedevops.firemud.account.v1
                                    .AccountGameLogicIntakeAuthorizationServiceGrpc
                                    .getAuthorizeIntakeMethod()
                                    .getFullMethodName()))
                .findFirst()
                .orElseThrow();
    io.grpc.ServerCall<GameLogicIntakeAuthorizationRequest, GameLogicIntakeAuthorizationResponse>
        call = mock(io.grpc.ServerCall.class);
    when(call.getMethodDescriptor()).thenReturn(method.getMethodDescriptor());
    method.getServerCallHandler().startCall(call, headers);
    var status = org.mockito.ArgumentCaptor.forClass(Status.class);
    org.mockito.Mockito.verify(call).close(status.capture(), org.mockito.ArgumentMatchers.any());
    return status.getValue().getCode();
  }

  private static <T> T authorized(Supplier<T> action) {
    var context =
        io.grpc.Context.current()
            .withValue(
                AccountGameLogicIntakeAuthorizationCredentials.CONTEXT_KEY,
                AccountGameLogicIntakeAuthorizationCredentials.Credential.of(CREDENTIAL));
    var prior = context.attach();
    try {
      return peer(NAMESPACE, "game-design-service", action);
    } finally {
      context.detach(prior);
    }
  }

  private static <T> T peer(String namespace, String workload, Supplier<T> action) {
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

  private static GameLogicIntakeAuthorizationRequest request() {
    var safe =
        GameLogicIntakeAuthorizationEvidence.Request.create(
            NAMESPACE, UUID.randomUUID(), selected());
    return GameLogicIntakeAuthorizationGrpcCodec.toRequest(safe);
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
                "0", UUID.randomUUID(), DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE, "{}")),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                "GAMEPLAY_RULE_SET",
                version.toString(),
                "GAMEPLAY_RULE_SET",
                "effective",
                "0")));
  }

  private static GameLogicIntakeAuthorizationBinding authorization(
      GameLogicIntakeAuthorizationEvidence.Request request) {
    var selected = request.selected();
    var actor = UUID.randomUUID();
    var source =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                java.util.Map.of(
                    "schema",
                    "game-design-gameplay-rule-source-snapshot/v1",
                    "bindingJson",
                    selected.canonicalJson(),
                    "bindingDigest",
                    selected.digest(),
                    "sourceEpoch",
                    "1",
                    "inheritedCommitId",
                    "",
                    "genesisReceiptId",
                    UUID.randomUUID().toString(),
                    "manifestJson",
                    GameplayRuleManifest.explicitEmpty().canonicalJson(),
                    "entries",
                    List.of())));
    return new GameLogicIntakeAuthorizationBinding(
        UUID.randomUUID(),
        UUID.randomUUID(),
        request.intakeRequestId(),
        actor,
        source,
        List.of(
            new DraftAuthorizationFenceBinding.SourceEvidence(
                DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                actor.toString(),
                "1",
                "1",
                null,
                null,
                new byte[] {1})));
  }
}
