package net.firedevops.firemud.gamesession.client;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.ManagedChannel;
import jakarta.annotation.PostConstruct;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.PingRequest;
import net.firedevops.firemud.entitymanagement.v1.PingResponse;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.config.GrpcClientProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** gRPC client for the Entity Management Service. */
@Component
@ConditionalOnProperty(
    name = "game-session.dev-isolated",
    havingValue = "false",
    matchIfMissing = false)
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Configuration and channel references remain internal")
public final class EntityManagementClient implements AutoCloseable {
  private final ServiceEndpointsProperties endpoints;
  private final GrpcClientProperties tlsProps;
  private final DevIsolatedProperties devIsolatedProperties;
  private final GrpcChannelFactory channelFactory;
  private static final org.slf4j.Logger logger =
      LoggingUtil.getLogger(EntityManagementClient.class);
  private ManagedChannel channel;
  private EntityManagementServiceGrpc.EntityManagementServiceBlockingStub stub;

  public EntityManagementClient(
      ServiceEndpointsProperties endpoints,
      GrpcClientProperties tlsProps,
      DevIsolatedProperties devIsolatedProperties,
      GrpcChannelFactory channelFactory) {
    this.endpoints = endpoints;
    this.tlsProps = tlsProps;
    this.devIsolatedProperties = devIsolatedProperties;
    this.channelFactory = channelFactory;
  }

  @PostConstruct
  void init() throws SSLException {
    if (devIsolatedProperties.isDevIsolated()) {
      logger.info(
          "Dev-isolated mode enabled; skipping EntityManagementClient channel initialization");
      return;
    }
    String target = endpoints.getEntityManagementService();
    if (target == null || target.isEmpty()) {
      target = "entity-management-service:6565";
    }
    channel = channelFactory.buildChannel(target, 6565, tlsProps, true);
    stub = EntityManagementServiceGrpc.newBlockingStub(channel).withCompression("gzip");
  }

  /** Simple ping to verify connectivity. */
  public PingResponse ping() {
    return stub.ping(PingRequest.newBuilder().build());
  }

  @Override
  public void close() {
    if (channel != null) {
      channel.shutdown();
    }
  }
}
