package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.ReadHeldPublicationAuthorizationRequest;
import net.firedevops.firemud.account.v1.ReadHeldPublicationAuthorizationResponse;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadEvidence;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Context-level owner tests; supplied peer contexts are not actual TLS-positive proof. */
class AccountPublicationAuthorizationReadServiceTest {
  private final AccountPublicationAuthorizationRepository repository =
      mock(AccountPublicationAuthorizationRepository.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final TransactionStatus status = mock(TransactionStatus.class);
  private final AccountPublicationAuthorizationReadService service =
      new AccountPublicationAuthorizationReadService(repository, transactions, "test");

  @AfterEach
  void clear() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void commitsAnIndependentOwnerReadBeforeReturningHeld() {
    var request = request("test");
    when(transactions.getTransaction(any())).thenReturn(status);
    asPeer("test", "game-design-service", () -> service.requireHeld(request));
    verify(repository).readHeld(any());
    var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactions).getTransaction(definition.capture());
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(definition.getValue().isReadOnly()).isFalse();
    verify(transactions).commit(status);
  }

  @Test
  void rejectsUnverifiedWrongWorkloadCrossNamespaceAndAmbientTransactionBeforeLookup() {
    var request = request("test");
    assertCode(Status.Code.UNAUTHENTICATED, () -> service.requireHeld(request));
    for (String workload :
        List.of("account-service", "world-management-service", "spring-cloud-gateway")) {
      assertCode(
          Status.Code.PERMISSION_DENIED,
          () -> asPeer("test", workload, () -> service.requireHeld(request)));
    }
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () -> asPeer("other", "game-design-service", () -> service.requireHeld(request)));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () -> asPeer("test", "game-design-service", () -> service.requireHeld(request("other"))));
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> asPeer("test", "game-design-service", () -> service.requireHeld(request)));
    verifyNoInteractions(repository, transactions);
  }

  @Test
  void absentOrChangedOwnerStateCannotProduceHeldAndStorageFailureFailsClosed() {
    var request = request("test");
    when(transactions.getTransaction(any())).thenReturn(status);
    doThrow(new IllegalArgumentException("absent or changed")).when(repository).readHeld(any());
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> asPeer("test", "game-design-service", () -> service.requireHeld(request)));
    doThrow(new org.jooq.exception.DataAccessException("offline")).when(repository).readHeld(any());
    assertCode(
        Status.Code.UNAVAILABLE,
        () -> asPeer("test", "game-design-service", () -> service.requireHeld(request)));
  }

  @Test
  void grpcAuthenticatesBeforeDecodingMalformedCallerCarriers() {
    var endpoint = new AccountPublicationAuthorizationReadGrpcService(service, "test");
    var error = new AtomicReference<Throwable>();
    StreamObserver<ReadHeldPublicationAuthorizationResponse> observer =
        new StreamObserver<>() {
          @Override
          public void onNext(ReadHeldPublicationAuthorizationResponse value) {
            throw new AssertionError("Malformed request cannot return HELD");
          }

          @Override
          public void onError(Throwable failure) {
            error.set(failure);
          }

          @Override
          public void onCompleted() {
            throw new AssertionError("Malformed request cannot complete");
          }
        };
    endpoint.readHeldPublicationAuthorization(
        ReadHeldPublicationAuthorizationRequest.getDefaultInstance(), observer);
    assertThat(Status.fromThrowable(error.get()).getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    asPeer(
        "test",
        "world-management-service",
        () ->
            endpoint.readHeldPublicationAuthorization(
                ReadHeldPublicationAuthorizationRequest.getDefaultInstance(), observer));
    assertThat(Status.fromThrowable(error.get()).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    asPeer(
        "other",
        "game-design-service",
        () ->
            endpoint.readHeldPublicationAuthorization(
                ReadHeldPublicationAuthorizationRequest.getDefaultInstance(), observer));
    assertThat(Status.fromThrowable(error.get()).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    asPeer(
        "test",
        "game-design-service",
        () ->
            endpoint.readHeldPublicationAuthorization(
                ReadHeldPublicationAuthorizationRequest.getDefaultInstance(), observer));
    assertThat(Status.fromThrowable(error.get()).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(repository, transactions);
  }

  private static void assertCode(Status.Code code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(code));
  }

  private static void asPeer(String namespace, String workload, Runnable action) {
    var peer =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
            .orElseThrow();
    Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(action);
  }

  private static AccountPublicationAuthorizationReadEvidence.Request request(String namespace) {
    UUID actor = UUID.randomUUID(),
        tenant = UUID.randomUUID(),
        version = UUID.randomUUID(),
        revision = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, version, 17, "test-tenant", 11, "test-tenant", "NEW_GAME_ROW");
    var draft =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "base-1",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0", revision, DraftCommitBinding.Owner.WORLD_MANAGEMENT, "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "ROOM",
                    "room",
                    "ROOM_SCOPE",
                    "room",
                    "0")));
    var selection =
        AuthoredDraftPublishSelectionBinding.capture(
            new AuthoredDraftPublishSelectionBinding.PublishIntent(
                tenant,
                version,
                "publication-request",
                "5",
                "proof",
                draft.requestId(),
                draft.commitId(),
                draft.digest()),
            target,
            draft,
            new AuthoredDraftPublishSelectionBinding.VisibilityFence(
                target,
                draft.requestId(),
                draft.commitId(),
                draft.digest(),
                "[]",
                OffsetDateTime.parse("2026-10-01T00:00:00Z")));
    var binding =
        new AccountPublicationAuthorizationBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new AccountPublicationAuthorizationBinding.PreallocationInput(actor, selection),
            List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                    actor.toString(),
                    "1",
                    "1",
                    null,
                    null,
                    new byte[] {1})));
    return AccountPublicationAuthorizationReadEvidence.Request.create(namespace, binding);
  }
}
