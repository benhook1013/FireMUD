package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.Selector;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayBindingInventoryConflictException;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewFinalAdmissionReadRepository;
import net.firedevops.firemud.gamesession.security.CanonicalGameplayOriginalNewFinalAdmissionReadWorkloadGuard;
import net.firedevops.firemud.gamesession.v1.ReadOriginalNewFinalAdmissionResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Controlled invalid requests prove the caller is authenticated before request parsing or reads.
 */
class CanonicalGameplayOriginalNewFinalAdmissionReadGrpcServiceTest {
  private static final String NAMESPACE = "game-session-final-read-test";

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void missingPeerIsRejectedBeforeNullRequestParsingOrRepositoryRead() {
    CanonicalGameplayOriginalNewFinalAdmissionReadRepository repository = mock();
    var service = service(repository);
    StreamObserver<ReadOriginalNewFinalAdmissionResponse> observer = mock();

    service.readOriginalNewFinalAdmission(null, observer);

    ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(failure.capture());
    assertThat(Status.fromThrowable(failure.getValue()).getCode())
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    verifyNoInteractions(repository);
  }

  @Test
  void playerContextIsRejectedBeforeRequestParsingOrRepositoryRead() {
    CanonicalGameplayOriginalNewFinalAdmissionReadRepository repository = mock();
    var service = service(repository);
    StreamObserver<ReadOriginalNewFinalAdmissionResponse> observer = mock();
    SessionContext.setContext("player-1", List.of(), Map.of());
    GrpcPeerIdentity account = peer(NAMESPACE);

    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, account)
        .run(() -> service.readOriginalNewFinalAdmission(null, observer));

    ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(failure.capture());
    assertThat(Status.fromThrowable(failure.getValue()).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void unobservableAdmissionClockProducesRetryableUnavailable() {
    assertRepositoryFailureStatus(
        new IllegalStateException(
            "Current clock cannot observe the finalized original admission instant"),
        Status.Code.UNAVAILABLE);
  }

  @Test
  void expiredContinuityRemainsFailedPrecondition() {
    assertRepositoryFailureStatus(
        new CanonicalGameplayBindingInventoryConflictException(
            "Finalized original admission is outside its retained current time bounds"),
        Status.Code.FAILED_PRECONDITION);
  }

  private static void assertRepositoryFailureStatus(
      RuntimeException repositoryFailure, Status.Code expectedStatus) {
    CanonicalGameplayOriginalNewFinalAdmissionReadRepository repository = mock();
    var service = service(repository);
    StreamObserver<ReadOriginalNewFinalAdmissionResponse> observer = mock();
    String hash = "a".repeat(64);
    var selector =
        new Selector(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1L,
            hash,
            hash,
            1L,
            1L,
            hash,
            hash,
            hash,
            hash);
    when(repository.readActualOriginalFinalAdmission(selector, NAMESPACE))
        .thenThrow(repositoryFailure);
    var request = CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.toRequest(selector);

    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer(NAMESPACE))
        .run(() -> service.readOriginalNewFinalAdmission(request, observer));

    ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(failure.capture());
    assertThat(Status.fromThrowable(failure.getValue()).getCode()).isEqualTo(expectedStatus);
    verify(repository).readActualOriginalFinalAdmission(selector, NAMESPACE);
  }

  private static CanonicalGameplayOriginalNewFinalAdmissionReadGrpcService service(
      CanonicalGameplayOriginalNewFinalAdmissionReadRepository repository) {
    return new CanonicalGameplayOriginalNewFinalAdmissionReadGrpcService(
        repository,
        new CanonicalGameplayOriginalNewFinalAdmissionReadWorkloadGuard(NAMESPACE),
        NAMESPACE);
  }

  private static GrpcPeerIdentity peer(String namespace) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/account-service")
        .orElseThrow();
  }
}
