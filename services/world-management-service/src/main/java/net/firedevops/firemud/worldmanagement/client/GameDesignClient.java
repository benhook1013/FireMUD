package net.firedevops.firemud.worldmanagement.client;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.GrpcClientAuth;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ListVersionsRequest;
import net.firedevops.firemud.gamedesign.v1.ListVersionsResponse;
import org.springframework.stereotype.Component;

/** gRPC client for communicating with the Game Design Service. */
@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected configuration and channel references are not exposed")
public class GameDesignClient
    extends AbstractReloadingBlockingGrpcClient<
        GameDesignServiceGrpc.GameDesignServiceBlockingStub> {
  private final JwtUtil jwtUtil;

  public GameDesignClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      JwtUtil jwtUtil,
      GrpcChannelFactory channelFactory) {
    super(endpoints, tlsProps, channelFactory, GameDesignClient.class);
    this.jwtUtil = jwtUtil;
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
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
    return GrpcClientAuth.attach(
        GameDesignServiceGrpc.newBlockingStub(channel).withCompression("gzip"), jwtUtil);
  }

  /** Returns published versions for the given tenant. */
  public ListVersionsResponse listVersions(long tenantId) {
    ListVersionsRequest request =
        ListVersionsRequest.newBuilder().setTenantId(String.valueOf(tenantId)).build();
    return stub().listVersions(request);
  }
}
