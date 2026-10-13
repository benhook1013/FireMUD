package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.service.impl.GameSessionOriginalStartSessionSelectionPinService;
import net.firedevops.firemud.gamesession.service.impl.HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService;
import net.firedevops.firemud.gamesession.v1.ReadHistoricalOriginalStartSessionOwnerEvidenceRequest;
import net.firedevops.firemud.gamesession.v1.ReadHistoricalOriginalStartSessionOwnerEvidenceResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HistoricalOriginalStartSessionOwnerEvidenceReadGrpcServiceTest {
  private static final String NAMESPACE = "gameplay";
  private final GameSessionOriginalStartSessionSelectionPinService service =
      mock(GameSessionOriginalStartSessionSelectionPinService.class);
  private final HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService receiver =
      new HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService(service, NAMESPACE);

  @AfterEach
  void clearCallerContext() {
    SessionContext.clear();
  }

  @Test
  void onlyExactSameNamespaceWorldWorkloadMayReachRequestDecoding() {
    for (String uri :
        List.of(
            "spiffe://firemud/ns/gameplay/sa/account-service",
            "spiffe://firemud/ns/other/sa/world-management-service",
            "spiffe://firemud/ns/gameplay/sa/game-session-service")) {
      RecordingObserver observer = invoke(uri, false);
      assertThat(observer.status()).isEqualTo(Status.Code.PERMISSION_DENIED);
    }
    RecordingObserver missingPeer = invoke(null, false);
    assertThat(missingPeer.status()).isEqualTo(Status.Code.UNAUTHENTICATED);
    RecordingObserver endUserContext =
        invoke("spiffe://firemud/ns/gameplay/sa/world-management-service", true);
    assertThat(endUserContext.status()).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(service);
  }

  @Test
  void authenticatedWorldPeerIsStillRequiredToSendTheClosedCanonicalRequest() {
    RecordingObserver observer =
        invoke("spiffe://firemud/ns/gameplay/sa/world-management-service", false);
    assertThat(observer.status()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(service);
  }

  private RecordingObserver invoke(String uri, boolean endUserContext) {
    RecordingObserver observer = new RecordingObserver();
    Runnable call =
        () ->
            receiver.readHistoricalOriginalStartSessionOwnerEvidence(
                ReadHistoricalOriginalStartSessionOwnerEvidenceRequest.getDefaultInstance(),
                observer);
    if (uri == null) {
      call.run();
      return observer;
    }
    GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(uri).orElseThrow();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(
            () -> {
              if (endUserContext) {
                SessionContext.setContext("42", List.of("tenantAdmin"), java.util.Map.of());
              }
              call.run();
            });
    return observer;
  }

  private static final class RecordingObserver
      implements StreamObserver<ReadHistoricalOriginalStartSessionOwnerEvidenceResponse> {
    private final List<ReadHistoricalOriginalStartSessionOwnerEvidenceResponse> responses =
        new ArrayList<>();
    private Status.Code errorCode;

    @Override
    public void onNext(ReadHistoricalOriginalStartSessionOwnerEvidenceResponse response) {
      responses.add(response);
    }

    @Override
    public void onError(Throwable throwable) {
      errorCode = Status.fromThrowable(throwable).getCode();
    }

    @Override
    public void onCompleted() {}

    Status.Code status() {
      return errorCode;
    }
  }
}
