package unit.net.firedevops.firemud.automationscripting.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.sourceintake.AutomationEmptySelectedSourceIntakeService;
import net.firedevops.firemud.automationscripting.sourceintake.AutomationSelectedSourceIntakeCommandGrpcService;
import net.firedevops.firemud.automationscripting.v1.RetainSelectedSourceRequest;
import net.firedevops.firemud.automationscripting.v1.RetainSelectedSourceResponse;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Receiver gate tests use mocked owner storage and do not claim physical mTLS or PostgreSQL proof.
 */
class AutomationSelectedSourceIntakeCommandGrpcServiceTest {
  private static final String NAMESPACE = "example";
  private static final String GAME_DESIGN_URI =
      "spiffe://firemud/ns/example/sa/game-design-service";

  @AfterEach
  void clearContexts() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void rejectsMissingOrWrongPeerBeforeRequestDecodeOrOwnerInvocation() {
    var owner = mock(AutomationEmptySelectedSourceIntakeService.class);
    var service = new AutomationSelectedSourceIntakeCommandGrpcService(owner, NAMESPACE);
    var response = new CapturingObserver();

    service.retainSelectedSource(RetainSelectedSourceRequest.getDefaultInstance(), response);

    assertThat(Status.fromThrowable(response.failure()).getCode())
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    verifyNoInteractions(owner);

    var wrongPeer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/example/sa/account-service", NAMESPACE, "account-service");
    var context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, wrongPeer);
    var previous = context.attach();
    try {
      var denied = new CapturingObserver();
      service.retainSelectedSource(RetainSelectedSourceRequest.getDefaultInstance(), denied);
      assertThat(Status.fromThrowable(denied.failure()).getCode())
          .isEqualTo(Status.Code.PERMISSION_DENIED);
      verifyNoInteractions(owner);
    } finally {
      context.detach(previous);
    }

    var wrongNamespacePeer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/other/sa/game-design-service", "other", "game-design-service");
    var wrongNamespaceContext =
        Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, wrongNamespacePeer);
    previous = wrongNamespaceContext.attach();
    try {
      var denied = new CapturingObserver();
      service.retainSelectedSource(
          RetainSelectedSourceRequest.newBuilder().setTargetNamespace(NAMESPACE).build(), denied);
      assertThat(Status.fromThrowable(denied.failure()).getCode())
          .isEqualTo(Status.Code.PERMISSION_DENIED);
      verifyNoInteractions(owner);
    } finally {
      wrongNamespaceContext.detach(previous);
    }
  }

  @Test
  void rejectsEndUserAndAmbientSqlBeforeMalformedEvidenceCanBeDecoded() {
    var owner = mock(AutomationEmptySelectedSourceIntakeService.class);
    var service = new AutomationSelectedSourceIntakeCommandGrpcService(owner, NAMESPACE);
    var peer = new GrpcPeerIdentity(GAME_DESIGN_URI, NAMESPACE, "game-design-service");
    var context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    var malformedRequest =
        RetainSelectedSourceRequest.newBuilder().setTargetNamespace(NAMESPACE).build();
    var previous = context.attach();
    try {
      SessionContext.setContext("101", List.of(), Map.of());
      var userDenied = new CapturingObserver();
      service.retainSelectedSource(malformedRequest, userDenied);
      assertThat(Status.fromThrowable(userDenied.failure()).getCode())
          .isEqualTo(Status.Code.PERMISSION_DENIED);
      SessionContext.clear();

      TransactionSynchronizationManager.setActualTransactionActive(true);
      var sqlDenied = new CapturingObserver();
      service.retainSelectedSource(malformedRequest, sqlDenied);
      assertThat(Status.fromThrowable(sqlDenied.failure()).getCode())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
      TransactionSynchronizationManager.clear();

      var malformed = new CapturingObserver();
      service.retainSelectedSource(malformedRequest, malformed);
      assertThat(Status.fromThrowable(malformed.failure()).getCode())
          .isEqualTo(Status.Code.INVALID_ARGUMENT);

      var mismatchedTarget = new CapturingObserver();
      service.retainSelectedSource(
          RetainSelectedSourceRequest.newBuilder().setTargetNamespace("other").build(),
          mismatchedTarget);
      assertThat(Status.fromThrowable(mismatchedTarget.failure()).getCode())
          .isEqualTo(Status.Code.PERMISSION_DENIED);
      verifyNoInteractions(owner);
    } finally {
      context.detach(previous);
    }
  }

  private static final class CapturingObserver
      implements StreamObserver<RetainSelectedSourceResponse> {
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    @Override
    public void onNext(RetainSelectedSourceResponse value) {
      throw new AssertionError("Malformed or unauthorized request unexpectedly returned evidence");
    }

    @Override
    public void onError(Throwable error) {
      failure.set(error);
    }

    @Override
    public void onCompleted() {
      throw new AssertionError("Malformed or unauthorized request unexpectedly completed");
    }

    Throwable failure() {
      return failure.get();
    }
  }
}
