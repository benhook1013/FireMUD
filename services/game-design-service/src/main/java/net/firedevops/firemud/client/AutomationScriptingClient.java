package net.firedevops.firemud.client;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.util.List;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.NotifyScriptVersionUpdateRequest;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.config.GrpcClientProperties;
import org.springframework.stereotype.Component;

/** gRPC client for Automation & Scripting Service. */
@Component
public class AutomationScriptingClient implements AutoCloseable {
  private final ServiceEndpointsProperties endpoints;
  private final GrpcClientProperties tlsProps;
  private ManagedChannel channel;
  private AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub stub;

  public AutomationScriptingClient(
      ServiceEndpointsProperties endpoints, GrpcClientProperties tlsProps) {
    this.endpoints = endpoints;
    this.tlsProps = tlsProps;
  }

  @PostConstruct
  void init() throws SSLException {
    String target = endpoints.getAutomationScriptingService();
    if (target == null || target.isEmpty()) {
      target = "automation-scripting-service:6565";
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
    stub = AutomationScriptingServiceGrpc.newBlockingStub(channel);
  }

  /** Notify the Automation service that a new script patch version is active. */
  public void notifyScriptVersionUpdate(long gameId, String patchVersion, List<String> scripts) {
    NotifyScriptVersionUpdateRequest request =
        NotifyScriptVersionUpdateRequest.newBuilder()
            .setGameId(gameId)
            .setScriptPatchVersion(patchVersion)
            .addAllAffectedScripts(scripts)
            .build();
    stub.notifyScriptVersionUpdate(request);
  }

  @Override
  public void close() {
    if (channel != null) {
      channel.shutdown();
    }
  }
}
