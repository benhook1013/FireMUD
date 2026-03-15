package net.firedevops.firemud.gamesession.client;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.config.GrpcClientProperties;
import net.firedevops.firemud.worldmanagement.v1.PingRequest;
import net.firedevops.firemud.worldmanagement.v1.PingResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** gRPC client for the World Management Service. */
@Component
@ConditionalOnProperty(
    name = "game-session.dev-isolated",
    havingValue = "false",
    matchIfMissing = false)
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Configuration and channel references remain internal")
public final class WorldManagementClient implements AutoCloseable {
  private final ServiceEndpointsProperties endpoints;
  private final GrpcClientProperties tlsProps;
  private final DevIsolatedProperties devIsolatedProperties;
  private static final org.slf4j.Logger logger = LoggingUtil.getLogger(WorldManagementClient.class);
  private ManagedChannel channel;
  private WorldManagementServiceGrpc.WorldManagementServiceBlockingStub stub;

  public WorldManagementClient(
      ServiceEndpointsProperties endpoints,
      GrpcClientProperties tlsProps,
      DevIsolatedProperties devIsolatedProperties) {
    this.endpoints = endpoints;
    this.tlsProps = tlsProps;
    this.devIsolatedProperties = devIsolatedProperties;
  }

  @PostConstruct
  void init() throws SSLException {
    if (devIsolatedProperties.isDevIsolated()) {
      logger.info(
          "Dev-isolated mode enabled; skipping WorldManagementClient channel initialization");
      return;
    }
    String target = endpoints.getWorldManagementService();
    if (target == null || target.isEmpty()) {
      target = "world-management-service:6565";
    }
    String[] parts = target.split(":");
    String host = parts[0];
    int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 6565;
    if (tlsProps.isPlaintext()) {
      channel =
          ManagedChannelBuilder.forAddress(host, port)
              .keepAliveTime(30, TimeUnit.SECONDS)
              .keepAliveTimeout(5, TimeUnit.SECONDS)
              .keepAliveWithoutCalls(true)
              .usePlaintext()
              .build();
    } else {
      var sslContext =
          GrpcSslContexts.forClient()
              .trustManager(new File(tlsProps.getCaCert()))
              .keyManager(new File(tlsProps.getCertChain()), new File(tlsProps.getPrivateKey()))
              .build();
      channel =
          NettyChannelBuilder.forAddress(host, port)
              .sslContext(sslContext)
              .keepAliveTime(30, TimeUnit.SECONDS)
              .keepAliveTimeout(5, TimeUnit.SECONDS)
              .keepAliveWithoutCalls(true)
              .build();
    }
    stub = WorldManagementServiceGrpc.newBlockingStub(channel).withCompression("gzip");
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
