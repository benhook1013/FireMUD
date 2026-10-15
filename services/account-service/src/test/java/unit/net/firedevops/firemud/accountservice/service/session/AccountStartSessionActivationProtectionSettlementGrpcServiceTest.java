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
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionActivationProtectionRequest;
import net.firedevops.firemud.account.v1.SettleOriginalStartSessionActivationProtectionResponse;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionActivationProtectionSettlementService;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Receiver boundary proof with synthetic protobufs; terminal outcomes remain World-owned. */
class AccountStartSessionActivationProtectionSettlementGrpcServiceTest {
  private static final String NAMESPACE = "world-runtime";

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesWorldBeforeCoordinatesOrTargetAreRead() {
    var owner = mock(AccountStartSessionActivationProtectionSettlementService.class);
    var service = new AccountStartSessionActivationProtectionSettlementGrpcService(owner, NAMESPACE);
    var malformed = SettleOriginalStartSessionActivationProtectionRequest.getDefaultInstance();

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
  void rejectsMissingDuplicateAndMismatchedTargetFieldsBeforeOwner() {
    var owner = mock(AccountStartSessionActivationProtectionSettlementService.class);
    var service = new AccountStartSessionActivationProtectionSettlementGrpcService(owner, NAMESPACE);
    var absent = SettleOriginalStartSessionActivationProtectionRequest.getDefaultInstance();
    var wrong =
        SettleOriginalStartSessionActivationProtectionRequest.newBuilder()
            .addTargetNamespace("default")
            .build();
    var duplicate =
        SettleOriginalStartSessionActivationProtectionRequest.newBuilder()
            .addTargetNamespace(NAMESPACE)
            .addTargetNamespace(NAMESPACE)
            .build();
    var missingCoordinates =
        SettleOriginalStartSessionActivationProtectionRequest.newBuilder()
            .addTargetNamespace(NAMESPACE)
            .build();

    assertCode(Status.Code.INVALID_ARGUMENT, invoke(service, identity("world-management-service"), absent).error());
    assertCode(Status.Code.PERMISSION_DENIED, invoke(service, identity("world-management-service"), wrong).error());
    assertCode(
        Status.Code.INVALID_ARGUMENT,
        invoke(service, identity("world-management-service"), duplicate).error());
    assertCode(
        Status.Code.INVALID_ARGUMENT,
        invoke(service, identity("world-management-service"), missingCoordinates).error());
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsAmbientTransactionBeforeRequestParsing() {
    var owner = mock(AccountStartSessionActivationProtectionSettlementService.class);
    var service = new AccountStartSessionActivationProtectionSettlementGrpcService(owner, NAMESPACE);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertCode(
        Status.Code.FAILED_PRECONDITION,
        invoke(
                service,
                identity("world-management-service"),
                SettleOriginalStartSessionActivationProtectionRequest.getDefaultInstance())
            .error());
    verifyNoInteractions(owner);
  }

  private static Call invoke(
      AccountStartSessionActivationProtectionSettlementGrpcService service,
      GrpcPeerIdentity peer,
      SettleOriginalStartSessionActivationProtectionRequest request) {
    AtomicReference<SettleOriginalStartSessionActivationProtectionResponse> response =
        new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    var observer =
        new StreamObserver<SettleOriginalStartSessionActivationProtectionResponse>() {
          @Override
          public void onNext(SettleOriginalStartSessionActivationProtectionResponse value) {
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
      service.settleOriginalStartSessionActivationProtection(request, observer);
    } else {
      var context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
      var prior = context.attach();
      try {
        service.settleOriginalStartSessionActivationProtection(request, observer);
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
    return GrpcPeerIdentity.parseUri(
            "spiffe://firemud/ns/" + namespace + "/sa/" + service)
        .orElseThrow();
  }

  private static void assertCode(Status.Code expected, Throwable failure) {
    assertThat(failure).isNotNull();
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected);
  }

  private record Call(
      SettleOriginalStartSessionActivationProtectionResponse response, Throwable error) {}
}
