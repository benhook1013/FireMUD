package net.firedevops.firemud.gamedesign.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.publication.StartSessionTemplateAssociationReadService;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationResponse;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class GameDesignStartSessionTemplateAssociationReadGrpcServiceTest {
  private final StartSessionTemplateAssociationReadService owner =
      mock(StartSessionTemplateAssociationReadService.class);
  private final GameDesignStartSessionTemplateAssociationReadGrpcService service =
      new GameDesignStartSessionTemplateAssociationReadGrpcService(owner, "world-runtime");

  @Test
  void authenticatesExactGameSessionPeerBeforeDecodingRequest() {
    var observer = new Collector();
    service.readStartSessionTemplateAssociation(
        ReadStartSessionTemplateAssociationRequest.getDefaultInstance(), observer);
    assertThat(observer.error).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsDifferentWorkloadBeforeDecodingRequest() {
    var observer =
        invokeAs(
            ReadStartSessionTemplateAssociationRequest.getDefaultInstance(), "account-service");
    assertThat(observer.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
    verifyNoInteractions(owner);
  }

  @Test
  void exactPeerIsAuthenticatedBeforeMalformedRequestIsRejected() {
    var observer =
        invokeAs(
            ReadStartSessionTemplateAssociationRequest.getDefaultInstance(),
            "game-session-service");
    assertThat(observer.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
    verifyNoInteractions(owner);
  }

  @Test
  void completesAfterDeliveringSuccessfulAssociationRead() {
    var request = ReadStartSessionTemplateAssociationRequest.getDefaultInstance();
    var decoded = mock(StartSessionTemplateAssociationReadEvidence.Request.class);
    var result = mock(StartSessionTemplateAssociationReadEvidence.Result.class);
    var response = ReadStartSessionTemplateAssociationResponse.getDefaultInstance();
    Mockito.when(decoded.targetNamespace()).thenReturn("world-runtime");

    try (MockedStatic<StartSessionTemplateAssociationReadGrpcCodec> codec =
        Mockito.mockStatic(StartSessionTemplateAssociationReadGrpcCodec.class)) {
      codec
          .when(() -> StartSessionTemplateAssociationReadGrpcCodec.fromRequest(request))
          .thenReturn(decoded);
      codec
          .when(() -> StartSessionTemplateAssociationReadGrpcCodec.toResponse(result))
          .thenReturn(response);
      Mockito.when(owner.read(decoded)).thenReturn(result);

      var observer = invokeAs(request, "game-session-service");

      assertThat(observer.error).isNull();
      assertThat(observer.value).isEqualTo(response);
      assertThat(observer.completed).isTrue();
    }
  }

  private Collector invokeAs(ReadStartSessionTemplateAssociationRequest request, String workload) {
    var peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/world-runtime/sa/" + workload, "world-runtime", workload);
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      var observer = new Collector();
      service.readStartSessionTemplateAssociation(request, observer);
      return observer;
    } finally {
      context.detach(previous);
    }
  }

  private static final class Collector
      implements StreamObserver<ReadStartSessionTemplateAssociationResponse> {
    private ReadStartSessionTemplateAssociationResponse value;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(ReadStartSessionTemplateAssociationResponse response) {
      value = response;
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
