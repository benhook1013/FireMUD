package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AcquireOriginalStartSessionActivationProtectionRequest;
import net.firedevops.firemud.account.v1.AcquireOriginalStartSessionActivationProtectionResponse;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionActivationProtectionAcquisitionGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionActivationProtectionAcquisitionService;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Receiver boundary proof with synthetic protobufs; no owner state or authority is claimed. */
class AccountStartSessionActivationProtectionAcquisitionGrpcServiceTest {
  private static final String NAMESPACE = "world-runtime";
  private static final String WORLD_URI =
      "spiffe://firemud/ns/world-runtime/sa/world-management-service";

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesWorldBeforeTargetOrEvidenceDecoding() {
    var owner = mock(AccountStartSessionActivationProtectionAcquisitionService.class);
    var service =
        new AccountStartSessionActivationProtectionAcquisitionGrpcService(owner, NAMESPACE);
    var malformed = AcquireOriginalStartSessionActivationProtectionRequest.getDefaultInstance();

    assertCode(Status.Code.UNAUTHENTICATED, invoke(service, null, malformed).error());
    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(service, identity("game-session-service"), malformed).error());
    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(service, identity("world-management-service", "other-runtime"), malformed).error());

    SessionContext.setContext("caller", List.of(), Map.of());
    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(service, identity("world-management-service"), malformed).error());
    verifyNoInteractions(owner);
  }

  @Test
  void requiresSingleExactTargetAndClosedCanonicalRequestBeforeOwner() {
    var owner = mock(AccountStartSessionActivationProtectionAcquisitionService.class);
    var service =
        new AccountStartSessionActivationProtectionAcquisitionGrpcService(owner, NAMESPACE);
    var absent = AcquireOriginalStartSessionActivationProtectionRequest.getDefaultInstance();
    var wrongNamespace =
        AcquireOriginalStartSessionActivationProtectionRequest.newBuilder()
            .addTargetNamespace("default")
            .build();
    var missingBinding =
        AcquireOriginalStartSessionActivationProtectionRequest.newBuilder()
            .addTargetNamespace(NAMESPACE)
            .build();

    assertCode(
        Status.Code.INVALID_ARGUMENT,
        invoke(service, identity("world-management-service"), absent).error());
    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(service, identity("world-management-service"), wrongNamespace).error());
    assertCode(
        Status.Code.INVALID_ARGUMENT,
        invoke(service, identity("world-management-service"), missingBinding).error());

    verifyNoInteractions(owner);
  }

  @Test
  void rejectsAmbientTransactionBeforeRequestParsing() {
    var owner = mock(AccountStartSessionActivationProtectionAcquisitionService.class);
    var service =
        new AccountStartSessionActivationProtectionAcquisitionGrpcService(owner, NAMESPACE);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertCode(
        Status.Code.FAILED_PRECONDITION,
        invoke(
                service,
                identity("world-management-service"),
                AcquireOriginalStartSessionActivationProtectionRequest.getDefaultInstance())
            .error());

    verifyNoInteractions(owner);
  }

  private static Call invoke(
      AccountStartSessionActivationProtectionAcquisitionGrpcService service,
      GrpcPeerIdentity peer,
      AcquireOriginalStartSessionActivationProtectionRequest request) {
    AtomicReference<AcquireOriginalStartSessionActivationProtectionResponse> response =
        new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    var observer =
        new StreamObserver<AcquireOriginalStartSessionActivationProtectionResponse>() {
          @Override
          public void onNext(AcquireOriginalStartSessionActivationProtectionResponse value) {
            response.set(value);
          }

          @Override
          public void onError(Throwable error) {
            failure.set(error);
          }

          @Override
          public void onCompleted() {}
        };
    if (peer == null) {
      service.acquireOriginalStartSessionActivationProtection(request, observer);
    } else {
      var context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
      var prior = context.attach();
      try {
        service.acquireOriginalStartSessionActivationProtection(request, observer);
      } finally {
        context.detach(prior);
      }
    }
    return new Call(response.get(), failure.get());
  }

  private static GrpcPeerIdentity identity(String service) {
    return identity(service, NAMESPACE);
  }

  private static GrpcPeerIdentity identity(String service, String namespace) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
        .orElseThrow();
  }

  private static void assertCode(Status.Code expected, Throwable failure) {
    assertThat(failure).isNotNull();
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected);
  }

  private record Call(
      AcquireOriginalStartSessionActivationProtectionResponse response, Throwable error) {}
}
