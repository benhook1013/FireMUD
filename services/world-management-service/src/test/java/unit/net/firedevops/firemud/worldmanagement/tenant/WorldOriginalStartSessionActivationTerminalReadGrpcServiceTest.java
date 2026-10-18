package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalEvidence;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalReadGrpcCodec;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalReadInput;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceActivationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceActivationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldOriginalStartSessionActivationTerminalReadGrpcService;
import net.firedevops.firemud.worldmanagement.v1.ReadOriginalStartSessionActivationTerminalEvidenceRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadOriginalStartSessionActivationTerminalEvidenceResponse;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Receiver denial ordering only; synthetic TLS context is not authenticated transport proof. */
class WorldOriginalStartSessionActivationTerminalReadGrpcServiceTest {
  @AfterEach
  void clearContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void rejectsWrongOrMissingAccountPeerBeforeRequestDecodeOrOwnerRead() {
    var owner = mock(WorldCanonicalInstanceActivationService.class);
    var service =
        new WorldOriginalStartSessionActivationTerminalReadGrpcService(owner, "world-runtime");

    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(
            service,
            null,
            ReadOriginalStartSessionActivationTerminalEvidenceRequest.getDefaultInstance()));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(service, peer("spiffe://firemud/ns/other-runtime/sa/account-service"), null));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(service, peer("spiffe://firemud/ns/world-runtime/sa/game-session-service"), null));
    verifyNoInteractions(owner);

    assertCode(
        Status.Code.INVALID_ARGUMENT,
        invoke(service, peer("spiffe://firemud/ns/world-runtime/sa/account-service"), null));
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsEndUserAndAmbientSqlBeforeDecodingOrOwnerRead() {
    var owner = mock(WorldCanonicalInstanceActivationService.class);
    var service =
        new WorldOriginalStartSessionActivationTerminalReadGrpcService(owner, "world-runtime");
    SessionContext.setContext("authenticated-user", java.util.List.of(), java.util.Map.of());
    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(service, peer("spiffe://firemud/ns/world-runtime/sa/account-service"), null));
    SessionContext.clear();

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        invoke(service, peer("spiffe://firemud/ns/world-runtime/sa/account-service"), null));
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsSynchronizationOnlyScopeBeforeOwnerRead() throws Exception {
    var fixture = WorldOriginalStartSessionActivationTransportTestSupport.fixture();
    var request =
        WorldOriginalStartSessionActivationTerminalReadGrpcCodec.toRequest(
            new WorldOriginalStartSessionActivationTerminalReadInput(
                java.util.UUID.fromString("a3a3a3a3-a3a3-43a3-83a3-a3a3a3a3a3a3"),
                fixture.protection()),
            "test");
    var owner = mock(WorldCanonicalInstanceActivationService.class);
    var service = new WorldOriginalStartSessionActivationTerminalReadGrpcService(owner, "test");
    TransactionSynchronizationManager.initSynchronization();
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();

    var failure = invoke(service, peer("spiffe://firemud/ns/test/sa/account-service"), request);

    assertFailure(
        failure,
        Status.Code.FAILED_PRECONDITION,
        "World terminal reads require independent owner operations");
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
    verifyNoInteractions(owner);
  }

  @Test
  void missingTerminalReceiptReturnsExactPendingDescriptionWithoutEvidence() throws Exception {
    var fixture = WorldOriginalStartSessionActivationTransportTestSupport.fixture();
    var request =
        WorldOriginalStartSessionActivationTerminalReadGrpcCodec.toRequest(
            new WorldOriginalStartSessionActivationTerminalReadInput(
                java.util.UUID.fromString("a3a3a3a3-a3a3-43a3-83a3-a3a3a3a3a3a3"),
                fixture.protection()),
            "test");
    var owner = mock(WorldCanonicalInstanceActivationService.class);
    when(owner.readTerminalEvidence(any())).thenReturn(Optional.empty());
    var service = new WorldOriginalStartSessionActivationTerminalReadGrpcService(owner, "test");

    var failure = invoke(service, peer("spiffe://firemud/ns/test/sa/account-service"), request);

    assertFailure(
        failure,
        Status.Code.FAILED_PRECONDITION,
        "World has no exact terminal receipt; Account protection remains pending");
    verify(owner).readTerminalEvidence(any());
  }

  @Test
  void sanitizesStorageAndUnexpectedOwnerFailures() throws Exception {
    var fixture = WorldOriginalStartSessionActivationTransportTestSupport.fixture();
    var request =
        WorldOriginalStartSessionActivationTerminalReadGrpcCodec.toRequest(
            new WorldOriginalStartSessionActivationTerminalReadInput(
                java.util.UUID.fromString("a3a3a3a3-a3a3-43a3-83a3-a3a3a3a3a3a3"),
                fixture.protection()),
            "test");
    var caller = peer("spiffe://firemud/ns/test/sa/account-service");
    List<FailureCase> cases =
        List.of(
            new FailureCase(
                new TransientDataAccessResourceException("private transient storage detail"),
                Status.Code.UNAVAILABLE,
                "World activation terminal storage is unavailable"),
            new FailureCase(
                new DataAccessException("private jOOQ storage detail"),
                Status.Code.INTERNAL,
                "World activation terminal storage failed"),
            new FailureCase(
                new IllegalStateException("private unexpected detail"),
                Status.Code.INTERNAL,
                "World activation terminal read failed"));

    for (FailureCase testCase : cases) {
      var owner = mock(WorldCanonicalInstanceActivationService.class);
      when(owner.readTerminalEvidence(any())).thenThrow(testCase.ownerFailure());
      var service = new WorldOriginalStartSessionActivationTerminalReadGrpcService(owner, "test");

      var failure = invoke(service, caller, request);

      assertFailure(failure, testCase.expectedCode(), testCase.expectedDescription());
      verify(owner).readTerminalEvidence(any());
    }
  }

  @Test
  void rejectsTerminalResponseBoundToDifferentAccountProtection() throws Exception {
    var fixture = WorldOriginalStartSessionActivationTransportTestSupport.fixture();
    var input =
        new WorldOriginalStartSessionActivationTerminalReadInput(
            java.util.UUID.fromString("a3a3a3a3-a3a3-43a3-83a3-a3a3a3a3a3a3"),
            fixture.protection());
    var request = WorldOriginalStartSessionActivationTerminalReadGrpcCodec.toRequest(input, "test");
    var protection = fixture.protection();
    var alternateProtection =
        AccountStartSessionActivationProtectionEvidence.create(
            protection.request(),
            java.util.UUID.fromString("55555555-5555-4555-8555-555555555555"),
            protection.accountProtectionFence() + 1L,
            protection.acquiredAt(),
            protection.originalSourceCaptureReferenceBytes(),
            protection.originalSourceCaptureReferenceSha256(),
            protection.sourceEvidenceVector());
    var mismatchedTerminal =
        WorldOriginalStartSessionActivationTerminalEvidence.create(
            alternateProtection,
            fixture.terminal().activationResult(),
            fixture.terminal().worldActivationFence() + 1L);
    var owner = mock(WorldCanonicalInstanceActivationService.class);
    when(owner.readTerminalEvidence(any())).thenReturn(Optional.of(mismatchedTerminal));
    var service = new WorldOriginalStartSessionActivationTerminalReadGrpcService(owner, "test");

    var failure = invoke(service, peer("spiffe://firemud/ns/test/sa/account-service"), request);

    assertFailure(
        failure,
        Status.Code.FAILED_PRECONDITION,
        "World terminal receipt differs from the exact Account protection read");
    verify(owner).readTerminalEvidence(any());
  }

  @Test
  void mapsActivationIdentityConflictToFailedPreconditionWithoutReturningTerminalEvidence()
      throws Exception {
    var fixture = WorldOriginalStartSessionActivationTransportTestSupport.fixture();
    var input =
        new WorldOriginalStartSessionActivationTerminalReadInput(
            java.util.UUID.fromString("a3a3a3a3-a3a3-43a3-83a3-a3a3a3a3a3a3"),
            fixture.protection());
    var request = WorldOriginalStartSessionActivationTerminalReadGrpcCodec.toRequest(input, "test");
    var owner = mock(WorldCanonicalInstanceActivationService.class);
    when(owner.readTerminalEvidence(any()))
        .thenThrow(
            new WorldCanonicalInstanceActivationRepository.ActivationConflictException(
                "private repository conflict detail"));
    var service = new WorldOriginalStartSessionActivationTerminalReadGrpcService(owner, "test");

    var failure = invoke(service, peer("spiffe://firemud/ns/test/sa/account-service"), request);

    assertFailure(
        failure,
        Status.Code.FAILED_PRECONDITION,
        "World activation terminal evidence conflicts with the exact Account protection read");
    verify(owner).readTerminalEvidence(any());
  }

  private static FailureObservation invoke(
      WorldOriginalStartSessionActivationTerminalReadGrpcService service,
      GrpcPeerIdentity peer,
      ReadOriginalStartSessionActivationTerminalEvidenceRequest request) {
    var observer = new Collector();
    Context context = Context.current();
    if (peer != null) context = context.withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.readOriginalStartSessionActivationTerminalEvidence(request, observer);
    } finally {
      context.detach(previous);
    }
    return new FailureObservation(
        observer.failureCode,
        observer.failureWasStatusRuntimeException,
        observer.failureDescription,
        observer.failureCause,
        observer.failureTrailers,
        observer.responseCount,
        observer.completionCount);
  }

  private static GrpcPeerIdentity peer(String uri) {
    return GrpcPeerIdentity.parseUri(uri).orElseThrow();
  }

  private static void assertCode(Status.Code expected, FailureObservation failure) {
    assertThat(failure.failureWasStatusRuntimeException()).isTrue();
    assertThat(failure.failureCode()).isEqualTo(expected);
  }

  private static void assertFailure(
      FailureObservation failure, Status.Code expectedCode, String expectedDescription) {
    assertThat(failure.failureWasStatusRuntimeException()).isTrue();
    assertThat(failure.failureCode()).isEqualTo(expectedCode);
    assertThat(failure.failureDescription()).isEqualTo(expectedDescription);
    assertThat(failure.failureCause()).isNull();
    assertThat(failure.failureTrailers()).isNull();
    assertThat(failure.responseCount()).isZero();
    assertThat(failure.completionCount()).isZero();
  }

  private record FailureObservation(
      Status.Code failureCode,
      boolean failureWasStatusRuntimeException,
      String failureDescription,
      Throwable failureCause,
      Metadata failureTrailers,
      int responseCount,
      int completionCount) {}

  private record FailureCase(
      RuntimeException ownerFailure, Status.Code expectedCode, String expectedDescription) {}

  private static final class Collector
      implements StreamObserver<ReadOriginalStartSessionActivationTerminalEvidenceResponse> {
    private Status.Code failureCode;
    private boolean failureWasStatusRuntimeException;
    private String failureDescription;
    private Throwable failureCause;
    private Metadata failureTrailers;
    private int responseCount;
    private int completionCount;

    @Override
    public void onNext(ReadOriginalStartSessionActivationTerminalEvidenceResponse value) {
      responseCount++;
    }

    @Override
    public void onError(Throwable failure) {
      Status status = Status.fromThrowable(failure);
      failureCode = status.getCode();
      failureDescription = status.getDescription();
      failureWasStatusRuntimeException = failure instanceof StatusRuntimeException;
      failureCause = failure.getCause();
      if (failure instanceof StatusRuntimeException statusFailure) {
        failureTrailers = statusFailure.getTrailers();
      }
    }

    @Override
    public void onCompleted() {
      completionCount++;
    }
  }
}
