package net.firedevops.firemud.loggingadmin.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.v1.ExecutePreparedVersionCutoverRequest;
import net.firedevops.firemud.gamesession.v1.ExecutePreparedVersionCutoverResponse;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointerAuditResponse;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersRequest;
import net.firedevops.firemud.gamesession.v1.ListAdmissionPointersResponse;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerRequest;
import net.firedevops.firemud.gamesession.v1.SetAdmissionPointerResponse;
import org.springframework.stereotype.Component;

@Component
public class GameSessionControlPlaneClient
    extends AbstractReloadingBlockingGrpcClient<
        GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub> {
  public GameSessionControlPlaneClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer, GameSessionControlPlaneClient.class);
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameSessionService();
  }

  @Override
  protected String defaultTarget() {
    return "game-session-service:6565";
  }

  @Override
  protected GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        GameSessionControlPlaneServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  public ListAdmissionPointersResponse listAdmissionPointers() {
    return stub().listAdmissionPointers(ListAdmissionPointersRequest.getDefaultInstance());
  }

  public ListAdmissionPointerAuditResponse listAdmissionPointerAudit(
      String worldSlug, String realmSlug) {
    return stub()
        .listAdmissionPointerAudit(
            ListAdmissionPointerAuditRequest.newBuilder()
                .setWorldSlug(worldSlug)
                .setRealmSlug(realmSlug)
                .build());
  }

  public SetAdmissionPointerResponse setAdmissionPointer(SetAdmissionPointerRequest request) {
    return stub().setAdmissionPointer(request);
  }

  public ExecutePreparedVersionCutoverResponse executePreparedVersionCutover(
      ExecutePreparedVersionCutoverRequest request) {
    return stub().executePreparedVersionCutover(request);
  }
}
