package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.service.CanonicalGameInstanceLaunchAssociationReadService;
import net.firedevops.firemud.gamesession.service.impl.GameSessionControlPlaneGrpcService;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class CanonicalGameInstanceLaunchAssociationReadGrpcAuthTest {
  private static final String NAMESPACE = "gameplay";

  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void rejectsNonWorldPeerBeforeInvokingTheOwnerReadService() {
    CanonicalGameInstanceLaunchAssociationReadService ownerReadService =
        mock(CanonicalGameInstanceLaunchAssociationReadService.class);
    GameSessionControlPlaneGrpcService grpcService = grpcService(ownerReadService);
    CapturingObserver response = new CapturingObserver();

    runAsPeer(
        "entity-management-service",
        () ->
            grpcService.getCanonicalGameInstanceLaunchAssociation(
                GetCanonicalGameInstanceLaunchAssociationRequest.newBuilder().build(), response));

    assertThat(response.value.getError().getCode()).isEqualTo("PERMISSION_DENIED");
    verifyNoInteractions(ownerReadService);
  }

  @Test
  void rejectsAuthenticatedUserContextEvenWhenWorldPeerCertificateMatches() {
    CanonicalGameInstanceLaunchAssociationReadService ownerReadService =
        mock(CanonicalGameInstanceLaunchAssociationReadService.class);
    GameSessionControlPlaneGrpcService grpcService = grpcService(ownerReadService);
    SessionContext.setContext("account", List.of("player"), Map.of());
    CapturingObserver response = new CapturingObserver();

    runAsPeer(
        "world-management-service",
        () ->
            grpcService.getCanonicalGameInstanceLaunchAssociation(
                GetCanonicalGameInstanceLaunchAssociationRequest.newBuilder().build(), response));

    assertThat(response.value.getError().getCode()).isEqualTo("PERMISSION_DENIED");
    verifyNoInteractions(ownerReadService);
  }

  private static GameSessionControlPlaneGrpcService grpcService(
      CanonicalGameInstanceLaunchAssociationReadService ownerReadService) {
    var service =
        new GameSessionControlPlaneGrpcService(
            null, null, null, null, null, null, new SimpleMeterRegistry());
    @SuppressWarnings("unchecked")
    ObjectProvider<CanonicalGameInstanceLaunchAssociationReadService> provider =
        mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(ownerReadService);
    service.configureCanonicalGameInstanceLaunchAssociationOwnerReadBoundary(provider, NAMESPACE);
    return service;
  }

  private static void runAsPeer(String service, Runnable action) {
    String uri = "spiffe://firemud/ns/" + NAMESPACE + "/sa/" + service;
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, new GrpcPeerIdentity(uri, NAMESPACE, service))
        .run(action);
  }

  private static final class CapturingObserver
      implements StreamObserver<GetCanonicalGameInstanceLaunchAssociationResponse> {
    private GetCanonicalGameInstanceLaunchAssociationResponse value;

    @Override
    public void onNext(GetCanonicalGameInstanceLaunchAssociationResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable throwable) {
      throw new AssertionError(throwable);
    }

    @Override
    public void onCompleted() {}
  }
}
