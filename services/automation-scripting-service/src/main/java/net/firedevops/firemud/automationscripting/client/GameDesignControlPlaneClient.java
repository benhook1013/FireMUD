package net.firedevops.firemud.automationscripting.client;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.GetPublishedPluginVersionRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedPluginVersionResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GameDesignControlPlaneClient
    extends AbstractBlockingGrpcClient<GameDesignServiceGrpc.GameDesignServiceBlockingStub> {
  private static final Logger logger = LoggerFactory.getLogger(GameDesignControlPlaneClient.class);
  private static final long CALL_DEADLINE_SECONDS = 3L;

  public GameDesignControlPlaneClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties grpcClientProperties,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, grpcClientProperties, channelFactory, stubCustomizer);
  }

  @PostConstruct
  void init() throws Exception {
    initClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameDesignService();
  }

  @Override
  protected String defaultTarget() {
    return "game-design-service:6565";
  }

  @Override
  protected GameDesignServiceGrpc.GameDesignServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        GameDesignServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  public GetPublishedPluginVersionResponse getPublishedPluginVersion(
      String tenantId, String pluginId, String pluginVersionId) {
    if (stub() == null) {
      return unavailable();
    }
    try {
      return stub()
          .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
          .getPublishedPluginVersion(
              GetPublishedPluginVersionRequest.newBuilder()
                  .setTenantId(tenantId)
                  .setPluginId(pluginId)
                  .setPluginVersionId(pluginVersionId)
                  .build());
    } catch (RuntimeException ex) {
      logger.warn("Game Design getPublishedPluginVersion failed", ex);
      return unavailable();
    }
  }

  private static GetPublishedPluginVersionResponse unavailable() {
    return GetPublishedPluginVersionResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("GAME_DESIGN_UNAVAILABLE")
                .setMessage("Game Design service unavailable"))
        .build();
  }
}
