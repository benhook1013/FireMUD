package net.firedevops.firemud.client;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PostConstruct;
import java.io.File;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.config.GrpcClientProperties;
import net.firedevops.firemud.loggingadmin.v1.ApplyModerationActionRequest;
import net.firedevops.firemud.loggingadmin.v1.LoggingAdminServiceGrpc;
import org.springframework.stereotype.Component;

/** Client for communicating with the Logging & Admin Service. */
@Component
public class LoggingAdminClient implements AutoCloseable {
  private final ServiceEndpointsProperties endpoints;
  private final GrpcClientProperties tlsProps;

  private ManagedChannel channel;
  private LoggingAdminServiceGrpc.LoggingAdminServiceBlockingStub stub;

  public LoggingAdminClient(ServiceEndpointsProperties endpoints, GrpcClientProperties tlsProps) {
    this.endpoints = endpoints;
    this.tlsProps = tlsProps;
  }

  @PostConstruct
  void init() throws SSLException {
    String target = endpoints.getLoggingAdminService();
    if (target == null || target.isEmpty()) {
      target = "logging-admin-service:6565";
    }
    String[] parts = target.split(":");
    String host = parts[0];
    int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 6565;
    var sslContext =
        GrpcSslContexts.forClient()
            .trustManager(new File(tlsProps.getCaCert()))
            .keyManager(new File(tlsProps.getCertChain()), new File(tlsProps.getPrivateKey()))
            .build();
    channel = NettyChannelBuilder.forAddress(host, port).sslContext(sslContext).build();
    stub = LoggingAdminServiceGrpc.newBlockingStub(channel);
  }

  /** Log that a new account was created. */
  public void logAccountCreation(long tenantId, long accountId) {
    ApplyModerationActionRequest request =
        ApplyModerationActionRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setAccountId(Long.toString(accountId))
            .setAction("ACCOUNT_CREATED")
            .setReason("")
            .build();
    stub.applyModerationAction(request);
  }

  @Override
  public void close() {
    if (channel != null) {
      channel.shutdown();
    }
  }
}
