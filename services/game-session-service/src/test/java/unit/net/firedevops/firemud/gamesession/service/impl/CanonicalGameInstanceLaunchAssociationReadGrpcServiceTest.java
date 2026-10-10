package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameInstanceLaunchAssociationReadGrpcService;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationResponse;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic dispatch/denial proof; genuine owner and mTLS composition is a separate PG fixture. */
class CanonicalGameInstanceLaunchAssociationReadGrpcServiceTest {
  private static final String NAMESPACE = "world-runtime";
  private final GameSessionCanonicalInstanceLaunchRepository repository =
      mock(GameSessionCanonicalInstanceLaunchRepository.class);
  private final CanonicalGameInstanceLaunchAssociationReadGrpcService service =
      new CanonicalGameInstanceLaunchAssociationReadGrpcService(repository, NAMESPACE);

  @AfterEach
  void clearAmbientState() {
    SessionContext.clear();
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void missingAuthenticatedPeerRejectsEvenMalformedInputBeforeStorage() {
    var observer = new RecordingObserver();
    Context.ROOT.run(
        () ->
            service.getCanonicalGameInstanceLaunchAssociation(
                GetCanonicalGameInstanceLaunchAssociationRequest.getDefaultInstance(), observer));
    assertDenied(observer, Status.Code.UNAUTHENTICATED);
    verifyNoInteractions(repository);
  }

  @Test
  void wrongWorkloadAndNamespaceRejectBeforeDecodeOrStorage() {
    for (var uri :
        List.of(
            "spiffe://firemud/ns/world-runtime/sa/account-service",
            "spiffe://firemud/ns/other-runtime/sa/world-management-service")) {
      assertDenied(
          invoke(uri, GetCanonicalGameInstanceLaunchAssociationRequest.getDefaultInstance()),
          Status.Code.PERMISSION_DENIED);
    }
    verifyNoInteractions(repository);
  }

  @Test
  void endUserContextCannotBorrowWorldWorkloadAuthorization() {
    SessionContext.setContext("42", List.of("tenantAdmin"), Map.of());
    assertDenied(invokeWorld(wire()), Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void configuredOwnerNamespaceRejectsDifferentSelectorBeforeStorage() {
    assertDenied(
        invokeWorld(wire().toBuilder().setTargetNamespace("other-runtime").build()),
        Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void malformedAndUnknownFieldsDenyBeforeStorage() {
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    for (var request :
        List.of(
            GetCanonicalGameInstanceLaunchAssociationRequest.getDefaultInstance(),
            wire().toBuilder().setUnknownFields(unknown).build(),
            wire().toBuilder().setGameInstanceUuid(new UUID(0L, 0L).toString()).build())) {
      assertDenied(invokeWorld(request), Status.Code.INVALID_ARGUMENT);
    }
    verifyNoInteractions(repository);
  }

  @Test
  void ambientTransactionCannotTurnReadIntoCallerOwnedExecution() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertDenied(invokeWorld(wire()), Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(repository);
  }

  @Test
  void absentAssociationIsNotSuccessOrInferredAbort() {
    var expected = request();
    when(repository.readByLaunchIdentity(expected)).thenReturn(Optional.empty());
    assertDenied(invokeWorld(wire()), Status.Code.FAILED_PRECONDITION);
    verify(repository).readByLaunchIdentity(expected);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void unavailableOwnerCannotManufactureHistoricalEvidence() {
    var expected = request();
    when(repository.readByLaunchIdentity(expected))
        .thenThrow(new DataAccessException("test-only unavailable storage"));
    assertDenied(invokeWorld(wire()), Status.Code.UNAVAILABLE);
    verify(repository).readByLaunchIdentity(expected);
    verifyNoMoreInteractions(repository);
  }

  private RecordingObserver invokeWorld(GetCanonicalGameInstanceLaunchAssociationRequest wire) {
    return invoke("spiffe://firemud/ns/world-runtime/sa/world-management-service", wire);
  }

  private RecordingObserver invoke(
      String uri, GetCanonicalGameInstanceLaunchAssociationRequest wire) {
    var observer = new RecordingObserver();
    var peer = GrpcPeerIdentity.parseUri(uri).orElseThrow();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(() -> service.getCanonicalGameInstanceLaunchAssociation(wire, observer));
    return observer;
  }

  private static CanonicalGameInstanceLaunchAssociationReadEvidence.Request request() {
    return new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
        UUID.fromString("01111111-1111-4111-8111-111111111111"),
        NAMESPACE,
        UUID.fromString("02222222-2222-4222-8222-222222222222"),
        "world",
        UUID.fromString("03333333-3333-4333-8333-333333333333"),
        "test-original-operation",
        "test-original-descriptor",
        "sha256:" + "1".repeat(64),
        "sha256:" + "2".repeat(64),
        "sha256:" + "3".repeat(64));
  }

  private static GetCanonicalGameInstanceLaunchAssociationRequest wire() {
    return CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toRequest(request());
  }

  private static void assertDenied(RecordingObserver observer, Status.Code status) {
    assertThat(observer.response).isNull();
    assertThat(observer.completed).isFalse();
    assertThat(observer.error).isNotNull();
    assertThat(observer.error).isEqualTo(status);
  }

  private static final class RecordingObserver
      implements StreamObserver<GetCanonicalGameInstanceLaunchAssociationResponse> {
    private GetCanonicalGameInstanceLaunchAssociationResponse response;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(GetCanonicalGameInstanceLaunchAssociationResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
